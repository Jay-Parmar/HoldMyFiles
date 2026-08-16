package dev.jay.holdmyfiles.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpaqueHandleRegistryTest {
    @Test
    fun `handle resolves to its original in memory target`() {
        val registry = OpaqueHandleRegistry<Target>(sequentialBytes())
        val target = Target("share-1", "private/report.pdf")

        val handle = requireHandle(registry, target)

        assertSame(target, registry.resolve(handle.encodedValue()))
        assertNull(registry.resolve("unknown"))
    }

    @Test
    fun `encoded handle contains no target metadata`() {
        val registry = OpaqueHandleRegistry<Target>(sequentialBytes())
        val target = Target("share-1", "private/report.pdf")

        val handle = requireHandle(registry, target)
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
        val handle = requireHandle(registry, Target("share-1", "document-1"))

        registry.clear()

        assertNull(registry.resolve(handle.encodedValue()))
    }

    @Test
    fun `handle expires at its fixed lifetime`() {
        val clock = MutableClock()
        val registry = OpaqueHandleRegistry<Target>(
            random = sequentialBytes(),
            clock = clock,
            lifetimeMillis = 5,
        )
        val target = Target("share-1", "document-1")
        val handle = requireHandle(registry, target)

        clock.nowMillis = 4
        assertSame(target, registry.resolve(handle.encodedValue()))
        clock.nowMillis = 5
        assertNull(registry.resolve(handle.encodedValue()))
    }

    @Test
    fun `backward clock movement invalidates a handle`() {
        val clock = MutableClock(nowMillis = 100)
        val registry = OpaqueHandleRegistry<Target>(
            random = sequentialBytes(),
            clock = clock,
            lifetimeMillis = 5,
        )
        val handle = requireHandle(registry, Target("share-1", "document-1"))

        clock.nowMillis = 99
        assertNull(registry.resolve(handle.encodedValue()))
    }

    @Test
    fun `expired handles free capacity`() {
        var seed = 0
        val clock = MutableClock()
        val registry = OpaqueHandleRegistry<Target>(
            random = RandomByteSource { destination -> destination.fill(seed++.toByte()) },
            clock = clock,
            lifetimeMillis = 5,
            capacity = 1,
        )
        assertNotNull(registry.issue(Target("share-1", "document-1")))
        assertNull(registry.issue(Target("share-1", "document-2")))

        clock.nowMillis = 5

        assertNotNull(registry.issue(Target("share-1", "document-2")))
    }

    @Test
    fun `random collisions never remap an existing handle`() {
        val registry = OpaqueHandleRegistry<Target>(
            random = RandomByteSource { destination -> destination.fill(0) },
            capacity = 2,
        )
        val original = Target("share-1", "document-1")
        val handle = requireHandle(registry, original)

        assertNull(registry.issue(Target("share-1", "document-2")))
        assertSame(original, registry.resolve(handle.encodedValue()))
    }

    private fun sequentialBytes(): RandomByteSource = RandomByteSource { destination ->
        destination.indices.forEach { index ->
            destination[index] = index.toByte()
        }
    }

    private fun requireHandle(
        registry: OpaqueHandleRegistry<Target>,
        target: Target,
    ): NodeHandle = requireNotNull(registry.issue(target))

    private class MutableClock(
        var nowMillis: Long = 0,
    ) : MonotonicClock {
        override fun nowMillis(): Long = nowMillis
    }

    private data class Target(
        val shareId: String,
        val documentId: String,
    )
}
