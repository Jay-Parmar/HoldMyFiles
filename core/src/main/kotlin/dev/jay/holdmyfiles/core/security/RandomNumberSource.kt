package dev.jay.holdmyfiles.core.security

import java.security.SecureRandom

fun interface RandomNumberSource {
    fun nextInt(exclusiveUpperBound: Int): Int
}

class SecureRandomNumberSource(
    private val secureRandom: SecureRandom = SecureRandom(),
) : RandomNumberSource {
    override fun nextInt(exclusiveUpperBound: Int): Int =
        secureRandom.nextInt(exclusiveUpperBound)
}
