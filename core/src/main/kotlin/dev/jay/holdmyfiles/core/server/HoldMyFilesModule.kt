package dev.jay.holdmyfiles.core.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable

private val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        call.response.headers.apply {
            append(HttpHeaders.CacheControl, "no-store")
            append("X-Content-Type-Options", "nosniff")
            append("Referrer-Policy", "no-referrer")
            append("X-Frame-Options", "DENY")
            append(
                "Content-Security-Policy",
                "default-src 'self'; object-src 'none'; frame-ancestors 'none'; " +
                    "base-uri 'none'; form-action 'self'",
            )
        }
    }
}

fun Application.holdMyFilesModule() {
    install(ContentNegotiation) {
        json()
    }
    install(SecurityHeaders)

    routing {
        get("/api/v1/health") {
            call.respond(HealthResponse())
        }
    }
}

@Serializable
private data class HealthResponse(
    val status: String = "ok",
)
