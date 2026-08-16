package dev.jay.holdmyfiles.core.security

import java.security.SecureRandom

fun interface RandomByteSource {
    fun nextBytes(destination: ByteArray)
}

class SecureRandomByteSource(
    private val secureRandom: SecureRandom = SecureRandom(),
) : RandomByteSource {
    override fun nextBytes(destination: ByteArray) {
        secureRandom.nextBytes(destination)
    }
}
