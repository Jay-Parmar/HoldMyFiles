package dev.jay.holdmyfiles.core.storage

@JvmInline
value class ShareId(
    val value: String,
) {
    init {
        require(value.matches(PATTERN))
    }

    private companion object {
        val PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

data class SharedFolder<R : Any>(
    val id: ShareId,
    val label: String,
    val enabled: Boolean,
    val storageRoot: R,
) {
    init {
        require(label.isNotBlank() && label.length <= MAX_LABEL_LENGTH)
        require(label.none(Char::isISOControl))
    }

    override fun toString(): String = "SharedFolder(id=$id, enabled=$enabled)"

    private companion object {
        const val MAX_LABEL_LENGTH = 80
    }
}

@JvmInline
value class ShareSetVersion(
    val value: Long,
) {
    init {
        require(value >= 0)
    }
}

class ShareSnapshot<R : Any>(
    val version: ShareSetVersion,
    shares: List<SharedFolder<R>>,
) {
    val shares: List<SharedFolder<R>> = shares.toList()

    init {
        require(this.shares.size <= MAX_CONFIGURED_SHARES)
        require(this.shares.map { share -> share.id }.distinct().size == this.shares.size)
    }

    override fun toString(): String =
        "ShareSnapshot(version=$version, shareCount=${shares.size})"
}

internal const val MAX_CONFIGURED_SHARES = 64

interface ShareCatalog<R : Any> {
    suspend fun snapshot(): ShareSnapshot<R>
}
