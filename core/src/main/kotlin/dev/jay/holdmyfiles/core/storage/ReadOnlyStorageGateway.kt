package dev.jay.holdmyfiles.core.storage

import java.util.concurrent.atomic.AtomicBoolean

class DirectoryListing<N : Any>(
    nodes: List<StorageNode<N>>,
    val truncated: Boolean,
) {
    val nodes: List<StorageNode<N>> = nodes.toList()

    override fun toString(): String =
        "DirectoryListing(nodeCount=${nodes.size}, truncated=$truncated)"
}

interface ReadLease : AutoCloseable {
    suspend fun read(destination: ByteArray, offset: Int, length: Int): Int
}

class OpenedFile(
    val displayName: String,
    val sizeBytes: Long?,
    val content: ReadLease,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    init {
        require(displayName.isNotEmpty() && displayName.length <= MAX_DISPLAY_NAME_LENGTH)
        require(sizeBytes == null || sizeBytes >= 0)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            content.close()
        }
    }

    override fun toString(): String = "OpenedFile(redacted)"

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 512
    }
}

sealed interface StorageOutcome<out T> {
    data class Ok<T>(val value: T) : StorageOutcome<T>

    data object Missing : StorageOutcome<Nothing>

    data object AccessRevoked : StorageOutcome<Nothing>

    data object OutsideRoot : StorageOutcome<Nothing>

    data object WrongKind : StorageOutcome<Nothing>

    data object Busy : StorageOutcome<Nothing>

    data object Unavailable : StorageOutcome<Nothing>
}

interface ReadOnlyStorageGateway<R : Any, N : Any> {
    suspend fun root(storageRoot: R): StorageOutcome<StorageNode<N>>

    suspend fun listChildren(
        storageRoot: R,
        directory: StorageNode<N>,
    ): StorageOutcome<DirectoryListing<N>>

    suspend fun openFile(
        storageRoot: R,
        file: StorageNode<N>,
    ): StorageOutcome<OpenedFile>
}
