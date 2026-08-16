package dev.jay.holdmyfiles.core.server

import dev.jay.holdmyfiles.core.security.LoginResult
import dev.jay.holdmyfiles.core.security.RandomByteSource
import dev.jay.holdmyfiles.core.security.RandomNumberSource
import dev.jay.holdmyfiles.core.security.RunPinGenerator
import dev.jay.holdmyfiles.core.security.SessionAuthenticator
import dev.jay.holdmyfiles.core.security.SessionRegistry
import dev.jay.holdmyfiles.core.storage.BrowseOutcome
import dev.jay.holdmyfiles.core.storage.GuestBrowser
import dev.jay.holdmyfiles.core.storage.GuestListing
import dev.jay.holdmyfiles.core.storage.OpenedFile
import dev.jay.holdmyfiles.core.storage.ReadLease
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRoutesTest {
    @Test
    fun `missing session blocks file metadata before storage access`() = testApplication {
        val browser = RecordingBrowser()
        application { holdMyFilesModule(dependencies(authenticator(), browser)) }

        val response = client.head("/api/v1/files/${"a".repeat(32)}") {
            validHost()
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(browser.openRequests.isEmpty())
    }

    @Test
    fun `authenticated file metadata is safe and closes without reading`() = testApplication {
        val authenticator = authenticator()
        val lease = RecordingReadLease()
        val browser = RecordingBrowser(
            openResult = BrowseOutcome.Ok(
                OpenedFile(
                    displayName = "holiday\r\nphoto.jpg",
                    sizeBytes = 12,
                    content = lease,
                ),
            ),
        )
        val handle = "b".repeat(32)
        application { holdMyFilesModule(dependencies(authenticator, browser)) }

        val response = client.head("/api/v1/files/$handle") {
            authenticated(authenticator)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.OctetStream, response.contentType())
        assertEquals("12", response.headers[HttpHeaders.ContentLength])
        val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
        assertTrue(disposition.startsWith("attachment;"))
        assertFalse(disposition.contains('\r'))
        assertFalse(disposition.contains('\n'))
        assertEquals("", response.bodyAsText())
        assertEquals(listOf(handle), browser.openRequests)
        assertEquals(0, lease.readCount)
        assertEquals(1, lease.closeCount)
    }

    @Test
    fun `unknown file size omits content length`() = testApplication {
        val authenticator = authenticator()
        val browser = RecordingBrowser(
            openResult = BrowseOutcome.Ok(
                OpenedFile("notes.txt", null, RecordingReadLease()),
            ),
        )
        application { holdMyFilesModule(dependencies(authenticator, browser)) }

        val response = client.head("/api/v1/files/${"c".repeat(32)}") {
            authenticated(authenticator)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(null, response.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun `unavailable file handles share one response`() = testApplication {
        val authenticator = authenticator()
        val browser = RecordingBrowser(openResult = BrowseOutcome.StaleHandle)
        application { holdMyFilesModule(dependencies(authenticator, browser)) }

        val response = client.head("/api/v1/files/${"d".repeat(32)}") {
            authenticated(authenticator)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    private fun dependencies(
        authenticator: SessionAuthenticator,
        browser: GuestBrowser,
    ) = ServerDependencies(
        allowedAuthority = "localhost:80",
        authenticator = authenticator,
        browser = browser,
    )

    private fun authenticator(): SessionAuthenticator {
        var seed = 0
        return SessionAuthenticator(
            pin = RunPinGenerator(RandomNumberSource { 42 }).generate(),
            sessions = SessionRegistry(
                random = RandomByteSource { destination -> destination.fill(seed++.toByte()) },
            ),
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.validHost() {
        header(HttpHeaders.Host, "localhost:80")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticated(
        authenticator: SessionAuthenticator,
    ) {
        validHost()
        val result = authenticator.login("test-peer", "000042")
        val token = (result as LoginResult.Authenticated).session.encodedValue()
        header(HttpHeaders.Cookie, "hmf_session=$token")
    }

    private class RecordingBrowser(
        var openResult: BrowseOutcome<OpenedFile> = BrowseOutcome.InvalidHandle,
    ) : GuestBrowser {
        val openRequests = mutableListOf<String>()

        override suspend fun roots(): BrowseOutcome<GuestListing> = error("Not used")

        override suspend fun list(encodedHandle: String): BrowseOutcome<GuestListing> =
            error("Not used")

        override suspend fun open(encodedHandle: String): BrowseOutcome<OpenedFile> {
            openRequests += encodedHandle
            return openResult
        }

        override fun clearHandles() = Unit
    }

    private class RecordingReadLease : ReadLease {
        var readCount = 0
        var closeCount = 0

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
            readCount += 1
            return -1
        }

        override fun close() {
            closeCount += 1
        }
    }
}
