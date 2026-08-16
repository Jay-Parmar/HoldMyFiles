package dev.jay.holdmyfiles.core.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostGuardTest {
    @Test
    fun `unexpected host is rejected before any application route`() = testApplication {
        application {
            holdMyFilesModule(ServerDependencies(allowedAuthority = "phone.test:8080"))
            routing {
                get("/probe") {
                    call.respondText("route reached")
                }
            }
        }

        val response = client.get("/probe") {
            header(HttpHeaders.Host, "evil.test:8080")
        }

        assertEquals(HttpStatusCode(421, "Misdirected Request"), response.status)
        assertTrue(response.bodyAsText().contains("unexpected_host"))
    }

    @Test
    fun `expected host reaches application routes`() = testApplication {
        application {
            holdMyFilesModule(ServerDependencies(allowedAuthority = "phone.test:8080"))
            routing {
                get("/probe") {
                    call.respondText("route reached")
                }
            }
        }

        val response = client.get("/probe") {
            header(HttpHeaders.Host, "phone.test:8080")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("route reached", response.bodyAsText())
    }
}
