package dev.jay.holdmyfiles.core.server

class GuestWebAssets(
    indexHtml: ByteArray,
    styleSheet: ByteArray,
    script: ByteArray,
) {
    internal val indexHtml = indexHtml.copyOf()
    internal val styleSheet = styleSheet.copyOf()
    internal val script = script.copyOf()

    init {
        require(this.indexHtml.size in 1..MAX_INDEX_BYTES)
        require(this.styleSheet.size in 1..MAX_STYLE_BYTES)
        require(this.script.size in 1..MAX_SCRIPT_BYTES)
    }

    override fun toString(): String = "GuestWebAssets(redacted)"

    private companion object {
        const val MAX_INDEX_BYTES = 256 * 1_024
        const val MAX_STYLE_BYTES = 512 * 1_024
        const val MAX_SCRIPT_BYTES = 2 * 1_024 * 1_024
    }
}
