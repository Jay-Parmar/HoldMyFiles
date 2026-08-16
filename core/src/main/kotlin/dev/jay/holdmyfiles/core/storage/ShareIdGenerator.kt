package dev.jay.holdmyfiles.core.storage

import dev.jay.holdmyfiles.core.security.RandomByteSource
import dev.jay.holdmyfiles.core.security.SecureRandomByteSource
import java.util.Base64

fun interface ShareIdGenerator {
    fun generate(): ShareId
}

class RandomShareIdGenerator(
    private val random: RandomByteSource = SecureRandomByteSource(),
) : ShareIdGenerator {
    override fun generate(): ShareId {
        val bytes = ByteArray(SHARE_ID_BYTES)
        random.nextBytes(bytes)
        return ShareId(ENCODER.encodeToString(bytes))
    }

    private companion object {
        const val SHARE_ID_BYTES = 16
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
