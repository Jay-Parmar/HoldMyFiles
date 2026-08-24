package dev.jay.holdmyfiles.ui.home

import dev.jay.holdmyfiles.core.ServerStatus
import dev.jay.holdmyfiles.core.server.BoundEndpoint
import dev.jay.holdmyfiles.core.storage.ShareId

enum class SharesLoadState {
    Loading,
    Ready,
    Failed,
}

enum class ShareHealth {
    Ready,
    PermissionMissing,
    StorageUnavailable,
}

data class ShareRowUi(
    val id: ShareId,
    val label: String,
    val enabled: Boolean,
    val health: ShareHealth = ShareHealth.Ready,
)

enum class OwnerError {
    FolderPermissionDenied,
    DuplicateFolder,
    ShareLimitReached,
    InvalidFolder,
    FolderUnavailable,
    PermissionReleaseFailed,
    FolderLoadFailed,
    FolderUpdateFailed,
    LocalNetworkPermissionDenied,
    ServerStartFailed,
}

data class OwnerHomeUiState(
    val shares: List<ShareRowUi> = emptyList(),
    val sharesLoadState: SharesLoadState = SharesLoadState.Loading,
    val serverStatus: ServerStatus = ServerStatus.Stopped,
    val updatingShareIds: Set<ShareId> = emptySet(),
    val addingFolder: Boolean = false,
    val pendingRemoval: ShareRowUi? = null,
    val error: OwnerError? = null,
) {
    val enabledShareCount: Int
        get() = shares.count { share ->
            share.enabled && share.health == ShareHealth.Ready
        }

    val canStart: Boolean
        get() =
            sharesLoadState == SharesLoadState.Ready &&
                enabledShareCount > 0 &&
                (serverStatus == ServerStatus.Stopped || serverStatus is ServerStatus.Failed)

    val canStop: Boolean
        get() = serverStatus == ServerStatus.Starting || serverStatus is ServerStatus.Running

    val isSharing: Boolean
        get() = serverStatus is ServerStatus.Running
}

sealed interface HomeEffect {
    data object OpenFolderPicker : HomeEffect

    data object RequestStartPermissions : HomeEffect

    data class CopyUrl(val endpoint: BoundEndpoint) : HomeEffect

    data class CopyPin(val pin: dev.jay.holdmyfiles.core.security.RunPin) : HomeEffect

    data class ShareAccess(
        val endpoint: BoundEndpoint,
        val pin: dev.jay.holdmyfiles.core.security.RunPin,
    ) : HomeEffect
}

sealed interface HomeAction {
    data object AddFolder : HomeAction

    data object RetryFolders : HomeAction

    data class SetEnabled(
        val id: ShareId,
        val enabled: Boolean,
    ) : HomeAction

    data class RequestRemove(val id: ShareId) : HomeAction

    data object ConfirmRemove : HomeAction

    data object DismissRemove : HomeAction

    data object StartSharing : HomeAction

    data object StopSharing : HomeAction

    data object CopyUrl : HomeAction

    data object CopyPin : HomeAction

    data object ShareAccessDetails : HomeAction

    data object DismissError : HomeAction
}
