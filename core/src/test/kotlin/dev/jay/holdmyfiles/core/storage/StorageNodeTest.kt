package dev.jay.holdmyfiles.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageNodeTest {
    @Test
    fun `keeps untrusted names as data`() {
        val node = StorageNode(
            reference = TestReference,
            displayName = "<script>alert(1)</script>\r\nname",
            kind = StorageNodeKind.File,
            sizeBytes = 12,
            modifiedAtEpochMillis = 100,
        )

        assertEquals("<script>alert(1)</script>\r\nname", node.displayName)
        assertEquals(StorageNodeKind.File, node.kind)
        assertEquals(12L, node.sizeBytes)
    }

    @Test
    fun `allows unknown metadata`() {
        val node = StorageNode(
            reference = TestReference,
            displayName = "Folder",
            kind = StorageNodeKind.Directory,
        )

        assertNull(node.sizeBytes)
        assertNull(node.modifiedAtEpochMillis)
    }

    @Test
    fun `rejects invalid bounded metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            StorageNode(TestReference, "", StorageNodeKind.File)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageNode(TestReference, "a".repeat(513), StorageNodeKind.File)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageNode(TestReference, "file", StorageNodeKind.File, sizeBytes = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageNode(
                TestReference,
                "file",
                StorageNodeKind.File,
                modifiedAtEpochMillis = -1,
            )
        }
    }

    private data object TestReference : StorageNodeReference
}
