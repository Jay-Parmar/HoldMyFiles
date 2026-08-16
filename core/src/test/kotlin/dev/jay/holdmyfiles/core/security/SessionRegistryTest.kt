package dev.jay.holdmyfiles.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryTest {
    @Test
    fun `issued session validates by its encoded token`() {
        val registry = SessionRegistry(sequentialBytes())

        val session = requireSession(registry)

        assertTrue(registry.validate(session.encodedValue()))
        assertFalse(registry.validate("unknown"))
    }

    @Test
    fun `session token is unpadded base64url and redacted in text`() {
        val registry = SessionRegistry(sequentialBytes())

        val session = requireSession(registry)
        val encoded = session.encodedValue()

        assertEquals(43, encoded.length)
        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(session.toString().contains(encoded))
    }

    @Test
    fun `revoking a session invalidates only that session`() {
        var seed = 0
        val registry = SessionRegistry(RandomByteSource { destination ->
            destination.fill(seed++.toByte())
        })
        val first = requireSession(registry)
        val second = requireSession(registry)

        registry.revoke(first.encodedValue())

        assertFalse(registry.validate(first.encodedValue()))
        assertTrue(registry.validate(second.encodedValue()))
    }

    @Test
    fun `clearing sessions invalidates every session`() {
        var seed = 0
        val registry = SessionRegistry(RandomByteSource { destination ->
            destination.fill(seed++.toByte())
        })
        val first = requireSession(registry)
        val second = requireSession(registry)

        registry.clear()

        assertFalse(registry.validate(first.encodedValue()))
        assertFalse(registry.validate(second.encodedValue()))
    }

    @Test
    fun `successful validation refreshes idle expiry`() {
        val clock = MutableClock()
        val registry = SessionRegistry(
            random = sequentialBytes(),
            clock = clock,
            idleTimeoutMillis = 5,
            absoluteTimeoutMillis = 20,
        )
        val session = requireSession(registry)

        clock.nowMillis = 4
        assertTrue(registry.validate(session.encodedValue()))
        clock.nowMillis = 8
        assertTrue(registry.validate(session.encodedValue()))
        clock.nowMillis = 13
        assertFalse(registry.validate(session.encodedValue()))
    }

    @Test
    fun `absolute expiry is not refreshed by validation`() {
        val clock = MutableClock()
        val registry = SessionRegistry(
            random = sequentialBytes(),
            clock = clock,
            idleTimeoutMillis = 5,
            absoluteTimeoutMillis = 10,
        )
        val session = requireSession(registry)

        clock.nowMillis = 4
        assertTrue(registry.validate(session.encodedValue()))
        clock.nowMillis = 8
        assertTrue(registry.validate(session.encodedValue()))
        clock.nowMillis = 10
        assertFalse(registry.validate(session.encodedValue()))
    }

    @Test
    fun `backward clock movement invalidates the session`() {
        val clock = MutableClock(nowMillis = 100)
        val registry = SessionRegistry(
            random = sequentialBytes(),
            clock = clock,
            idleTimeoutMillis = 5,
            absoluteTimeoutMillis = 20,
        )
        val session = requireSession(registry)

        clock.nowMillis = 99
        assertFalse(registry.validate(session.encodedValue()))
        clock.nowMillis = 100
        assertFalse(registry.validate(session.encodedValue()))
    }

    @Test
    fun `refuses new sessions when capacity is full`() {
        var seed = 0
        val registry = SessionRegistry(
            random = RandomByteSource { destination -> destination.fill(seed++.toByte()) },
            capacity = 2,
        )

        assertNotNull(registry.issue())
        assertNotNull(registry.issue())
        assertNull(registry.issue())
    }

    @Test
    fun `removes expired sessions before checking capacity`() {
        var seed = 0
        val clock = MutableClock()
        val registry = SessionRegistry(
            random = RandomByteSource { destination -> destination.fill(seed++.toByte()) },
            clock = clock,
            idleTimeoutMillis = 5,
            absoluteTimeoutMillis = 20,
            capacity = 1,
        )
        val expired = requireSession(registry)

        clock.nowMillis = 5
        val replacement = registry.issue()

        assertNotNull(replacement)
        assertFalse(registry.validate(expired.encodedValue()))
    }

    @Test
    fun `random collisions do not refresh an existing session`() {
        val clock = MutableClock()
        val registry = SessionRegistry(
            random = RandomByteSource { destination -> destination.fill(0) },
            clock = clock,
            idleTimeoutMillis = 5,
            absoluteTimeoutMillis = 20,
            capacity = 2,
        )
        val session = requireSession(registry)

        clock.nowMillis = 4
        assertNull(registry.issue())
        clock.nowMillis = 5
        assertFalse(registry.validate(session.encodedValue()))
    }

    @Test
    fun `rejects malformed session values`() {
        val registry = SessionRegistry(sequentialBytes())

        assertFalse(registry.validate("a".repeat(10_000)))
        assertFalse(registry.validate("!".repeat(43)))
        assertFalse(registry.validate("a".repeat(42)))
    }

    private fun sequentialBytes(): RandomByteSource = RandomByteSource { destination ->
        destination.indices.forEach { index ->
            destination[index] = index.toByte()
        }
    }

    private fun requireSession(registry: SessionRegistry): SessionToken =
        requireNotNull(registry.issue())

    private class MutableClock(
        var nowMillis: Long = 0,
    ) : MonotonicClock {
        override fun nowMillis(): Long = nowMillis
    }
}
