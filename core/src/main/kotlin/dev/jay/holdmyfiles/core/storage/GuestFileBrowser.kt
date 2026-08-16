package dev.jay.holdmyfiles.core.storage

import dev.jay.holdmyfiles.core.security.MonotonicClock
import dev.jay.holdmyfiles.core.security.NodeHandle
import dev.jay.holdmyfiles.core.security.OpaqueHandleRegistry
import dev.jay.holdmyfiles.core.security.RandomByteSource
import dev.jay.holdmyfiles.core.security.SecureRandomByteSource
import dev.jay.holdmyfiles.core.security.SystemMonotonicClock

class GuestNode(
    val handle: NodeHandle,
    val displayName: String,
    val kind: StorageNodeKind,
    val sizeBytes: Long?,
) {
    override fun toString(): String = "GuestNode(kind=$kind)"
}

class GuestListing(
    nodes: List<GuestNode>,
    val truncated: Boolean,
) {
    val nodes: List<GuestNode> = nodes.toList()
}

sealed interface BrowseOutcome<out T> {
    data class Ok<T>(val value: T) : BrowseOutcome<T>

    data object InvalidHandle : BrowseOutcome<Nothing>

    data object StaleHandle : BrowseOutcome<Nothing>

    data object WrongKind : BrowseOutcome<Nothing>

    data object Missing : BrowseOutcome<Nothing>

    data object Busy : BrowseOutcome<Nothing>

    data object Unavailable : BrowseOutcome<Nothing>
}

class GuestFileBrowser<R : Any, N : Any>(
    private val catalog: ShareCatalog<R>,
    private val gateway: ReadOnlyStorageGateway<R, N>,
    handleRandom: RandomByteSource = SecureRandomByteSource(),
    handleClock: MonotonicClock = SystemMonotonicClock,
    handleLifetimeMillis: Long = 15 * 60 * 1_000L,
    handleCapacity: Int = 4_096,
    private val maxListingEntries: Int = 1_000,
) {
    private val handles = OpaqueHandleRegistry<NodeTarget<N>>(
        random = handleRandom,
        clock = handleClock,
        lifetimeMillis = handleLifetimeMillis,
        capacity = handleCapacity,
    )

    init {
        require(maxListingEntries > 0)
    }

    suspend fun roots(): BrowseOutcome<GuestListing> {
        val snapshot = catalog.snapshot()
        val guestNodes = mutableListOf<GuestNode>()
        var truncated = false

        for (share in snapshot.shares) {
            if (!share.enabled) {
                continue
            }

            val root = when (val result = gateway.root(share.storageRoot)) {
                is StorageOutcome.Ok -> result.value
                StorageOutcome.Busy -> return BrowseOutcome.Busy
                else -> return BrowseOutcome.Unavailable
            }
            if (root.kind != StorageNodeKind.Directory) {
                return BrowseOutcome.Unavailable
            }

            val handle = handles.issue(
                NodeTarget(
                    shareId = share.id,
                    shareSetVersion = snapshot.version,
                    node = root,
                ),
            )
            if (handle == null) {
                truncated = true
                break
            }

            guestNodes += GuestNode(
                handle = handle,
                displayName = share.label,
                kind = StorageNodeKind.Directory,
                sizeBytes = null,
            )
        }

        return BrowseOutcome.Ok(GuestListing(guestNodes, truncated))
    }

    suspend fun list(encodedHandle: String): BrowseOutcome<GuestListing> {
        val target = handles.resolve(encodedHandle) ?: return BrowseOutcome.InvalidHandle
        val snapshot = catalog.snapshot()
        if (snapshot.version != target.shareSetVersion) {
            return BrowseOutcome.StaleHandle
        }

        val share = snapshot.shares.firstOrNull { candidate -> candidate.id == target.shareId }
        if (share == null || !share.enabled) {
            return BrowseOutcome.StaleHandle
        }
        if (target.node.kind != StorageNodeKind.Directory) {
            return BrowseOutcome.WrongKind
        }

        val listing = when (
            val result = gateway.listChildren(share.storageRoot, target.node)
        ) {
            is StorageOutcome.Ok -> result.value
            StorageOutcome.Missing -> return BrowseOutcome.Missing
            StorageOutcome.WrongKind -> return BrowseOutcome.WrongKind
            StorageOutcome.Busy -> return BrowseOutcome.Busy
            else -> return BrowseOutcome.Unavailable
        }

        val guestNodes = mutableListOf<GuestNode>()
        var truncated = listing.truncated || listing.nodes.size > maxListingEntries
        for (node in listing.nodes.take(maxListingEntries)) {
            val handle = handles.issue(
                NodeTarget(
                    shareId = share.id,
                    shareSetVersion = snapshot.version,
                    node = node,
                ),
            )
            if (handle == null) {
                truncated = true
                break
            }

            guestNodes += GuestNode(
                handle = handle,
                displayName = node.displayName,
                kind = node.kind,
                sizeBytes = node.sizeBytes,
            )
        }

        return BrowseOutcome.Ok(GuestListing(guestNodes, truncated))
    }

    fun clearHandles() {
        handles.clear()
    }

    private class NodeTarget<N : Any>(
        val shareId: ShareId,
        val shareSetVersion: ShareSetVersion,
        val node: StorageNode<N>,
    ) {
        override fun toString(): String = "NodeTarget(redacted)"
    }
}
