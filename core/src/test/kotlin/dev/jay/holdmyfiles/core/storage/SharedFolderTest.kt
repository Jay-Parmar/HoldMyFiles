package dev.jay.holdmyfiles.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFolderTest {
    @Test
    fun `keeps a stable id label and enabled state`() {
        val root = TestRoot("content://provider/tree/photos")
        val share = SharedFolder(
            id = ShareId("photos_2026"),
            label = "Family photos",
            enabled = true,
            storageRoot = root,
        )

        assertEquals("photos_2026", share.id.value)
        assertEquals("Family photos", share.label)
        assertTrue(share.enabled)
        assertSame(root, share.storageRoot)
        assertFalse(share.toString().contains(root.value))
        assertFalse(share.toString().contains(share.label))
    }

    @Test
    fun `accepts a disabled share`() {
        val share = SharedFolder(
            ShareId("documents"),
            "Documents",
            enabled = false,
            storageRoot = TestRoot("content://provider/tree/documents"),
        )

        assertFalse(share.enabled)
    }

    @Test
    fun `rejects invalid ids and labels`() {
        listOf("", "has space", "../folder", "a".repeat(65)).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                ShareId(value)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SharedFolder(ShareId("valid"), "", enabled = true, storageRoot = TestRoot("root"))
        }
        listOf("   ", "line\nbreak", "tab\tname").forEach { label ->
            assertThrows(label, IllegalArgumentException::class.java) {
                SharedFolder(
                    ShareId("valid"),
                    label,
                    enabled = true,
                    storageRoot = TestRoot("root"),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SharedFolder(
                ShareId("valid"),
                "a".repeat(81),
                enabled = true,
                storageRoot = TestRoot("root"),
            )
        }
    }

    @Test
    fun `snapshot keeps an immutable copy of its shares`() {
        val shares = mutableListOf(
            SharedFolder(ShareId("one"), "One", true, TestRoot("root-one")),
        )
        val snapshot = ShareSnapshot(ShareSetVersion(3), shares)

        shares.clear()

        assertEquals(3L, snapshot.version.value)
        assertEquals(1, snapshot.shares.size)
    }

    @Test
    fun `snapshot rejects duplicate share ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareSnapshot(
                ShareSetVersion(1),
                listOf(
                    SharedFolder(ShareId("same"), "One", true, TestRoot("root-one")),
                    SharedFolder(ShareId("same"), "Two", true, TestRoot("root-two")),
                ),
            )
        }
    }

    @Test
    fun `snapshot rejects duplicate storage roots`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareSnapshot(
                ShareSetVersion(1),
                listOf(
                    SharedFolder(ShareId("one"), "One", true, TestRoot("same-root")),
                    SharedFolder(ShareId("two"), "Two", false, TestRoot("same-root")),
                ),
            )
        }
    }

    @Test
    fun `snapshot bounds configured shares`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareSnapshot(
                ShareSetVersion(1),
                List(65) { index ->
                    SharedFolder(
                        ShareId("share-$index"),
                        "Share $index",
                        true,
                        TestRoot("root-$index"),
                    )
                },
            )
        }
    }

    @Test
    fun `rejects a negative share set version`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareSetVersion(-1)
        }
    }

    private data class TestRoot(val value: String)
}
