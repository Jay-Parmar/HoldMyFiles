package dev.jay.holdmyfiles.server

import dev.jay.holdmyfiles.core.server.GuestWebAssets
import dev.jay.holdmyfiles.core.server.BoundEndpoint
import dev.jay.holdmyfiles.core.storage.DirectoryListing
import dev.jay.holdmyfiles.core.storage.OpenedFile
import dev.jay.holdmyfiles.core.storage.ReadOnlyStorageGateway
import dev.jay.holdmyfiles.core.storage.ShareCatalog
import dev.jay.holdmyfiles.core.storage.ShareSetVersion
import dev.jay.holdmyfiles.core.storage.ShareSnapshot
import dev.jay.holdmyfiles.core.storage.StorageNode
import dev.jay.holdmyfiles.core.storage.StorageOutcome
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DefaultSharingRunFactoryTest {
    @Test
    fun `share configuration changes invalidate an authenticated guest`() {
        runBlocking {
            val snapshots = MutableStateFlow(snapshot(version = 0))
            val catalog = object : ShareCatalog<String> {
                override suspend fun snapshot(): ShareSnapshot<String> = snapshots.value
            }
            val factory = DefaultSharingRunFactory(
                catalog = catalog,
                gateway = UnusedGateway,
                guestAssets = {
                    GuestWebAssets(
                        indexHtml = "<html></html>".toByteArray(),
                        styleSheet = "body{}".toByteArray(),
                        script = "void 0".toByteArray(),
                    )
                },
                shareSnapshots = snapshots,
            )
            val run = factory.create("127.0.0.1")

            try {
                val endpoint = withTimeout(5_000) { run.start() }
                val cookie = login(endpoint, run.pin.displayValue())
                assertNotNull(cookie)
                assertEquals(
                    HttpURLConnection.HTTP_OK,
                    sharesResponse(endpoint, requireNotNull(cookie)),
                )

                snapshots.value = snapshot(version = 1)

                withTimeout(2_000) {
                    while (
                        sharesResponse(endpoint, requireNotNull(cookie)) !=
                        HttpURLConnection.HTTP_UNAUTHORIZED
                    ) {
                        delay(10)
                    }
                }
            } finally {
                run.stop()
            }
        }
    }

    private fun login(endpoint: BoundEndpoint, pin: String): String? {
        val body = "{\"pin\":\"$pin\"}"
        val response = request(
            endpoint = endpoint,
            method = "POST",
            path = "/api/v1/session",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Origin" to endpoint.origin,
            ),
            body = body,
        )
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, response.status)
        return response.headers["set-cookie"]?.substringBefore(';')
    }

    private fun sharesResponse(endpoint: BoundEndpoint, cookie: String): Int = request(
        endpoint = endpoint,
        method = "GET",
        path = "/api/v1/shares",
        headers = mapOf("Cookie" to cookie),
    ).status

    private fun request(
        endpoint: BoundEndpoint,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String = "",
    ): HttpResponse = Socket().use { socket ->
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 2_000)
        socket.soTimeout = 2_000
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val request = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: ${endpoint.authority}\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        socket.getOutputStream().apply {
            write(request)
            write(bodyBytes)
            flush()
        }

        val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val status = reader.readLine().split(' ')[1].toInt()
        val responseHeaders = buildMap {
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) {
                    break
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    put(
                        line.substring(0, separator).lowercase(),
                        line.substring(separator + 1).trim(),
                    )
                }
            }
        }
        HttpResponse(status, responseHeaders)
    }

    private data class HttpResponse(
        val status: Int,
        val headers: Map<String, String>,
    )

    private fun snapshot(version: Long): ShareSnapshot<String> = ShareSnapshot(
        version = ShareSetVersion(version),
        shares = emptyList(),
    )

    private object UnusedGateway : ReadOnlyStorageGateway<String, String> {
        override suspend fun root(storageRoot: String): StorageOutcome<StorageNode<String>> =
            error("Storage should not be called")

        override suspend fun listChildren(
            storageRoot: String,
            directory: StorageNode<String>,
        ): StorageOutcome<DirectoryListing<String>> = error("Storage should not be called")

        override suspend fun openFile(
            storageRoot: String,
            file: StorageNode<String>,
        ): StorageOutcome<OpenedFile> = error("Storage should not be called")
    }
}
