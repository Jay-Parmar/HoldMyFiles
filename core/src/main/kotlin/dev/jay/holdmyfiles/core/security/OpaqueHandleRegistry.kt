package dev.jay.holdmyfiles.core.security

import java.util.Base64

class OpaqueHandleRegistry<T : Any>(
    private val random: RandomByteSource = SecureRandomByteSource(),
) {
    private val targets = mutableMapOf<String, T>()

    @Synchronized
    fun issue(target: T): NodeHandle {
        var encoded: String
        do {
            val bytes = ByteArray(HANDLE_BYTES)
            random.nextBytes(bytes)
            encoded = ENCODER.encodeToString(bytes)
        } while (targets.containsKey(encoded))

        targets[encoded] = target
        return NodeHandle(encoded)
    }

    @Synchronized
    fun resolve(encodedHandle: String): T? = targets[encodedHandle]

    @Synchronized
    fun clear() {
        targets.clear()
    }

    private companion object {
        const val HANDLE_BYTES = 24
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
