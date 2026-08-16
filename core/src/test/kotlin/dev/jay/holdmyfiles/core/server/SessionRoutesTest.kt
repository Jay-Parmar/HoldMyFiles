package dev.jay.holdmyfiles.core.server

import dev.jay.holdmyfiles.core.security.MonotonicClock
import dev.jay.holdmyfiles.core.security.PinRateLimiter
import dev.jay.holdmyfiles.core.security.RandomByteSource
import dev.jay.holdmyfiles.core.security.RandomNumberSource
import dev.jay.holdmyfiles.core.security.RunPinGenerator
import dev.jay.holdmyfiles.core.security.SessionAuthenticator
import dev.jay.holdmyfiles.core.security.SessionRegistry
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRoutesTest {
    @Test
    fun `valid pin creates a memory only strict session cookie`() = testApplication {
        val authenticator = authenticator()
        application { holdMyFilesModule(dependencies(authenticator)) }

        val response = client.post("/api/v1/session") {
            validOrigin()
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"000042"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val cookie = response.headers[HttpHeaders.SetCookie]
        assertNotNull(cookie)
        assertTrue(cookie.orEmpty().contains("Path=/"))
        assertTrue(cookie.orEmpty().contains("HttpOnly"))
        assertTrue(cookie.orEmpty().contains("SameSite=Strict"))
        assertFalse(cookie.orEmpty().contains("Domain="))
        assertFalse(cookie.orEmpty().contains("Max-Age="))
        val token = cookie.orEmpty().substringAfter("hmf_session=").substringBefore(';')
        assertTrue(authenticator.validate(token))
    }

    @Test
    fun `invalid pin returns a generic unauthorized response`() = testApplication {
        application { holdMyFilesModule(dependencies(authenticator())) }

        val response = client.post("/api/v1/session") {
            validOrigin()
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"000043"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(response.headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `wrong origin is rejected before consuming a pin attempt`() = testApplication {
        val authenticator = authenticator(peerCapacity = 1)
        application { holdMyFilesModule(dependencies(authenticator)) }
        val rejected = client.post("/api/v1/session") {
            header(HttpHeaders.Host, "localhost:80")
            header(HttpHeaders.Origin, "http://evil.example")
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"000042"}""")
        }

        val accepted = client.post("/api/v1/session") {
            validOrigin()
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"000042"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, rejected.status)
        assertEquals(HttpStatusCode.NoContent, accepted.status)
    }

    @Test
    fun `unexpected host is rejected`() = testApplication {
        application {
            holdMyFilesModule(
                ServerDependencies(
                    allowedAuthority = "expected.example",
                    authenticator = authenticator(),
                ),
            )
        }

        val response = client.post("/api/v1/session") {
            validOrigin()
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"000042"}""")
        }

        assertEquals(HttpStatusCode(421, "Misdirected Request"), response.status)
    }

    @Test
    fun `forwarded addresses do not bypass the socket peer limit`() = testApplication {
        val authenticator = authenticator(peerCapacity = 1, peerRefillMillis = 10_000)
        application { holdMyFilesModule(dependencies(authenticator)) }
        client.post("/api/v1/session") {
            validOrigin()
            header("X-Forwarded-For", "192.168.1.10")
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"wrong"}""")
        }

        val response = client.post("/api/v1/session") {
            validOrigin()
            header("X-Forwarded-For", "192.168.1.11")
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"000042"}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("10", response.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `malformed login body returns bad request`() = testApplication {
        application { holdMyFilesModule(dependencies(authenticator())) }

        val response = client.post("/api/v1/session") {
            validOrigin()
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `oversized login body is rejected`() = testApplication {
        application { holdMyFilesModule(dependencies(authenticator())) }

        val response = client.post("/api/v1/session") {
            validOrigin()
            val oversizedBody = "{\"pin\":\"${"0".repeat(200)}\"}"
            setBody(UnknownLengthJsonContent(oversizedBody))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `encoded login body is rejected`() = testApplication {
        application { holdMyFilesModule(dependencies(authenticator())) }

        val response = client.post("/api/v1/session") {
            validOrigin()
            header(HttpHeaders.ContentEncoding, "gzip")
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"000042"}""")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `logout revokes and expires the session cookie`() = testApplication {
        val authenticator = authenticator()
        application { holdMyFilesModule(dependencies(authenticator)) }
        val login = client.post("/api/v1/session") {
            validOrigin()
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"000042"}""")
        }
        val cookie = login.headers[HttpHeaders.SetCookie].orEmpty().substringBefore(';')
        val token = cookie.substringAfter("hmf_session=")

        val response = client.delete("/api/v1/session") {
            validOrigin()
            header(HttpHeaders.Cookie, cookie)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertFalse(authenticator.validate(token))
        val expiredCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
        assertTrue(expiredCookie.contains("hmf_session="))
        assertTrue(expiredCookie.contains("Path=/"))
        assertTrue(expiredCookie.contains("Max-Age=0"))
        assertTrue(expiredCookie.contains("HttpOnly"))
        assertTrue(expiredCookie.contains("SameSite=Strict"))
    }

    private fun dependencies(authenticator: SessionAuthenticator) = ServerDependencies(
        allowedAuthority = "localhost:80",
        authenticator = authenticator,
    )

    private fun authenticator(
        peerCapacity: Int = 5,
        peerRefillMillis: Long = 30_000,
    ): SessionAuthenticator {
        var seed = 0
        return SessionAuthenticator(
            pin = RunPinGenerator(RandomNumberSource { 42 }).generate(),
            sessions = SessionRegistry(
                random = RandomByteSource { destination -> destination.fill(seed++.toByte()) },
            ),
            limiter = PinRateLimiter(
                clock = FixedClock,
                peerCapacity = peerCapacity,
                peerRefillMillis = peerRefillMillis,
                globalCapacity = 20,
                globalRefillMillis = 5_000,
                maxTrackedPeers = 20,
            ),
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.validOrigin() {
        header(HttpHeaders.Host, "localhost:80")
        header(HttpHeaders.Origin, "http://localhost:80")
    }

    private data object FixedClock : MonotonicClock {
        override fun nowMillis(): Long = 0
    }

    private class UnknownLengthJsonContent(
        private val value: String,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.Json

        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeStringUtf8(value)
        }
    }
}
