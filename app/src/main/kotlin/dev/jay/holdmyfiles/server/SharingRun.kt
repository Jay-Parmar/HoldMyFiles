package dev.jay.holdmyfiles.server

import dev.jay.holdmyfiles.core.security.RunPin
import dev.jay.holdmyfiles.core.security.RunPinGenerator
import dev.jay.holdmyfiles.core.security.SessionAuthenticator
import dev.jay.holdmyfiles.core.server.BoundEndpoint
import dev.jay.holdmyfiles.core.server.CioServerLifecycle
import dev.jay.holdmyfiles.core.server.GuestWebAssets
import dev.jay.holdmyfiles.core.server.MutableAuthoritySource
import dev.jay.holdmyfiles.core.server.ServerDependencies
import dev.jay.holdmyfiles.core.server.holdMyFilesModule
import dev.jay.holdmyfiles.core.storage.BoundedReadOnlyStorageGateway
import dev.jay.holdmyfiles.core.storage.GuestFileBrowser
import dev.jay.holdmyfiles.core.storage.ReadOnlyStorageGateway
import dev.jay.holdmyfiles.core.storage.ShareCatalog
import dev.jay.holdmyfiles.core.storage.ShareSnapshot
import dev.jay.holdmyfiles.core.storage.ShareSetVersion
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface SharingRun {
    val pin: RunPin

    suspend fun start(): BoundEndpoint

    suspend fun stop()
}

fun interface SharingRunFactory {
    fun create(bindAddress: String): SharingRun
}

class DefaultSharingRunFactory<R : Any, N : Any>(
    private val catalog: ShareCatalog<R>,
    private val gateway: ReadOnlyStorageGateway<R, N>,
    private val guestAssets: () -> GuestWebAssets,
    private val shareSnapshots: Flow<ShareSnapshot<R>>? = null,
    private val pinGenerator: RunPinGenerator = RunPinGenerator(),
    private val maxConcurrentReads: Int = DEFAULT_MAX_CONCURRENT_READS,
) : SharingRunFactory {
    init {
        require(maxConcurrentReads > 0)
    }

    override fun create(bindAddress: String): SharingRun {
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val pin = pinGenerator.generate()
            val authenticator = SessionAuthenticator(pin)
            val browser = GuestFileBrowser(
                catalog = catalog,
                gateway = BoundedReadOnlyStorageGateway(
                    delegate = gateway,
                    maxConcurrentReads = maxConcurrentReads,
                ),
            )
            val authority = MutableAuthoritySource()
            val invalidateGuests = {
                authenticator.clear()
                browser.clearHandles()
            }
            val dependencies = ServerDependencies(
                authoritySource = authority,
                authenticator = authenticator,
                browser = browser,
                guestAssets = guestAssets(),
            )
            val lifecycle = CioServerLifecycle(
                scope = runScope,
                bindHost = bindAddress,
                authoritySource = authority,
                module = { holdMyFilesModule(dependencies) },
                invalidateGuests = invalidateGuests,
            )
            DefaultSharingRun(
                pin = pin,
                lifecycle = lifecycle,
                scope = runScope,
                catalog = catalog,
                shareSnapshots = shareSnapshots,
                invalidateGuests = invalidateGuests,
            )
        } catch (cause: Throwable) {
            runScope.cancel()
            throw cause
        }
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_READS = 4
    }
}

private class DefaultSharingRun<R : Any>(
    override val pin: RunPin,
    private val lifecycle: CioServerLifecycle,
    private val scope: CoroutineScope,
    private val catalog: ShareCatalog<R>,
    private val shareSnapshots: Flow<ShareSnapshot<R>>?,
    private val invalidateGuests: () -> Unit,
) : SharingRun {
    private val mutex = Mutex()
    private var shareWatcher: Job? = null
    private var terminal = false

    override suspend fun start(): BoundEndpoint = mutex.withLock {
        check(!terminal) { "Sharing run cannot be restarted" }
        lifecycle.endpoint?.let { endpoint -> return endpoint }

        try {
            startShareWatcher()
            lifecycle.start()
        } catch (cause: Throwable) {
            terminal = true
            val cleanupFailure = runCatching {
                lifecycle.stop(
                    gracePeriodMillis = 0,
                    timeoutMillis = STOP_TIMEOUT_MILLIS,
                )
            }.exceptionOrNull()
            try {
                cleanupAfterLifecycle()
            } catch (cleanupCause: Throwable) {
                cause.addSuppressed(cleanupCause)
            }
            cleanupFailure?.let(cause::addSuppressed)
            throw cause
        }
    }

    override suspend fun stop() {
        withContext(NonCancellable) {
            mutex.withLock {
                if (terminal) {
                    cleanupAfterLifecycle()
                    return@withLock
                }

                terminal = true
                try {
                    lifecycle.stop(
                        gracePeriodMillis = 0,
                        timeoutMillis = STOP_TIMEOUT_MILLIS,
                    )
                } finally {
                    cleanupAfterLifecycle()
                }
            }
        }
    }

    private suspend fun startShareWatcher() {
        val snapshots = shareSnapshots ?: return
        if (shareWatcher != null) {
            return
        }

        val observedVersion = AtomicReference<ShareSetVersion>(catalog.snapshot().version)
        shareWatcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            snapshots.collect { snapshot ->
                val previous = observedVersion.getAndSet(snapshot.version)
                if (previous != snapshot.version) {
                    invalidateGuests()
                }
            }
        }
    }

    private suspend fun cleanupAfterLifecycle() {
        shareWatcher?.cancelAndJoin()
        shareWatcher = null
        scope.cancel()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}
