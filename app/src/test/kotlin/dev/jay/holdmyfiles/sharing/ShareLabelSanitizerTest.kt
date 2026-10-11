package dev.jay.holdmyfiles.sharing

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareLabelSanitizerTest {
    @Test
    fun `removes control characters and bounds the stored label`() {
        val unsafe = "  Family\u0000\n  Photos  " + "x".repeat(100)

        val sanitized = ShareLabelSanitizer.sanitize(unsafe)

        assertEquals("Family Photos " + "x".repeat(66), sanitized)
        assertEquals(80, sanitized.length)
    }
}
