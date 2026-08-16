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
        require(label.isNotEmpty() && label.length <= MAX_LABEL_LENGTH)
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

    override fun toString(): String =
        "ShareSnapshot(version=$version, shareCount=${shares.size})"
}

interface ShareCatalog<R : Any> {
    suspend fun snapshot(): ShareSnapshot<R>
}
