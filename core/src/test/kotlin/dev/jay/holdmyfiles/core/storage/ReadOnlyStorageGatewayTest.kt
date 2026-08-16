package dev.jay.holdmyfiles.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyStorageGatewayTest {
    @Test
    fun `directory listing keeps an immutable node snapshot`() {
        val nodes = mutableListOf(
            StorageNode(TestReference("one"), "One", StorageNodeKind.File, sizeBytes = 1),
        )
        val listing = DirectoryListing(nodes, truncated = true)

        nodes.clear()

        assertEquals(1, listing.nodes.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun `closing an opened file closes its read lease once`() {
        val lease = RecordingReadLease()
        val opened = OpenedFile(
            displayName = "report.pdf",
            sizeBytes = 10,
            content = lease,
        )

        opened.close()
        opened.close()

        assertEquals(1, lease.closeCount)
    }

    private data class TestReference(val value: String)

    private class RecordingReadLease : ReadLease {
        var closeCount = 0

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int = -1

        override fun close() {
            closeCount += 1
        }
    }
}
