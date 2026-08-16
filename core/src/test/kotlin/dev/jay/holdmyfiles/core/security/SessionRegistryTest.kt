package dev.jay.holdmyfiles.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryTest {
    @Test
    fun `issued session validates by its encoded token`() {
        val registry = SessionRegistry(sequentialBytes())

        val session = registry.issue()

        assertTrue(registry.validate(session.encodedValue()))
        assertFalse(registry.validate("unknown"))
    }

    @Test
    fun `session token is unpadded base64url and redacted in text`() {
        val registry = SessionRegistry(sequentialBytes())

        val session = registry.issue()
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
        val first = registry.issue()
        val second = registry.issue()

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
        val first = registry.issue()
        val second = registry.issue()

        registry.clear()

        assertFalse(registry.validate(first.encodedValue()))
        assertFalse(registry.validate(second.encodedValue()))
    }

    private fun sequentialBytes(): RandomByteSource = RandomByteSource { destination ->
        destination.indices.forEach { index ->
            destination[index] = index.toByte()
        }
    }
}
