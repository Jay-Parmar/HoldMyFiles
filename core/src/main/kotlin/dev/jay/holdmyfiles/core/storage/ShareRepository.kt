package dev.jay.holdmyfiles.core.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ShareMutationRejection {
    DuplicateRoot,
    CapacityReached,
    MissingShare,
    IdUnavailable,
}

sealed interface ShareMutationResult<R : Any> {
    val snapshot: ShareSnapshot<R>

    data class Changed<R : Any>(
        override val snapshot: ShareSnapshot<R>,
    ) : ShareMutationResult<R>

    data class Unchanged<R : Any>(
        override val snapshot: ShareSnapshot<R>,
    ) : ShareMutationResult<R>

    data class Rejected<R : Any>(
        override val snapshot: ShareSnapshot<R>,
        val reason: ShareMutationRejection,
    ) : ShareMutationResult<R>
}

interface ShareRepository<R : Any> : ShareCatalog<R> {
    suspend fun add(label: String, storageRoot: R): ShareMutationResult<R>

    suspend fun rename(id: ShareId, label: String): ShareMutationResult<R>

    suspend fun setEnabled(id: ShareId, enabled: Boolean): ShareMutationResult<R>

    suspend fun remove(id: ShareId): ShareMutationResult<R>
}

class InMemoryShareRepository<R : Any>(
    initialSnapshot: ShareSnapshot<R> = ShareSnapshot(
        version = ShareSetVersion(0),
        shares = emptyList(),
    ),
    private val idGenerator: ShareIdGenerator = RandomShareIdGenerator(),
) : ShareRepository<R> {
    private val mutex = Mutex()
    private var current = initialSnapshot

    override suspend fun snapshot(): ShareSnapshot<R> = mutex.withLock { current }

    override suspend fun add(label: String, storageRoot: R): ShareMutationResult<R> =
        mutex.withLock {
            if (current.shares.any { share -> share.storageRoot == storageRoot }) {
                return@withLock rejected(ShareMutationRejection.DuplicateRoot)
            }
            if (current.shares.size >= MAX_CONFIGURED_SHARES) {
                return@withLock rejected(ShareMutationRejection.CapacityReached)
            }

            val id = generateUniqueId()
                ?: return@withLock rejected(ShareMutationRejection.IdUnavailable)
            val share = SharedFolder(
                id = id,
                label = label,
                enabled = true,
                storageRoot = storageRoot,
            )
            changed(current.shares + share)
        }

    override suspend fun rename(id: ShareId, label: String): ShareMutationResult<R> =
        mutex.withLock {
            val index = current.shares.indexOfFirst { share -> share.id == id }
            if (index == -1) {
                return@withLock rejected(ShareMutationRejection.MissingShare)
            }
            val existing = current.shares[index]
            if (existing.label == label) {
                return@withLock ShareMutationResult.Unchanged(current)
            }

            val updated = current.shares.toMutableList()
            updated[index] = existing.copy(label = label)
            changed(updated)
        }

    override suspend fun setEnabled(
        id: ShareId,
        enabled: Boolean,
    ): ShareMutationResult<R> = mutex.withLock {
        val index = current.shares.indexOfFirst { share -> share.id == id }
        if (index == -1) {
            return@withLock rejected(ShareMutationRejection.MissingShare)
        }
        val existing = current.shares[index]
        if (existing.enabled == enabled) {
            return@withLock ShareMutationResult.Unchanged(current)
        }

        val updated = current.shares.toMutableList()
        updated[index] = existing.copy(enabled = enabled)
        changed(updated)
    }

    override suspend fun remove(id: ShareId): ShareMutationResult<R> = mutex.withLock {
        val updated = current.shares.filterNot { share -> share.id == id }
        if (updated.size == current.shares.size) {
            return@withLock rejected(ShareMutationRejection.MissingShare)
        }
        changed(updated)
    }

    private fun generateUniqueId(): ShareId? {
        val existingIds = current.shares.mapTo(mutableSetOf()) { share -> share.id }
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = idGenerator.generate()
            if (candidate !in existingIds) {
                return candidate
            }
        }
        return null
    }

    private fun changed(shares: List<SharedFolder<R>>): ShareMutationResult.Changed<R> {
        check(current.version.value < Long.MAX_VALUE) { "Share version is exhausted" }
        val snapshot = ShareSnapshot(
            version = ShareSetVersion(current.version.value + 1),
            shares = shares,
        )
        current = snapshot
        return ShareMutationResult.Changed(snapshot)
    }

    private fun rejected(reason: ShareMutationRejection): ShareMutationResult.Rejected<R> =
        ShareMutationResult.Rejected(current, reason)

    private companion object {
        const val MAX_ID_GENERATION_ATTEMPTS = 8
    }
}
