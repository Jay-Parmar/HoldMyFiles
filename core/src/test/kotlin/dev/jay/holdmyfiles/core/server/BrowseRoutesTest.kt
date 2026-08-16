package dev.jay.holdmyfiles.core.server

import dev.jay.holdmyfiles.core.security.LoginResult
import dev.jay.holdmyfiles.core.security.NodeHandle
import dev.jay.holdmyfiles.core.security.RandomByteSource
import dev.jay.holdmyfiles.core.security.RandomNumberSource
import dev.jay.holdmyfiles.core.security.RunPinGenerator
import dev.jay.holdmyfiles.core.security.SessionAuthenticator
import dev.jay.holdmyfiles.core.security.SessionRegistry
import dev.jay.holdmyfiles.core.storage.BrowseOutcome
import dev.jay.holdmyfiles.core.storage.GuestBrowser
import dev.jay.holdmyfiles.core.storage.GuestListing
import dev.jay.holdmyfiles.core.storage.GuestNode
import dev.jay.holdmyfiles.core.storage.OpenedFile
import dev.jay.holdmyfiles.core.storage.StorageNodeKind
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseRoutesTest {
    @Test
    fun `missing session blocks root discovery`() = testApplication {
        val browser = RecordingBrowser()
        application { holdMyFilesModule(dependencies(authenticator(), browser)) }

        val response = client.get("/api/v1/shares") {
            validHost()
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, browser.rootRequests)
    }

    @Test
    fun `authenticated root discovery returns safe guest fields`() = testApplication {
        val authenticator = authenticator()
        val handle = "a".repeat(32)
        val browser = RecordingBrowser(
            rootsResult = BrowseOutcome.Ok(
                GuestListing(
                    listOf(
                        GuestNode(
                            handle = NodeHandle(handle),
                            displayName = "Shared photos",
                            kind = StorageNodeKind.Directory,
                            sizeBytes = null,
                        ),
                    ),
                    truncated = false,
                ),
            ),
        )
        application { holdMyFilesModule(dependencies(authenticator, browser)) }

        val response = client.get("/api/v1/shares") {
            authenticated(authenticator)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"handle\":\"$handle\""))
        assertTrue(body.contains("\"name\":\"Shared photos\""))
        assertTrue(body.contains("\"kind\":\"directory\""))
        assertFalse(body.contains("shareId"))
        assertFalse(body.contains("content://"))
        assertEquals(1, browser.rootRequests)
    }

    @Test
    fun `authenticated directory route passes only the opaque handle`() = testApplication {
        val authenticator = authenticator()
        val browser = RecordingBrowser()
        val handle = "b".repeat(32)
        application { holdMyFilesModule(dependencies(authenticator, browser)) }

        val response = client.get("/api/v1/nodes/$handle") {
            authenticated(authenticator)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(handle), browser.listRequests)
    }

    @Test
    fun `invalid handles use one unavailable response`() = testApplication {
        val authenticator = authenticator()
        val browser = RecordingBrowser(
            listResult = BrowseOutcome.InvalidHandle,
        )
        val handle = "c".repeat(32)
        application { holdMyFilesModule(dependencies(authenticator, browser)) }

        val response = client.get("/api/v1/nodes/$handle") {
            authenticated(authenticator)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("item_unavailable"))
        assertFalse(response.bodyAsText().contains(handle))
    }

    @Test
    fun `malformed session blocks directory access before the browser`() = testApplication {
        val browser = RecordingBrowser()
        application { holdMyFilesModule(dependencies(authenticator(), browser)) }

        val response = client.get("/api/v1/nodes/${"d".repeat(32)}") {
            validHost()
            header(HttpHeaders.Cookie, "hmf_session=malformed")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(browser.listRequests.isEmpty())
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
        var rootsResult: BrowseOutcome<GuestListing> = BrowseOutcome.Ok(
            GuestListing(emptyList(), truncated = false),
        ),
        var listResult: BrowseOutcome<GuestListing> = BrowseOutcome.Ok(
            GuestListing(emptyList(), truncated = false),
        ),
    ) : GuestBrowser {
        var rootRequests = 0
        val listRequests = mutableListOf<String>()

        override suspend fun roots(): BrowseOutcome<GuestListing> {
            rootRequests += 1
            return rootsResult
        }

        override suspend fun list(encodedHandle: String): BrowseOutcome<GuestListing> {
            listRequests += encodedHandle
            return listResult
        }

        override suspend fun open(encodedHandle: String): BrowseOutcome<OpenedFile> =
            error("Not used")

        override fun clearHandles() = Unit
    }
}
