package dev.jay.holdmyfiles.core.storage

import dev.jay.holdmyfiles.core.security.RandomByteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIdGeneratorTest {
    @Test
    fun `generates url safe ids without labels or roots`() {
        var seed = 0
        val generator = RandomShareIdGenerator(
            RandomByteSource { destination -> destination.fill(seed++.toByte()) },
        )

        val first = generator.generate()
        val second = generator.generate()

        assertEquals(22, first.value.length)
        assertTrue(first.value.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(first == second)
        assertFalse(first.value.contains("content"))
    }
}
