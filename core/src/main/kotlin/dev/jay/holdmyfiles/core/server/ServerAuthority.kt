package dev.jay.holdmyfiles.core.server

import java.util.concurrent.atomic.AtomicReference

class BoundEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank() && host.length <= MAX_HOST_LENGTH)
        require(host.none { character ->
            character.isWhitespace() ||
                character.isISOControl() ||
                character in FORBIDDEN_HOST_CHARACTERS
        })
        require(port in 1..65_535)
    }

    val authority: String = if (':' in host) "[$host]:$port" else "$host:$port"

    val origin: String = "http://$authority"

    override fun toString(): String = "BoundEndpoint(authority=$authority)"

    private companion object {
        const val MAX_HOST_LENGTH = 253
        const val FORBIDDEN_HOST_CHARACTERS = "[]/\\?#@"
    }
}

fun interface AuthoritySource {
    fun current(): String?
}

class MutableAuthoritySource : AuthoritySource {
    private val authority = AtomicReference<String?>()

    override fun current(): String? = authority.get()

    fun publish(endpoint: BoundEndpoint) {
        authority.set(endpoint.authority)
    }

    fun clear() {
        authority.set(null)
    }

    override fun toString(): String = "MutableAuthoritySource(redacted)"
}

internal class StaticAuthoritySource(
    private val authority: String,
) : AuthoritySource {
    init {
        require(authority.isSafeAuthority())
    }

    override fun current(): String = authority
}

internal fun String.isSafeAuthority(): Boolean =
    isNotBlank() &&
        length <= 255 &&
        none { character ->
            character.isWhitespace() ||
                character.isISOControl() ||
                character == '/' ||
                character == '\\'
        }
