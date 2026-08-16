package dev.jay.holdmyfiles.core.server

import dev.jay.holdmyfiles.core.security.LoginResult
import dev.jay.holdmyfiles.core.security.SessionAuthenticator
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

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

class ServerDependencies(
    val allowedAuthority: String = "localhost:80",
    val authenticator: SessionAuthenticator? = null,
) {
    init {
        require(allowedAuthority.isNotBlank() && allowedAuthority.length <= 255)
        require(allowedAuthority.none { character ->
            character.isWhitespace() || character.isISOControl() || character == '/'
        })
    }

    val allowedOrigin: String = "http://$allowedAuthority"
}

fun Application.holdMyFilesModule(
    dependencies: ServerDependencies = ServerDependencies(),
) {
    install(ContentNegotiation) {
        json()
    }
    install(SecurityHeaders)

    routing {
        get("/api/v1/health") {
            if (call.rejectUnexpectedHost(dependencies.allowedAuthority)) {
                return@get
            }
            call.respond(HealthResponse())
        }

        dependencies.authenticator?.let { authenticator ->
            post("/api/v1/session") {
                if (call.rejectUnexpectedHost(dependencies.allowedAuthority)) {
                    return@post
                }
                if (call.request.headers[HttpHeaders.Origin] != dependencies.allowedOrigin) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiError("origin_rejected", "Request origin is not allowed."),
                    )
                    return@post
                }
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                    call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        ApiError("invalid_content_type", "Use application/json."),
                    )
                    return@post
                }
                if (call.request.headers[HttpHeaders.ContentEncoding] != null) {
                    call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        ApiError("content_encoding_rejected", "Encoded requests are not supported."),
                    )
                    return@post
                }

                val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (contentLength != null && contentLength > MAX_LOGIN_BODY_BYTES) {
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ApiError("body_too_large", "Login request is too large."),
                    )
                    return@post
                }

                val bodyBytes = call.receiveChannel()
                    .readRemaining(MAX_LOGIN_BODY_BYTES + 1)
                    .readByteArray()
                if (bodyBytes.size > MAX_LOGIN_BODY_BYTES) {
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ApiError("body_too_large", "Login request is too large."),
                    )
                    return@post
                }
                val body = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bodyBytes))
                        .toString()
                } catch (_: CharacterCodingException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_request", "Login request is invalid."),
                    )
                    return@post
                }
                val request = try {
                    LOGIN_JSON.decodeFromString<LoginRequest>(body)
                } catch (_: SerializationException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_request", "Login request is invalid."),
                    )
                    return@post
                }

                when (
                    val result = authenticator.login(
                        peerAddress = call.request.local.remoteAddress,
                        candidatePin = request.pin,
                    )
                ) {
                    is LoginResult.Authenticated -> {
                        call.request.cookies[SESSION_COOKIE]?.let(authenticator::logout)
                        call.response.headers.append(
                            HttpHeaders.SetCookie,
                            "$SESSION_COOKIE=${result.session.encodedValue()}; " +
                                "Path=/; HttpOnly; SameSite=Strict",
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }

                    LoginResult.InvalidCredentials -> call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiError("invalid_credentials", "PIN is not valid."),
                    )

                    is LoginResult.RateLimited -> {
                        call.response.header(
                            HttpHeaders.RetryAfter,
                            result.retryAfterMillis.toRetryAfterSeconds().toString(),
                        )
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            ApiError("too_many_attempts", "Try again later."),
                        )
                    }

                    LoginResult.CapacityReached -> call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiError("server_busy", "Try again later."),
                    )
                }
            }
        }
    }
}

private suspend fun ApplicationCall.rejectUnexpectedHost(allowedAuthority: String): Boolean {
    val requestAuthorities = request.headers.getAll(HttpHeaders.Host)
    if (requestAuthorities == listOf(allowedAuthority)) {
        return false
    }

    respond(
        MISDIRECTED_REQUEST,
        ApiError("unexpected_host", "Use the address shown in the app."),
    )
    return true
}

private fun Long.toRetryAfterSeconds(): Long = this / 1_000 + if (this % 1_000 == 0L) 0 else 1

private val MISDIRECTED_REQUEST = HttpStatusCode(421, "Misdirected Request")
private val LOGIN_JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
}
private const val SESSION_COOKIE = "hmf_session"
private const val MAX_LOGIN_BODY_BYTES = 128L

@Serializable
private class LoginRequest(
    val pin: String,
) {
    override fun toString(): String = "LoginRequest(redacted)"
}

@Serializable
private data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
private data class HealthResponse(
    val status: String = "ok",
)
