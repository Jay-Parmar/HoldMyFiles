package dev.jay.holdmyfiles.core.storage

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class BoundedReadOnlyStorageGateway<R : Any, N : Any>(
    private val delegate: ReadOnlyStorageGateway<R, N>,
    maxConcurrentReads: Int,
) : ReadOnlyStorageGateway<R, N> {
    private val readPermits: Semaphore

    init {
        require(maxConcurrentReads > 0)
        readPermits = Semaphore(maxConcurrentReads)
    }

    override suspend fun root(storageRoot: R): StorageOutcome<StorageNode<N>> =
        delegate.root(storageRoot)

    override suspend fun listChildren(
        storageRoot: R,
        directory: StorageNode<N>,
    ): StorageOutcome<DirectoryListing<N>> = delegate.listChildren(storageRoot, directory)

    override suspend fun openFile(
        storageRoot: R,
        file: StorageNode<N>,
    ): StorageOutcome<OpenedFile> {
        if (!readPermits.tryAcquire()) {
            return StorageOutcome.Busy
        }

        return try {
            when (val result = delegate.openFile(storageRoot, file)) {
                is StorageOutcome.Ok -> StorageOutcome.Ok(
                    OpenedFile(
                        displayName = result.value.displayName,
                        sizeBytes = result.value.sizeBytes,
                        content = PermitReadLease(result.value.content, readPermits),
                    ),
                )

                else -> {
                    readPermits.release()
                    result
                }
            }
        } catch (error: Throwable) {
            readPermits.release()
            throw error
        }
    }

    private class PermitReadLease(
        private val delegate: ReadLease,
        private val permit: Semaphore,
    ) : ReadLease {
        private val closed = AtomicBoolean()

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
            check(!closed.get())
            return delegate.read(destination, offset, length)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close()
                } finally {
                    permit.release()
                }
            }
        }
    }
}
