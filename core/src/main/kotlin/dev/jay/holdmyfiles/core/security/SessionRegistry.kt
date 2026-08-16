package dev.jay.holdmyfiles.core.security

import java.util.Base64

class SessionRegistry(
    private val random: RandomByteSource = SecureRandomByteSource(),
) {
    private val sessions = mutableSetOf<String>()

    @Synchronized
    fun issue(): SessionToken {
        var encoded: String
        do {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            encoded = ENCODER.encodeToString(bytes)
        } while (!sessions.add(encoded))

        return SessionToken(encoded)
    }

    @Synchronized
    fun validate(encodedToken: String): Boolean = sessions.contains(encodedToken)

    @Synchronized
    fun revoke(encodedToken: String) {
        sessions.remove(encodedToken)
    }

    @Synchronized
    fun clear() {
        sessions.clear()
    }

    private companion object {
        const val TOKEN_BYTES = 32
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
