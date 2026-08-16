package dev.jay.holdmyfiles.core.security

class SessionToken internal constructor(
    private val encoded: String,
) {
    fun encodedValue(): String = encoded

    override fun toString(): String = "SessionToken(redacted)"
}
