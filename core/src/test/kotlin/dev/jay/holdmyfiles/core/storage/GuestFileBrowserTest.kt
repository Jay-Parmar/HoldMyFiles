package dev.jay.holdmyfiles.core.storage

import dev.jay.holdmyfiles.core.security.RandomByteSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun browser(
        catalog: ShareCatalog<TestRoot>,
        gateway: ReadOnlyStorageGateway<TestRoot, TestReference>,
    ): GuestFileBrowser<TestRoot, TestReference> {
        var seed = 0
        return GuestFileBrowser(
            catalog = catalog,
            gateway = gateway,
            handleRandom = RandomByteSource { destination ->
                destination.fill(seed++.toByte())
            },
        )
    }

    private fun snapshot(vararg shares: SharedFolder<TestRoot>) =
        ShareSnapshot(ShareSetVersion(1), shares.toList())

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
        override suspend fun snapshot(): ShareSnapshot<TestRoot> = current
    }

    private class FakeGateway : ReadOnlyStorageGateway<TestRoot, TestReference> {
        val roots = mutableMapOf<TestRoot, StorageNode<TestReference>>()
        val rootRequests = mutableListOf<TestRoot>()

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
        ): StorageOutcome<DirectoryListing<TestReference>> = error("Not used")

        override suspend fun openFile(
            storageRoot: TestRoot,
            file: StorageNode<TestReference>,
        ): StorageOutcome<OpenedFile> = error("Not used")
    }
}
