package dev.jay.holdmyfiles.core.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class RunPin private constructor(
    private val digits: ByteArray,
) {
    fun displayValue(): String = String(digits, StandardCharsets.US_ASCII)

    fun verify(candidate: String): Boolean {
        val candidateBytes = ByteArray(LENGTH)
        var hasValidFormat = candidate.length == LENGTH

        repeat(LENGTH) { index ->
            val character = candidate.getOrNull(index)
            hasValidFormat = hasValidFormat && character != null && character in '0'..'9'
            candidateBytes[index] = character?.code?.toByte() ?: 0
        }

        return MessageDigest.isEqual(digits, candidateBytes) && hasValidFormat
    }

    override fun toString(): String = "RunPin(redacted)"

    internal companion object {
        private const val LENGTH = 6

        fun fromDigits(value: String): RunPin {
            require(value.length == LENGTH && value.all { it in '0'..'9' })
            return RunPin(value.toByteArray(StandardCharsets.US_ASCII))
        }
    }
}
