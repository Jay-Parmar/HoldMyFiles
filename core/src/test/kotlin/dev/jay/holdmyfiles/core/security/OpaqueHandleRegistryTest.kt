package dev.jay.holdmyfiles.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpaqueHandleRegistryTest {
    @Test
    fun `handle resolves to its original in memory target`() {
        val registry = OpaqueHandleRegistry<Target>(sequentialBytes())
        val target = Target("share-1", "private/report.pdf")

        val handle = registry.issue(target)

        assertSame(target, registry.resolve(handle.encodedValue()))
        assertNull(registry.resolve("unknown"))
    }

    @Test
    fun `encoded handle contains no target metadata`() {
        val registry = OpaqueHandleRegistry<Target>(sequentialBytes())
        val target = Target("share-1", "private/report.pdf")

        val handle = registry.issue(target)
        val encoded = handle.encodedValue()

        assertEquals(32, encoded.length)
        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(encoded.contains(target.shareId))
        assertFalse(encoded.contains(target.documentId))
        assertFalse(handle.toString().contains(encoded))
    }

    @Test
    fun `clearing handles invalidates every handle`() {
        val registry = OpaqueHandleRegistry<Target>(sequentialBytes())
        val handle = registry.issue(Target("share-1", "document-1"))

        registry.clear()

        assertNull(registry.resolve(handle.encodedValue()))
    }

    private fun sequentialBytes(): RandomByteSource = RandomByteSource { destination ->
        destination.indices.forEach { index ->
            destination[index] = index.toByte()
        }
    }

    private data class Target(
        val shareId: String,
        val documentId: String,
    )
}
