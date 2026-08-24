package dev.jay.holdmyfiles.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jay.holdmyfiles.core.ServerStatus
import dev.jay.holdmyfiles.core.server.BoundEndpoint
import dev.jay.holdmyfiles.core.storage.ShareId
import dev.jay.holdmyfiles.service.SharingController
import dev.jay.holdmyfiles.sharing.ObservableShareRepository
import dev.jay.holdmyfiles.sharing.SelectedFolderRegistrar
import dev.jay.holdmyfiles.sharing.SelectedFolderRejection
import dev.jay.holdmyfiles.sharing.SelectedFolderResult
import dev.jay.holdmyfiles.sharing.SelectedFolderWarning
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal interface OwnerShareSource {
    val shares: Flow<List<ShareRowUi>>
}

internal interface OwnerFolderManager {
    suspend fun addPickedTree(treeUri: Uri): OwnerMutationFailure?

    suspend fun setEnabled(id: ShareId, enabled: Boolean): OwnerMutationFailure?

    suspend fun remove(id: ShareId): OwnerMutationFailure?
}

internal enum class OwnerMutationFailure {
    InvalidFolder,
    PermissionDenied,
    DuplicateFolder,
    ShareLimitReached,
    MissingShare,
    IdUnavailable,
    PersistenceUnavailable,
    PermissionReleaseFailed,
}

class OwnerHomeViewModel internal constructor(
    private val shareSource: OwnerShareSource,
    private val folderManager: OwnerFolderManager,
    private val sharingController: SharingController,
) : ViewModel() {
    constructor(
        shareRepository: ObservableShareRepository<Uri>,
        selectedFolderRegistrar: SelectedFolderRegistrar,
        sharingController: SharingController,
    ) : this(
        shareSource = RepositoryOwnerShareSource(shareRepository),
        folderManager = RegistrarOwnerFolderManager(selectedFolderRegistrar),
        sharingController = sharingController,
    )

    private val mutableState = MutableStateFlow(OwnerHomeUiState())
    val state: StateFlow<OwnerHomeUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<HomeEffect>(capacity = Channel.BUFFERED)
    val effects: Flow<HomeEffect> = effectChannel.receiveAsFlow()

    private var shareObservation: Job? = null

    init {
        observeShares()
        viewModelScope.launch {
            sharingController.status.collect { status ->
                mutableState.update { current -> current.copy(serverStatus = status) }
            }
        }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.AddFolder -> requestFolderPicker()
            HomeAction.RetryFolders -> observeShares()
            is HomeAction.SetEnabled -> setEnabled(action.id, action.enabled)
            is HomeAction.RequestRemove -> requestRemove(action.id)
            HomeAction.ConfirmRemove -> confirmRemove()
            HomeAction.DismissRemove -> dismissRemove()
            HomeAction.StartSharing -> requestStart()
            HomeAction.StopSharing -> stopSharing()
            HomeAction.CopyUrl -> copyUrl()
            HomeAction.CopyPin -> copyPin()
            HomeAction.ShareAccessDetails -> shareAccess()
            HomeAction.DismissError -> dismissError()
        }
    }

    fun folderSelected(treeUri: Uri?) {
        if (treeUri == null || mutableState.value.addingFolder) {
            return
        }

        mutableState.update { current -> current.copy(addingFolder = true, error = null) }
        viewModelScope.launch {
            val failure = runCatching { folderManager.addPickedTree(treeUri) }
                .getOrElse { OwnerMutationFailure.PersistenceUnavailable }
            mutableState.update { current ->
                current.copy(
                    addingFolder = false,
                    error = failure?.toOwnerError(),
                )
            }
        }
    }

    fun startPermissionsResolved(localNetworkGranted: Boolean) {
        if (!localNetworkGranted) {
            mutableState.update { current ->
                current.copy(error = OwnerError.LocalNetworkPermissionDenied)
            }
            return
        }
        if (!mutableState.value.canStart) {
            return
        }

        runCatching(sharingController::start)
            .onFailure {
                mutableState.update { current ->
                    current.copy(error = OwnerError.ServerStartFailed)
                }
            }
    }

    private fun observeShares() {
        shareObservation?.cancel()
        mutableState.update { current ->
            current.copy(
                sharesLoadState = SharesLoadState.Loading,
                error = if (current.error == OwnerError.FolderLoadFailed) null else current.error,
            )
        }
        shareObservation = viewModelScope.launch {
            shareSource.shares
                .catch {
                    mutableState.update { current ->
                        current.copy(
                            sharesLoadState = SharesLoadState.Failed,
                            error = OwnerError.FolderLoadFailed,
                        )
                    }
                }
                .collect { shares ->
                    mutableState.update { current ->
                        current.copy(
                            shares = shares.toList(),
                            sharesLoadState = SharesLoadState.Ready,
                        )
                    }
                }
        }
    }

    private fun requestFolderPicker() {
        if (!mutableState.value.addingFolder) {
            effectChannel.trySend(HomeEffect.OpenFolderPicker)
        }
    }

    private fun setEnabled(id: ShareId, enabled: Boolean) {
        val current = mutableState.value
        val share = current.shares.firstOrNull { candidate -> candidate.id == id } ?: return
        if (
            share.enabled == enabled ||
            share.health != ShareHealth.Ready ||
            id in current.updatingShareIds
        ) {
            return
        }
        mutateFolder(id) { folderManager.setEnabled(id, enabled) }
    }

    private fun requestRemove(id: ShareId) {
        val current = mutableState.value
        if (id in current.updatingShareIds) {
            return
        }
        val share = current.shares.firstOrNull { candidate -> candidate.id == id } ?: return
        mutableState.update { state -> state.copy(pendingRemoval = share) }
    }

    private fun confirmRemove() {
        val share = mutableState.value.pendingRemoval ?: return
        if (share.id in mutableState.value.updatingShareIds) {
            return
        }
        mutableState.update { current -> current.copy(pendingRemoval = null) }
        mutateFolder(share.id) { folderManager.remove(share.id) }
    }

    private fun dismissRemove() {
        mutableState.update { current -> current.copy(pendingRemoval = null) }
    }

    private fun mutateFolder(
        id: ShareId,
        mutation: suspend () -> OwnerMutationFailure?,
    ) {
        mutableState.update { current ->
            current.copy(
                updatingShareIds = current.updatingShareIds + id,
                error = null,
            )
        }
        viewModelScope.launch {
            val failure = runCatching { mutation() }
                .getOrElse { OwnerMutationFailure.PersistenceUnavailable }
            mutableState.update { current ->
                current.copy(
                    updatingShareIds = current.updatingShareIds - id,
                    error = failure?.toOwnerError(),
                )
            }
        }
    }

    private fun requestStart() {
        if (mutableState.value.canStart) {
            effectChannel.trySend(HomeEffect.RequestStartPermissions)
        }
    }

    private fun stopSharing() {
        if (!mutableState.value.canStop) {
            return
        }
        runCatching(sharingController::stop)
            .onFailure {
                mutableState.update { current ->
                    current.copy(error = OwnerError.ServerStartFailed)
                }
            }
    }

    private fun copyUrl() {
        runningEndpoint()?.let { endpoint ->
            effectChannel.trySend(HomeEffect.CopyUrl(endpoint))
        }
    }

    private fun copyPin() {
        val status = mutableState.value.serverStatus as? ServerStatus.Running ?: return
        effectChannel.trySend(HomeEffect.CopyPin(status.pin))
    }

    private fun shareAccess() {
        val status = mutableState.value.serverStatus as? ServerStatus.Running ?: return
        val endpoint = endpointFor(status) ?: return
        effectChannel.trySend(HomeEffect.ShareAccess(endpoint, status.pin))
    }

    private fun runningEndpoint(): BoundEndpoint? {
        val status = mutableState.value.serverStatus as? ServerStatus.Running ?: return null
        return endpointFor(status)
    }

    private fun endpointFor(status: ServerStatus.Running): BoundEndpoint? =
        runCatching { BoundEndpoint(status.address, status.port) }
            .onFailure {
                mutableState.update { current ->
                    current.copy(error = OwnerError.ServerStartFailed)
                }
            }
            .getOrNull()

    private fun dismissError() {
        mutableState.update { current -> current.copy(error = null) }
    }
}

private fun OwnerMutationFailure.toOwnerError(): OwnerError = when (this) {
    OwnerMutationFailure.InvalidFolder -> OwnerError.InvalidFolder
    OwnerMutationFailure.PermissionDenied -> OwnerError.FolderPermissionDenied
    OwnerMutationFailure.DuplicateFolder -> OwnerError.DuplicateFolder
    OwnerMutationFailure.ShareLimitReached -> OwnerError.ShareLimitReached
    OwnerMutationFailure.MissingShare,
    OwnerMutationFailure.IdUnavailable,
    OwnerMutationFailure.PersistenceUnavailable,
    -> OwnerError.FolderUpdateFailed
    OwnerMutationFailure.PermissionReleaseFailed -> OwnerError.PermissionReleaseFailed
}

private class RepositoryOwnerShareSource(
    repository: ObservableShareRepository<Uri>,
) : OwnerShareSource {
    override val shares: Flow<List<ShareRowUi>> = repository.snapshots.map { snapshot ->
        snapshot.shares.map { share ->
            ShareRowUi(
                id = share.id,
                label = share.label,
                enabled = share.enabled,
            )
        }
    }
}

private class RegistrarOwnerFolderManager(
    private val registrar: SelectedFolderRegistrar,
) : OwnerFolderManager {
    override suspend fun addPickedTree(treeUri: Uri): OwnerMutationFailure? =
        registrar.addPickedTree(treeUri).toOwnerMutationFailure()

    override suspend fun setEnabled(
        id: ShareId,
        enabled: Boolean,
    ): OwnerMutationFailure? = registrar.setEnabled(id, enabled).toOwnerMutationFailure()

    override suspend fun remove(id: ShareId): OwnerMutationFailure? =
        registrar.remove(id).toOwnerMutationFailure()
}

private fun SelectedFolderResult.toOwnerMutationFailure(): OwnerMutationFailure? =
    when (this) {
        is SelectedFolderResult.Changed,
        is SelectedFolderResult.Unchanged,
        -> if (warning == SelectedFolderWarning.PermissionReleaseFailed) {
            OwnerMutationFailure.PermissionReleaseFailed
        } else {
            null
        }

        is SelectedFolderResult.Rejected -> when (reason) {
            SelectedFolderRejection.InvalidTree -> OwnerMutationFailure.InvalidFolder
            SelectedFolderRejection.PermissionDenied -> OwnerMutationFailure.PermissionDenied
            SelectedFolderRejection.DuplicateRoot -> OwnerMutationFailure.DuplicateFolder
            SelectedFolderRejection.CapacityReached -> OwnerMutationFailure.ShareLimitReached
            SelectedFolderRejection.MissingShare -> OwnerMutationFailure.MissingShare
            SelectedFolderRejection.IdUnavailable -> OwnerMutationFailure.IdUnavailable
            SelectedFolderRejection.PersistenceUnavailable ->
                OwnerMutationFailure.PersistenceUnavailable
        }
    }
