package dev.jay.holdmyfiles.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class RunPinGeneratorTest {
    @Test
    fun `generates a six digit pin with leading zeros`() {
        val random = RandomNumberSource { upperBound ->
            assertEquals(1_000_000, upperBound)
            42
        }

        val pin = RunPinGenerator(random).generate()

        assertEquals("000042", pin)
    }

    @Test
    fun `generates the highest six digit pin`() {
        val pin = RunPinGenerator(RandomNumberSource { 999_999 }).generate()

        assertEquals("999999", pin)
    }
}
