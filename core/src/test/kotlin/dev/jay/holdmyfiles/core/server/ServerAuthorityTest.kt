package dev.jay.holdmyfiles.core.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAuthorityTest {
    @Test
    fun `authority rejects requests until an endpoint is published`() = testApplication {
        val authority = MutableAuthoritySource()
        application {
            holdMyFilesModule(ServerDependencies(authoritySource = authority))
        }

        val beforePublish = client.get("/api/v1/health") {
            header(HttpHeaders.Host, "127.0.0.1:8080")
        }
        authority.publish(BoundEndpoint("127.0.0.1", 8080))
        val whilePublished = client.get("/api/v1/health") {
            header(HttpHeaders.Host, "127.0.0.1:8080")
        }
        authority.clear()
        val afterClear = client.get("/api/v1/health") {
            header(HttpHeaders.Host, "127.0.0.1:8080")
        }

        assertEquals(HttpStatusCode(421, "Misdirected Request"), beforePublish.status)
        assertEquals(HttpStatusCode.OK, whilePublished.status)
        assertEquals(HttpStatusCode(421, "Misdirected Request"), afterClear.status)
    }

    @Test
    fun `endpoint formats ipv4 and ipv6 authorities`() {
        assertEquals("192.168.1.4:42123", BoundEndpoint("192.168.1.4", 42123).authority)
        assertEquals("[fe80::1]:42123", BoundEndpoint("fe80::1", 42123).authority)
        assertEquals("http://[fe80::1]:42123", BoundEndpoint("fe80::1", 42123).origin)
    }
}
