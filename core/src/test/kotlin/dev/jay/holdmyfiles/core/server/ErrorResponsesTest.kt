package dev.jay.holdmyfiles.core.server

import dev.jay.holdmyfiles.core.security.LoginResult
import dev.jay.holdmyfiles.core.security.RandomByteSource
import dev.jay.holdmyfiles.core.security.RandomNumberSource
import dev.jay.holdmyfiles.core.security.RunPinGenerator
import dev.jay.holdmyfiles.core.security.SessionAuthenticator
import dev.jay.holdmyfiles.core.security.SessionRegistry
import dev.jay.holdmyfiles.core.storage.BrowseOutcome
import dev.jay.holdmyfiles.core.storage.GuestBrowser
import dev.jay.holdmyfiles.core.storage.GuestListing
import dev.jay.holdmyfiles.core.storage.OpenedFile
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorResponsesTest {
    @Test
    fun `unknown route returns a safe json error`() = testApplication {
        application { holdMyFilesModule() }

        val response = client.get("/missing") {
            validHost()
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("route_not_found"))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `unsupported method returns a safe json error`() = testApplication {
        application { holdMyFilesModule() }

        val response = client.post("/api/v1/health") {
            validHost()
        }

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals("GET", response.headers[HttpHeaders.Allow])
        assertTrue(response.bodyAsText().contains("method_not_allowed"))
    }

    @Test
    fun `unexpected failure hides exception details`() = testApplication {
        val authenticator = authenticator()
        application {
            holdMyFilesModule(
                ServerDependencies(
                    authenticator = authenticator,
                    browser = FailingBrowser,
                ),
            )
        }

        val response = client.get("/api/v1/shares") {
            authenticated(authenticator)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("internal_error"))
        assertFalse(body.contains("content://secret-provider/root"))
        assertFalse(body.contains("provider exploded"))
    }

    private fun authenticator(): SessionAuthenticator {
        var seed = 0
        return SessionAuthenticator(
            pin = RunPinGenerator(RandomNumberSource { 42 }).generate(),
            sessions = SessionRegistry(
                random = RandomByteSource { destination -> destination.fill(seed++.toByte()) },
            ),
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.validHost() {
        header(HttpHeaders.Host, "localhost:80")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticated(
        authenticator: SessionAuthenticator,
    ) {
        validHost()
        val result = authenticator.login("test-peer", "000042")
        val token = (result as LoginResult.Authenticated).session.encodedValue()
        header(HttpHeaders.Cookie, "hmf_session=$token")
    }

    private data object FailingBrowser : GuestBrowser {
        override suspend fun roots(): BrowseOutcome<GuestListing> =
            error("provider exploded at content://secret-provider/root")

        override suspend fun list(encodedHandle: String): BrowseOutcome<GuestListing> =
            error("Not used")

        override suspend fun open(encodedHandle: String): BrowseOutcome<OpenedFile> =
            error("Not used")

        override fun clearHandles() = Unit
    }
}
