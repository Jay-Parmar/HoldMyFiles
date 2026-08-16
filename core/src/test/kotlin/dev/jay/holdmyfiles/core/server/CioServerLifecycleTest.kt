package dev.jay.holdmyfiles.core.server

import io.ktor.server.application.Application
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CioServerLifecycleTest {
    @Test
    fun `start resolves an ephemeral port and serves health`() = runBlocking {
        val authority = MutableAuthoritySource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lifecycle = lifecycle(scope, authority)

        try {
            val endpoint = withTimeout(5_000) { lifecycle.start() }
            val connection = URI.create("${endpoint.origin}/api/v1/health")
                .toURL()
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000

            assertTrue(endpoint.port in 1..65_535)
            assertEquals(endpoint.authority, authority.current())
            assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
        } finally {
            lifecycle.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
            scope.cancel()
        }
    }

    @Test
    fun `start and stop are idempotent while cleanup runs once`() = runBlocking {
        val authority = MutableAuthoritySource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var invalidations = 0
        val lifecycle = lifecycle(scope, authority) { invalidations += 1 }

        try {
            val first = lifecycle.start()
            val second = lifecycle.start()

            assertSame(first, second)
            lifecycle.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
            lifecycle.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)

            assertNull(lifecycle.endpoint)
            assertNull(authority.current())
            assertEquals(1, invalidations)
            val failure = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(first.host, first.port), 500)
                }
            }.exceptionOrNull()
            assertNotNull(failure)
        } finally {
            lifecycle.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
            scope.cancel()
        }
    }

    @Test
    fun `startup failure clears authority and invalidates the run`() = runBlocking {
        val authority = MutableAuthoritySource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var invalidations = 0
        val lifecycle = CioServerLifecycle(
            scope = scope,
            bindHost = "127.0.0.1",
            authoritySource = authority,
            module = { error("startup failure") },
            invalidateGuests = { invalidations += 1 },
        )

        try {
            val failure = runCatching { lifecycle.start() }.exceptionOrNull()

            assertNotNull(failure)
            assertNull(lifecycle.endpoint)
            assertNull(authority.current())
            assertEquals(1, invalidations)
        } finally {
            lifecycle.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
            scope.cancel()
        }
    }

    private fun lifecycle(
        scope: CoroutineScope,
        authority: MutableAuthoritySource,
        invalidateGuests: () -> Unit = {},
    ): CioServerLifecycle = CioServerLifecycle(
        scope = scope,
        bindHost = "127.0.0.1",
        authoritySource = authority,
        module = module(authority),
        invalidateGuests = invalidateGuests,
    )

    private fun module(
        authority: MutableAuthoritySource,
    ): suspend Application.() -> Unit = {
        holdMyFilesModule(ServerDependencies(authoritySource = authority))
    }
}
