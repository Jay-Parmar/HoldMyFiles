package dev.jay.holdmyfiles.core.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerReadinessTest {
    @Test
    fun `health route reports readiness`() = testApplication {
        application { holdMyFilesModule() }

        val response = client.get("/api/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }

    @Test
    fun `responses carry restrictive browser headers`() = testApplication {
        application { holdMyFilesModule() }

        val response = client.get("/api/v1/health")

        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertTrue(
            response.headers["Content-Security-Policy"]
                .orEmpty()
                .contains("frame-ancestors 'none'"),
        )
    }
}
