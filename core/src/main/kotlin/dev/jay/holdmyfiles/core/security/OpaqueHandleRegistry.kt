package dev.jay.holdmyfiles.core.security

import java.util.Base64

class OpaqueHandleRegistry<T : Any>(
    private val random: RandomByteSource = SecureRandomByteSource(),
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val lifetimeMillis: Long = 15 * 60 * 1_000L,
    private val capacity: Int = 4_096,
) {
    private val entries = mutableMapOf<String, Entry<T>>()

    init {
        require(lifetimeMillis > 0)
        require(capacity > 0)
    }

    @Synchronized
    fun issue(target: T): NodeHandle? {
        val nowMillis = clock.nowMillis()
        entries.entries.removeAll { (_, entry) -> entry.isExpired(nowMillis, lifetimeMillis) }
        if (entries.size >= capacity) {
            return null
        }

        repeat(MAX_GENERATION_ATTEMPTS) {
            val bytes = ByteArray(HANDLE_BYTES)
            random.nextBytes(bytes)
            val encoded = ENCODER.encodeToString(bytes)
            if (!entries.containsKey(encoded)) {
                entries[encoded] = Entry(target, nowMillis)
                return NodeHandle(encoded)
            }
        }

        return null
    }

    @Synchronized
    fun resolve(encodedHandle: String): T? {
        if (!encodedHandle.isUnpaddedBase64Url(ENCODED_HANDLE_LENGTH)) {
            return null
        }

        val entry = entries[encodedHandle] ?: return null
        if (entry.isExpired(clock.nowMillis(), lifetimeMillis)) {
            entries.remove(encodedHandle)
            return null
        }

        return entry.target
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private companion object {
        const val HANDLE_BYTES = 24
        const val ENCODED_HANDLE_LENGTH = 32
        const val MAX_GENERATION_ATTEMPTS = 8
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }

    private data class Entry<T : Any>(
        val target: T,
        val issuedAtMillis: Long,
    ) {
        fun isExpired(nowMillis: Long, lifetimeMillis: Long): Boolean =
            nowMillis < issuedAtMillis || nowMillis - issuedAtMillis >= lifetimeMillis
    }
}
