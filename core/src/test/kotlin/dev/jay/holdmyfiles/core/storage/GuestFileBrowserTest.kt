package dev.jay.holdmyfiles.core.storage

import dev.jay.holdmyfiles.core.security.RandomByteSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestFileBrowserTest {
    @Test
    fun `roots expose handles only for enabled shares`() = runTest {
        val enabledRoot = TestRoot("content://provider/tree/enabled")
        val disabledRoot = TestRoot("content://provider/tree/disabled")
        val catalog = FakeCatalog(
            snapshot(
                share("enabled", "Shared photos", true, enabledRoot),
                share("disabled", "Private", false, disabledRoot),
            ),
        )
        val gateway = FakeGateway().apply {
            roots[enabledRoot] = StorageNode(
                reference = TestReference("root-document-id"),
                displayName = "Provider name",
                kind = StorageNodeKind.Directory,
            )
        }
        val browser = browser(catalog, gateway)

        val result = browser.roots()

        val listing = (result as BrowseOutcome.Ok).value
        assertEquals(listOf(enabledRoot), gateway.rootRequests)
        assertEquals(1, listing.nodes.size)
        assertEquals("Shared photos", listing.nodes.single().displayName)
        assertEquals(StorageNodeKind.Directory, listing.nodes.single().kind)
        assertEquals(32, listing.nodes.single().handle.encodedValue().length)
        assertFalse(listing.truncated)
    }

    @Test
    fun `root response contains no storage reference`() = runTest {
        val privateRoot = TestRoot("content://provider/tree/private")
        val catalog = FakeCatalog(snapshot(share("share", "Files", true, privateRoot)))
        val gateway = FakeGateway().apply {
            roots[privateRoot] = StorageNode(
                reference = TestReference("secret-document-id"),
                displayName = "Provider name",
                kind = StorageNodeKind.Directory,
            )
        }

        val result = browser(catalog, gateway).roots()

        val node = (result as BrowseOutcome.Ok).value.nodes.single()
        assertFalse(node.toString().contains(privateRoot.value))
        assertFalse(node.toString().contains("secret-document-id"))
        assertTrue(node.handle.encodedValue().matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `unknown handles never reach the catalog or gateway`() = runTest {
        val catalog = FakeCatalog(snapshot())
        val gateway = FakeGateway()
        val browser = browser(catalog, gateway)

        val result = browser.list("content://provider/tree/private/../document")

        assertEquals(BrowseOutcome.InvalidHandle, result)
        assertEquals(0, catalog.snapshotRequests)
        assertTrue(gateway.listRequests.isEmpty())
    }

    @Test
    fun `valid directory handle passes the stored root and node`() = runTest {
        val root = TestRoot("content://provider/tree/files")
        val rootNode = StorageNode(
            TestReference("root-id"),
            "Files",
            StorageNodeKind.Directory,
        )
        val child = StorageNode(
            TestReference("child-id"),
            "report.pdf",
            StorageNodeKind.File,
            sizeBytes = 42,
        )
        val catalog = FakeCatalog(snapshot(share("files", "Files", true, root)))
        val gateway = FakeGateway().apply {
            roots[root] = rootNode
            nextListing = StorageOutcome.Ok(DirectoryListing(listOf(child), truncated = false))
        }
        val browser = browser(catalog, gateway)
        val rootHandle = ((browser.roots() as BrowseOutcome.Ok).value.nodes.single()).handle

        val result = browser.list(rootHandle.encodedValue())

        val listing = (result as BrowseOutcome.Ok).value
        assertEquals(root, gateway.listRequests.single().first)
        assertSame(rootNode, gateway.listRequests.single().second)
        assertEquals("report.pdf", listing.nodes.single().displayName)
        assertEquals(42L, listing.nodes.single().sizeBytes)
        assertFalse(listing.truncated)
    }

    @Test
    fun `catalog version changes invalidate existing handles`() = runTest {
        val root = TestRoot("content://provider/tree/files")
        val catalog = FakeCatalog(snapshot(share("files", "Files", true, root)))
        val gateway = FakeGateway().apply {
            roots[root] = StorageNode(
                TestReference("root-id"),
                "Files",
                StorageNodeKind.Directory,
            )
        }
        val browser = browser(catalog, gateway)
        val handle = (browser.roots() as BrowseOutcome.Ok).value.nodes.single().handle
        catalog.current = snapshotVersion(2, share("files", "Files", true, root))

        val result = browser.list(handle.encodedValue())

        assertEquals(BrowseOutcome.StaleHandle, result)
        assertTrue(gateway.listRequests.isEmpty())
    }

    @Test
    fun `disabled shares invalidate existing handles`() = runTest {
        val root = TestRoot("content://provider/tree/files")
        val catalog = FakeCatalog(snapshot(share("files", "Files", true, root)))
        val gateway = FakeGateway().apply {
            roots[root] = StorageNode(
                TestReference("root-id"),
                "Files",
                StorageNodeKind.Directory,
            )
        }
        val browser = browser(catalog, gateway)
        val handle = (browser.roots() as BrowseOutcome.Ok).value.nodes.single().handle
        catalog.current = snapshot(share("files", "Files", false, root))

        val result = browser.list(handle.encodedValue())

        assertEquals(BrowseOutcome.StaleHandle, result)
        assertTrue(gateway.listRequests.isEmpty())
    }

    @Test
    fun `listing is bounded independently of the gateway`() = runTest {
        val root = TestRoot("content://provider/tree/files")
        val catalog = FakeCatalog(snapshot(share("files", "Files", true, root)))
        val gateway = FakeGateway().apply {
            roots[root] = StorageNode(
                TestReference("root-id"),
                "Files",
                StorageNodeKind.Directory,
            )
            nextListing = StorageOutcome.Ok(
                DirectoryListing(
                    nodes = listOf(
                        StorageNode(TestReference("one"), "One", StorageNodeKind.File),
                        StorageNode(TestReference("two"), "Two", StorageNodeKind.File),
                    ),
                    truncated = false,
                ),
            )
        }
        val browser = browser(catalog, gateway, maxListingEntries = 1)
        val handle = (browser.roots() as BrowseOutcome.Ok).value.nodes.single().handle

        val result = browser.list(handle.encodedValue())

        val listing = (result as BrowseOutcome.Ok).value
        assertEquals(listOf("One"), listing.nodes.map { node -> node.displayName })
        assertTrue(listing.truncated)
    }

    private fun browser(
        catalog: ShareCatalog<TestRoot>,
        gateway: ReadOnlyStorageGateway<TestRoot, TestReference>,
        maxListingEntries: Int = 1_000,
    ): GuestFileBrowser<TestRoot, TestReference> {
        var seed = 0
        return GuestFileBrowser(
            catalog = catalog,
            gateway = gateway,
            handleRandom = RandomByteSource { destination ->
                destination.fill(seed++.toByte())
            },
            maxListingEntries = maxListingEntries,
        )
    }

    private fun snapshot(vararg shares: SharedFolder<TestRoot>) =
        ShareSnapshot(ShareSetVersion(1), shares.toList())

    private fun snapshotVersion(version: Long, vararg shares: SharedFolder<TestRoot>) =
        ShareSnapshot(ShareSetVersion(version), shares.toList())

    private fun share(
        id: String,
        label: String,
        enabled: Boolean,
        root: TestRoot,
    ) = SharedFolder(ShareId(id), label, enabled, root)

    private data class TestRoot(val value: String) {
        override fun toString(): String = "TestRoot(redacted)"
    }

    private data class TestReference(val value: String) {
        override fun toString(): String = "TestReference(redacted)"
    }

    private class FakeCatalog(
        var current: ShareSnapshot<TestRoot>,
    ) : ShareCatalog<TestRoot> {
        var snapshotRequests = 0

        override suspend fun snapshot(): ShareSnapshot<TestRoot> {
            snapshotRequests += 1
            return current
        }
    }

    private class FakeGateway : ReadOnlyStorageGateway<TestRoot, TestReference> {
        val roots = mutableMapOf<TestRoot, StorageNode<TestReference>>()
        val rootRequests = mutableListOf<TestRoot>()
        val listRequests = mutableListOf<Pair<TestRoot, StorageNode<TestReference>>>()
        var nextListing: StorageOutcome<DirectoryListing<TestReference>> = StorageOutcome.Missing

        override suspend fun root(
            storageRoot: TestRoot,
        ): StorageOutcome<StorageNode<TestReference>> {
            rootRequests += storageRoot
            return roots[storageRoot]?.let { node -> StorageOutcome.Ok(node) }
                ?: StorageOutcome.Missing
        }

        override suspend fun listChildren(
            storageRoot: TestRoot,
            directory: StorageNode<TestReference>,
        ): StorageOutcome<DirectoryListing<TestReference>> {
            listRequests += storageRoot to directory
            return nextListing
        }

        override suspend fun openFile(
            storageRoot: TestRoot,
            file: StorageNode<TestReference>,
        ): StorageOutcome<OpenedFile> = error("Not used")
    }
}
