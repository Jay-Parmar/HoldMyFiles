package dev.jay.holdmyfiles.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPinTest {
    private val pin = RunPinGenerator(RandomNumberSource { 42 }).generate()

    @Test
    fun `accepts the matching six digit pin`() {
        assertTrue(pin.verify("000042"))
    }

    @Test
    fun `rejects incorrect or malformed pins`() {
        val invalidPins = listOf(
            "000043",
            "00042",
            "0000420",
            " 00042",
            "+00042",
            "\u0660\u0660\u0660\u0660\u0664\u0662",
        )

        invalidPins.forEach { candidate ->
            assertFalse(candidate, pin.verify(candidate))
        }
    }

    @Test
    fun `does not reveal the pin when converted to text`() {
        assertFalse(pin.toString().contains("000042"))
    }
}
