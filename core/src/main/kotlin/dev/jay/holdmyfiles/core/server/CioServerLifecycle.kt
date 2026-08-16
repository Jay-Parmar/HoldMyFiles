package dev.jay.holdmyfiles.core.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CioServerLifecycle(
    private val scope: CoroutineScope,
    private val bindHost: String,
    private val authoritySource: MutableAuthoritySource,
    private val module: suspend Application.() -> Unit,
    private val invalidateGuests: () -> Unit,
) {
    private val mutex = Mutex()
    private var server: EmbeddedServer<
        CIOApplicationEngine,
        CIOApplicationEngine.Configuration,
        >? = null
    private var terminal = false
    private var guestsInvalidated = false

    @Volatile
    private var currentEndpoint: BoundEndpoint? = null

    val endpoint: BoundEndpoint?
        get() = currentEndpoint

    init {
        require(bindHost.isNotBlank())
        require(bindHost != "0.0.0.0" && bindHost != "::" && bindHost != "[::]")
    }

    suspend fun start(): BoundEndpoint = mutex.withLock {
        currentEndpoint?.let { endpoint -> return endpoint }
        check(!terminal) { "Server lifecycle cannot be restarted" }

        var candidate: EmbeddedServer<
            CIOApplicationEngine,
            CIOApplicationEngine.Configuration,
            >? = null
        try {
            candidate = scope.embeddedServer(
                factory = CIO,
                host = bindHost,
                port = 0,
                watchPaths = emptyList(),
                module = module,
            )
            candidate.startSuspend(wait = false)
            val connectors = candidate.engine.resolvedConnectors()
            check(connectors.size == 1) { "Expected one server connector" }
            val endpoint = BoundEndpoint(bindHost, connectors.single().port)

            server = candidate
            currentEndpoint = endpoint
            authoritySource.publish(endpoint)
            endpoint
        } catch (cause: Throwable) {
            terminal = true
            server = null
            currentEndpoint = null
            authoritySource.clear()
            val cleanupFailure = runCatching {
                withContext(NonCancellable) {
                    try {
                        candidate?.stopSuspend(
                            gracePeriodMillis = 0,
                            timeoutMillis = START_FAILURE_TIMEOUT_MILLIS,
                        )
                    } finally {
                        invalidateGuestsOnce()
                    }
                }
            }.exceptionOrNull()
            cleanupFailure?.let(cause::addSuppressed)
            throw cause
        }
    }

    suspend fun stop(
        gracePeriodMillis: Long = 0,
        timeoutMillis: Long = 2_000,
    ) {
        require(gracePeriodMillis >= 0)
        require(timeoutMillis >= gracePeriodMillis)

        withContext(NonCancellable) {
            mutex.withLock {
                if (terminal) {
                    invalidateGuestsOnce()
                    return@withLock
                }

                terminal = true
                val active = server
                server = null
                currentEndpoint = null
                authoritySource.clear()
                try {
                    active?.stopSuspend(
                        gracePeriodMillis = gracePeriodMillis,
                        timeoutMillis = timeoutMillis,
                    )
                } finally {
                    invalidateGuestsOnce()
                }
            }
        }
    }

    private fun invalidateGuestsOnce() {
        if (guestsInvalidated) {
            return
        }
        guestsInvalidated = true
        invalidateGuests()
    }

    private companion object {
        const val START_FAILURE_TIMEOUT_MILLIS = 2_000L
    }
}
