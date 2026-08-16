package dev.jay.holdmyfiles.core.server

import dev.jay.holdmyfiles.core.security.LoginResult
import dev.jay.holdmyfiles.core.security.SessionAuthenticator
import dev.jay.holdmyfiles.core.storage.BrowseOutcome
import dev.jay.holdmyfiles.core.storage.GuestBrowser
import dev.jay.holdmyfiles.core.storage.GuestListing
import dev.jay.holdmyfiles.core.storage.OpenedFile
import dev.jay.holdmyfiles.core.storage.StorageNodeKind
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.isHandled
import io.ktor.server.application.install
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.header
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import io.ktor.util.pipeline.PipelinePhase
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
            append("Cross-Origin-Resource-Policy", "same-origin")
            append(
                "Content-Security-Policy",
                "default-src 'self'; object-src 'none'; frame-ancestors 'none'; " +
                    "base-uri 'none'; form-action 'self'",
            )
        }
    }
}

private val SafeErrors = createApplicationPlugin("SafeErrors") {
    on(CallFailed) { call, cause ->
        if (cause is CancellationException) {
            throw cause
        }
        call.respond(
            HttpStatusCode.InternalServerError,
            ApiError("internal_error", "The server could not complete this request."),
        )
    }
}

private val SafeFallbackPhase = PipelinePhase("SafeFallback")

class ServerDependencies(
    val allowedAuthority: String = "localhost:80",
    val authenticator: SessionAuthenticator? = null,
    val browser: GuestBrowser? = null,
    val guestAssets: GuestWebAssets? = null,
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
    install(SafeErrors)
    intercept(ApplicationCallPipeline.Plugins) {
        if (context.request.headers.getAll(HttpHeaders.Host) != listOf(dependencies.allowedAuthority)) {
            context.respond(
                MISDIRECTED_REQUEST,
                ApiError("unexpected_host", "Use the address shown in the app."),
            )
            finish()
        }
    }
    insertPhaseBefore(ApplicationCallPipeline.Fallback, SafeFallbackPhase)
    intercept(SafeFallbackPhase) {
        if (!context.isHandled) {
            context.respondUnhandledRoute()
            finish()
        }
    }

    routing {
        get("/api/v1/health") {
            call.respond(HealthResponse())
        }

        dependencies.guestAssets?.let { assets ->
            get("/") {
                call.respondBytes(
                    assets.indexHtml,
                    ContentType.Text.Html.withCharset(Charsets.UTF_8),
                )
            }
            get("/assets/app.css") {
                call.respondBytes(
                    assets.styleSheet,
                    ContentType.Text.CSS.withCharset(Charsets.UTF_8),
                )
            }
            get("/assets/app.js") {
                call.respondBytes(assets.script, JAVASCRIPT_CONTENT_TYPE)
            }
        }

        dependencies.authenticator?.let { authenticator ->
            post("/api/v1/session") {
                if (
                    call.request.headers.getAll(HttpHeaders.Origin) !=
                    listOf(dependencies.allowedOrigin)
                ) {
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

            delete("/api/v1/session") {
                if (
                    call.request.headers.getAll(HttpHeaders.Origin) !=
                    listOf(dependencies.allowedOrigin)
                ) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiError("origin_rejected", "Request origin is not allowed."),
                    )
                    return@delete
                }

                call.request.cookies[SESSION_COOKIE]?.let(authenticator::logout)
                call.response.headers.append(
                    HttpHeaders.SetCookie,
                    "$SESSION_COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict",
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }

        val authenticator = dependencies.authenticator
        val browser = dependencies.browser
        if (authenticator != null && browser != null) {
            get("/api/v1/shares") {
                if (!call.requireSession(authenticator)) {
                    return@get
                }

                call.respondListing(browser.roots())
            }

            get("/api/v1/nodes/{handle}") {
                if (!call.requireSession(authenticator)) {
                    return@get
                }

                call.respondListing(browser.list(call.parameters["handle"].orEmpty()))
            }

            head("/api/v1/files/{handle}") {
                if (!call.requireSession(authenticator)) {
                    return@head
                }

                call.respondFile(browser.open(call.parameters["handle"].orEmpty()), includeContent = false)
            }

            get("/api/v1/files/{handle}") {
                if (!call.requireSession(authenticator)) {
                    return@get
                }

                call.respondFile(browser.open(call.parameters["handle"].orEmpty()), includeContent = true)
            }
        }
    }
}

private suspend fun ApplicationCall.respondUnhandledRoute() {
    val allowedMethods = request.path().allowedMethods()
    if (allowedMethods == null) {
        respond(
            HttpStatusCode.NotFound,
            ApiError("route_not_found", "This route does not exist."),
        )
        return
    }

    response.header(HttpHeaders.Allow, allowedMethods)
    respond(
        HttpStatusCode.MethodNotAllowed,
        ApiError("method_not_allowed", "This request method is not supported."),
    )
}

private fun String.allowedMethods(): String? = when {
    this == "/" -> "GET"
    this == "/assets/app.css" -> "GET"
    this == "/assets/app.js" -> "GET"
    this == "/api/v1/health" -> "GET"
    this == "/api/v1/session" -> "POST, DELETE"
    this == "/api/v1/shares" -> "GET"
    isSingleHandlePath("/api/v1/nodes/") -> "GET"
    isSingleHandlePath("/api/v1/files/") -> "GET, HEAD"
    else -> null
}

private fun String.isSingleHandlePath(prefix: String): Boolean {
    if (!startsWith(prefix)) {
        return false
    }
    val handle = removePrefix(prefix)
    return handle.isNotEmpty() && '/' !in handle
}

private suspend fun ApplicationCall.requireSession(
    authenticator: SessionAuthenticator,
): Boolean {
    val encodedSession = request.cookies[SESSION_COOKIE]
    if (encodedSession != null && authenticator.validate(encodedSession)) {
        return true
    }

    respond(
        HttpStatusCode.Unauthorized,
        ApiError("session_required", "Enter the current PIN to continue."),
    )
    return false
}

private suspend fun ApplicationCall.respondListing(
    outcome: BrowseOutcome<GuestListing>,
) {
    when (outcome) {
        is BrowseOutcome.Ok -> respond(outcome.value.toResponse())
        BrowseOutcome.InvalidHandle,
        BrowseOutcome.StaleHandle,
        BrowseOutcome.Missing,
        -> respond(
            HttpStatusCode.NotFound,
            ApiError("item_unavailable", "This item is no longer available."),
        )

        BrowseOutcome.WrongKind -> respond(
            HttpStatusCode.BadRequest,
            ApiError("wrong_item_type", "This item cannot be opened as a folder."),
        )

        BrowseOutcome.Busy -> {
            response.header(HttpHeaders.RetryAfter, "1")
            respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("server_busy", "Try again shortly."),
            )
        }

        BrowseOutcome.Unavailable -> respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("storage_unavailable", "Storage is temporarily unavailable."),
        )
    }
}

private suspend fun ApplicationCall.respondFile(
    outcome: BrowseOutcome<OpenedFile>,
    includeContent: Boolean,
) {
    when (outcome) {
        is BrowseOutcome.Ok -> {
            val openedFile = outcome.value
            try {
                response.header(
                    HttpHeaders.ContentDisposition,
                    attachmentContentDisposition(openedFile.displayName),
                )
                respondBytesWriter(
                    contentType = ContentType.Application.OctetStream,
                    status = HttpStatusCode.OK,
                    contentLength = openedFile.sizeBytes,
                ) {
                    if (includeContent) {
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            val count = openedFile.content.read(buffer, 0, buffer.size)
                            if (count == -1) {
                                break
                            }
                            check(count in 1..buffer.size) { "Invalid storage read count" }
                            writeFully(buffer, 0, count)
                        }
                    }
                }
            } finally {
                openedFile.close()
            }
        }

        BrowseOutcome.InvalidHandle,
        BrowseOutcome.StaleHandle,
        BrowseOutcome.Missing,
        -> respond(
            HttpStatusCode.NotFound,
            ApiError("item_unavailable", "This item is no longer available."),
        )

        BrowseOutcome.WrongKind -> respond(
            HttpStatusCode.BadRequest,
            ApiError("wrong_item_type", "This item cannot be downloaded."),
        )

        BrowseOutcome.Busy -> {
            response.header(HttpHeaders.RetryAfter, "1")
            respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("server_busy", "Try again shortly."),
            )
        }

        BrowseOutcome.Unavailable -> respond(
            HttpStatusCode.ServiceUnavailable,
            ApiError("storage_unavailable", "Storage is temporarily unavailable."),
        )
    }
}

private fun GuestListing.toResponse(): GuestListingResponse = GuestListingResponse(
    nodes = nodes.map { node ->
        GuestNodeResponse(
            handle = node.handle.encodedValue(),
            name = node.displayName,
            kind = when (node.kind) {
                StorageNodeKind.Directory -> "directory"
                StorageNodeKind.File -> "file"
            },
            sizeBytes = node.sizeBytes,
        )
    },
    truncated = truncated,
)

private fun Long.toRetryAfterSeconds(): Long = this / 1_000 + if (this % 1_000 == 0L) 0 else 1

private val MISDIRECTED_REQUEST = HttpStatusCode(421, "Misdirected Request")
private val LOGIN_JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
}
private val JAVASCRIPT_CONTENT_TYPE = ContentType.parse("text/javascript; charset=UTF-8")
private const val SESSION_COOKIE = "hmf_session"
private const val MAX_LOGIN_BODY_BYTES = 128L
private const val DOWNLOAD_BUFFER_BYTES = 32 * 1_024

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
private data class GuestListingResponse(
    val nodes: List<GuestNodeResponse>,
    val truncated: Boolean,
)

@Serializable
private data class GuestNodeResponse(
    val handle: String,
    val name: String,
    val kind: String,
    val sizeBytes: Long?,
)

@Serializable
private data class HealthResponse(
    val status: String = "ok",
)
