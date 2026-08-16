package dev.jay.holdmyfiles.core.security

class NodeHandle internal constructor(
    private val encoded: String,
) {
    fun encodedValue(): String = encoded

    override fun toString(): String = "NodeHandle(redacted)"
}
