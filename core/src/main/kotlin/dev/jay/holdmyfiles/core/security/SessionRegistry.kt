package dev.jay.holdmyfiles.core.security

import java.util.Base64

class SessionRegistry(
    private val random: RandomByteSource = SecureRandomByteSource(),
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val idleTimeoutMillis: Long = 15 * 60 * 1_000L,
    private val absoluteTimeoutMillis: Long = 8 * 60 * 60 * 1_000L,
    private val capacity: Int = 256,
) {
    private val sessions = mutableMapOf<String, SessionRecord>()

    init {
        require(idleTimeoutMillis > 0)
        require(absoluteTimeoutMillis > 0)
        require(capacity > 0)
    }

    @Synchronized
    fun issue(): SessionToken? {
        val nowMillis = clock.nowMillis()
        sessions.entries.removeAll { (_, session) ->
            session.isExpired(nowMillis, idleTimeoutMillis, absoluteTimeoutMillis)
        }
        if (sessions.size >= capacity) {
            return null
        }

        repeat(MAX_GENERATION_ATTEMPTS) {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            val encoded = ENCODER.encodeToString(bytes)
            if (!sessions.containsKey(encoded)) {
                sessions[encoded] = SessionRecord(
                    issuedAtMillis = nowMillis,
                    lastSeenAtMillis = nowMillis,
                )
                return SessionToken(encoded)
            }
        }

        return null
    }

    @Synchronized
    fun validate(encodedToken: String): Boolean {
        if (!encodedToken.isUnpaddedBase64Url(ENCODED_TOKEN_LENGTH)) {
            return false
        }

        val session = sessions[encodedToken] ?: return false
        val nowMillis = clock.nowMillis()

        if (session.isExpired(nowMillis, idleTimeoutMillis, absoluteTimeoutMillis)) {
            sessions.remove(encodedToken)
            return false
        }

        session.lastSeenAtMillis = nowMillis
        return true
    }

    @Synchronized
    fun revoke(encodedToken: String) {
        if (encodedToken.isUnpaddedBase64Url(ENCODED_TOKEN_LENGTH)) {
            sessions.remove(encodedToken)
        }
    }

    @Synchronized
    fun clear() {
        sessions.clear()
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val ENCODED_TOKEN_LENGTH = 43
        const val MAX_GENERATION_ATTEMPTS = 8
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }

    private data class SessionRecord(
        val issuedAtMillis: Long,
        var lastSeenAtMillis: Long,
    ) {
        fun isExpired(
            nowMillis: Long,
            idleTimeoutMillis: Long,
            absoluteTimeoutMillis: Long,
        ): Boolean {
            if (nowMillis < issuedAtMillis || nowMillis < lastSeenAtMillis) {
                return true
            }

            return nowMillis - issuedAtMillis >= absoluteTimeoutMillis ||
                nowMillis - lastSeenAtMillis >= idleTimeoutMillis
        }
    }
}
