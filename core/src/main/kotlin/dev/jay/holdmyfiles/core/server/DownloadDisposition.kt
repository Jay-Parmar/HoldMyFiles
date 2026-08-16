package dev.jay.holdmyfiles.core.server

import java.nio.charset.StandardCharsets
import java.text.Normalizer

fun attachmentContentDisposition(displayName: String): String {
    val safeName = sanitizeFileName(displayName)
    val asciiName = asciiFallback(safeName)
    val encodedName = encodeHeaderValue(safeName)
    return "attachment; filename=\"$asciiName\"; filename*=UTF-8''$encodedName"
}

private fun sanitizeFileName(displayName: String): String {
    val normalized = Normalizer.normalize(displayName, Normalizer.Form.NFC)
    val sanitized = StringBuilder()
    var offset = 0

    while (offset < normalized.length) {
        val codePoint = normalized.codePointAt(offset)
        offset += Character.charCount(codePoint)

        when {
            codePoint == '/'.code || codePoint == '\\'.code || codePoint == '"'.code -> {
                sanitized.append('_')
            }

            isUnsafeControl(codePoint) -> Unit
            else -> sanitized.appendCodePoint(codePoint)
        }
    }

    val trimmed = sanitized.toString().trimEnd { character ->
        character.isWhitespace() || character == '.'
    }
    val usable = trimmed.takeUnless { value ->
        value.isBlank() || value == "." || value == ".."
    } ?: DEFAULT_FILE_NAME

    return truncatePreservingExtension(usable, MAX_UTF8_NAME_BYTES)
}

private fun isUnsafeControl(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.SURROGATE.toInt(),
    -> true

    else -> false
}

private fun asciiFallback(value: String): String {
    val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
    val ascii = StringBuilder()
    var offset = 0

    while (offset < decomposed.length) {
        val codePoint = decomposed.codePointAt(offset)
        offset += Character.charCount(codePoint)
        val type = Character.getType(codePoint)

        when {
            type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() -> Unit

            codePoint in 0x20..0x7E && codePoint != '"'.code && codePoint != '\\'.code -> {
                ascii.appendCodePoint(codePoint)
            }

            else -> ascii.append('_')
        }
    }

    val trimmed = ascii.toString().trimEnd { character ->
        character.isWhitespace() || character == '.'
    }
    val usable = trimmed.takeUnless { candidate ->
        candidate.isBlank() || candidate == "." || candidate == ".."
    } ?: DEFAULT_FILE_NAME

    return truncatePreservingExtension(usable, MAX_ASCII_NAME_BYTES)
}

private fun truncatePreservingExtension(value: String, maxBytes: Int): String {
    if (value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) {
        return value
    }

    val extensionIndex = value.lastIndexOf('.')
    val extension = if (extensionIndex > 0) value.substring(extensionIndex) else ""
    val preservedExtension = extension
        .takeIf { candidate -> candidate.length in 2..13 }
        .orEmpty()
    val extensionBytes = preservedExtension.toByteArray(StandardCharsets.UTF_8).size
    val base = if (preservedExtension.isEmpty()) value else value.substring(0, extensionIndex)
    return truncateUtf8(base, maxBytes - extensionBytes) + preservedExtension
}

private fun truncateUtf8(value: String, maxBytes: Int): String {
    val truncated = StringBuilder()
    var byteCount = 0
    var offset = 0

    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        val text = String(Character.toChars(codePoint))
        val codePointBytes = text.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount + codePointBytes > maxBytes) {
            break
        }

        truncated.append(text)
        byteCount += codePointBytes
        offset += Character.charCount(codePoint)
    }

    return truncated.toString().ifEmpty { DEFAULT_FILE_NAME }
}

private fun encodeHeaderValue(value: String): String = buildString {
    value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
        val unsigned = byte.toInt() and 0xFF
        if (unsigned.isHeaderAttributeCharacter()) {
            append(unsigned.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0F])
        }
    }
}

private fun Int.isHeaderAttributeCharacter(): Boolean =
    this in 'A'.code..'Z'.code ||
        this in 'a'.code..'z'.code ||
        this in '0'.code..'9'.code ||
        toChar() in "!#$&+-.^_`|~"

private const val DEFAULT_FILE_NAME = "download"
private const val MAX_UTF8_NAME_BYTES = 120
private const val MAX_ASCII_NAME_BYTES = 80
private const val HEX_DIGITS = "0123456789ABCDEF"
