package dev.jay.holdmyfiles.sharing

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class StoredShare(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val uri: String,
) {
    override fun toString(): String = "StoredShare(redacted)"
}

internal data class StoredShareState(
    val version: Long,
    val shares: List<StoredShare>,
) {
    override fun toString(): String =
        "StoredShareState(version=$version, shareCount=${shares.size})"
}

internal object ShareStateCodec {
    fun encode(state: StoredShareState): String = buildString {
        append(HEADER)
        append('\n')
        append(state.version)
        append('\n')
        append(state.shares.size)
        state.shares.forEach { share ->
            append('\n')
            append(encodeText(share.id))
            append(SEPARATOR)
            append(encodeText(share.label))
            append(SEPARATOR)
            append(if (share.enabled) ENABLED else DISABLED)
            append(SEPARATOR)
            append(encodeText(share.uri))
        }
    }

    fun decode(encoded: String?): StoredShareState? {
        if (encoded == null || encoded.length > MAX_ENCODED_LENGTH) {
            return null
        }

        return runCatching {
            val lines = encoded.split('\n')
            require(lines.size >= HEADER_LINE_COUNT)
            require(lines[0] == HEADER)
            val version = lines[1].toLong()
            require(version >= 0)
            val shareCount = lines[2].toInt()
            require(shareCount in 0..MAX_SHARE_COUNT)
            require(lines.size == HEADER_LINE_COUNT + shareCount)

            val shares = lines.drop(HEADER_LINE_COUNT).map { line ->
                val fields = line.split(SEPARATOR)
                require(fields.size == FIELD_COUNT)
                StoredShare(
                    id = decodeText(fields[0]),
                    label = decodeText(fields[1]),
                    enabled = when (fields[2]) {
                        ENABLED -> true
                        DISABLED -> false
                        else -> error("Invalid enabled value")
                    },
                    uri = decodeText(fields[3]),
                )
            }
            StoredShareState(version = version, shares = shares)
        }.getOrNull()
    }

    private fun encodeText(value: String): String =
        ENCODER.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String {
        val bytes = DECODER.decode(value)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private const val HEADER = "HMF1"
    private const val HEADER_LINE_COUNT = 3
    private const val FIELD_COUNT = 4
    private const val MAX_SHARE_COUNT = 64
    private const val MAX_ENCODED_LENGTH = 1_048_576
    private const val SEPARATOR = "."
    private const val ENABLED = "1"
    private const val DISABLED = "0"
    private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val DECODER: Base64.Decoder = Base64.getUrlDecoder()
}
