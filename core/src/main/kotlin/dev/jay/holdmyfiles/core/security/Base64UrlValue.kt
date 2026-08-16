package dev.jay.holdmyfiles.core.security

internal fun String.isUnpaddedBase64Url(expectedLength: Int): Boolean =
    length == expectedLength && all { character ->
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '_' ||
            character == '-'
    }
