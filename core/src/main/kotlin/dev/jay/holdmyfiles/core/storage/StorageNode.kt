package dev.jay.holdmyfiles.core.storage

enum class StorageNodeKind {
    Directory,
    File,
}

class StorageNode<N : Any>(
    val reference: N,
    val displayName: String,
    val kind: StorageNodeKind,
    val sizeBytes: Long? = null,
    val modifiedAtEpochMillis: Long? = null,
    val mimeType: String? = null,
) {
    init {
        require(displayName.isNotEmpty() && displayName.length <= MAX_DISPLAY_NAME_LENGTH)
        require(sizeBytes == null || sizeBytes >= 0)
        require(modifiedAtEpochMillis == null || modifiedAtEpochMillis >= 0)
        require(mimeType == null || mimeType.length <= MAX_MIME_TYPE_LENGTH)
    }

    override fun toString(): String = "StorageNode(kind=$kind)"

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 512
        const val MAX_MIME_TYPE_LENGTH = 255
    }
}
