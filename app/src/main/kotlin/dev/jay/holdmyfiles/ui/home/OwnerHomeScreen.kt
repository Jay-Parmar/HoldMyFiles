@file:Suppress("HardcodedText")

package dev.jay.holdmyfiles.ui.home

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jay.holdmyfiles.core.ServerStatus
import dev.jay.holdmyfiles.core.server.BoundEndpoint

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun OwnerHomeScreen(
    state: OwnerHomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("Hold My Files") })
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "server-status") {
                ServerStatusCard(
                    state = state,
                    onAction = onAction,
                )
            }

            item(key = "transport-warning") {
                TransportWarning()
            }

            state.error?.let { error ->
                item(key = "owner-error") {
                    ErrorCard(
                        error = error,
                        onDismiss = { onAction(HomeAction.DismissError) },
                    )
                }
            }

            item(key = "folders-heading") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Shared folders",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FilledTonalButton(
                        onClick = { onAction(HomeAction.AddFolder) },
                        enabled = !state.addingFolder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.addingFolder) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Adding")
                        } else {
                            Text("Add folder")
                        }
                    }
                }
            }

            when (state.sharesLoadState) {
                SharesLoadState.Loading -> item(key = "folders-loading") {
                    LoadingFoldersCard()
                }

                SharesLoadState.Failed -> item(key = "folders-failed") {
                    FailedFoldersCard(
                        onRetry = { onAction(HomeAction.RetryFolders) },
                    )
                }

                SharesLoadState.Ready -> {
                    if (state.shares.isEmpty()) {
                        item(key = "folders-empty") {
                            EmptyFoldersCard(
                                addingFolder = state.addingFolder,
                                onAdd = { onAction(HomeAction.AddFolder) },
                            )
                        }
                    } else {
                        items(
                            items = state.shares,
                            key = { share -> share.id.value },
                        ) { share ->
                            ShareRow(
                                share = share,
                                updating = share.id in state.updatingShareIds,
                                onEnabledChange = { enabled ->
                                    onAction(HomeAction.SetEnabled(share.id, enabled))
                                },
                                onRemove = {
                                    onAction(HomeAction.RequestRemove(share.id))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    state.pendingRemoval?.let { share ->
        RemoveShareDialog(
            share = share,
            sharingIsActive = state.isSharing,
            onConfirm = { onAction(HomeAction.ConfirmRemove) },
            onDismiss = { onAction(HomeAction.DismissRemove) },
        )
    }
}

@Composable
private fun ServerStatusCard(
    state: OwnerHomeUiState,
    onAction: (HomeAction) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val status = state.serverStatus) {
                ServerStatus.Stopped -> StoppedStatus(state, onAction)
                ServerStatus.Starting -> StartingStatus(onAction)
                is ServerStatus.Running -> RunningStatus(status, state, onAction)
                ServerStatus.Stopping -> StoppingStatus()
                is ServerStatus.Failed -> FailedStatus(state, onAction)
            }
        }
    }
}

@Composable
private fun StoppedStatus(
    state: OwnerHomeUiState,
    onAction: (HomeAction) -> Unit,
) {
    StatusTitle("Not sharing")
    Text(
        text = enabledFoldersText(state.enabledShareCount),
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        onClick = { onAction(HomeAction.StartSharing) },
        enabled = state.canStart,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Start sharing")
    }
    if (state.sharesLoadState == SharesLoadState.Ready && state.enabledShareCount == 0) {
        Text(
            text = "Enable at least one available folder to start.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StartingStatus(onAction: (HomeAction) -> Unit) {
    ProgressHeading("Starting sharing")
    Text("Preparing a private PIN and opening the local server.")
    OutlinedButton(
        onClick = { onAction(HomeAction.StopSharing) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Cancel")
    }
}

@Composable
private fun RunningStatus(
    status: ServerStatus.Running,
    state: OwnerHomeUiState,
    onAction: (HomeAction) -> Unit,
) {
    val endpoint = safeEndpoint(status)
    val pin = status.pin.displayValue()

    StatusTitle("Sharing is active")
    if (state.enabledShareCount == 0) {
        Text("Sharing is active, but no folders are enabled.")
    } else {
        Text("Guests on the same Wi-Fi or hotspot can open:")
    }

    Text("Sharing URL", style = MaterialTheme.typography.labelLarge)
    SelectionContainer {
        Text(
            text = endpoint?.origin ?: "Local address unavailable",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }

    Text("Sharing PIN", style = MaterialTheme.typography.labelLarge)
    Text(
        text = pin.chunked(3).joinToString(" "),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Sharing PIN ${pin.toCharArray().joinToString(" ")}"
        },
        style = MaterialTheme.typography.headlineMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )

    ButtonGrid(
        firstText = "Copy sharing URL",
        firstEnabled = endpoint != null,
        onFirst = { onAction(HomeAction.CopyUrl) },
        secondText = "Copy sharing PIN",
        onSecond = { onAction(HomeAction.CopyPin) },
    )
    OutlinedButton(
        onClick = { onAction(HomeAction.ShareAccessDetails) },
        enabled = endpoint != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Share access details")
    }
    Button(
        onClick = { onAction(HomeAction.StopSharing) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Stop sharing")
    }
}

@Composable
private fun StoppingStatus() {
    ProgressHeading("Stopping sharing")
    Text("Closing guest connections and invalidating access.")
    Button(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Stopping")
    }
}

@Composable
private fun FailedStatus(
    state: OwnerHomeUiState,
    onAction: (HomeAction) -> Unit,
) {
    StatusTitle("Sharing could not start")
    Text("Check that Wi-Fi or a hotspot is active, then try again.")
    Button(
        onClick = { onAction(HomeAction.StartSharing) },
        enabled = state.canStart,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Retry")
    }
}

@Composable
private fun ProgressHeading(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        StatusTitle(text)
    }
}

@Composable
private fun StatusTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ButtonGrid(
    firstText: String,
    firstEnabled: Boolean,
    onFirst: () -> Unit,
    secondText: String,
    onSecond: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onFirst,
            enabled = firstEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(firstText)
        }
        OutlinedButton(
            onClick = onSecond,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(secondText)
        }
    }
}

@Composable
private fun TransportWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Local connection warning",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Guests must be on the same Wi-Fi or hotspot. This connection is not " +
                    "encrypted. Do not share sensitive files or use an untrusted network.",
            )
        }
    }
}

@Composable
private fun ErrorCard(
    error: OwnerError,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = errorMessage(error),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun LoadingFoldersCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Text("Loading shared folders")
        }
    }
}

@Composable
private fun FailedFoldersCard(onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Shared folders could not be loaded.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Your folders were not changed. Try loading them again.")
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Retry loading folders")
            }
        }
    }
}

@Composable
private fun EmptyFoldersCard(
    addingFolder: Boolean,
    onAdd: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "No folders selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Choose a folder to make its files available for read-only access.")
            OutlinedButton(
                onClick = onAdd,
                enabled = !addingFolder,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (addingFolder) "Adding folder" else "Choose a folder")
            }
        }
    }
}

@Composable
private fun ShareRow(
    share: ShareRowUi,
    updating: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val toggleEnabled = share.health == ShareHealth.Ready && !updating
    val status = shareStatus(share)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(Modifier.semanticsShareTag(share)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = share.enabled,
                    enabled = toggleEnabled,
                    role = Role.Switch,
                    onValueChange = onEnabledChange,
                )
                .semantics {
                    stateDescription = if (share.enabled) "Enabled" else "Disabled"
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = share.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (updating) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            } else {
                Switch(
                    checked = share.enabled,
                    onCheckedChange = null,
                    enabled = toggleEnabled,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        HorizontalDivider()
        TextButton(
            onClick = onRemove,
            enabled = !updating,
            modifier = Modifier
                .align(Alignment.End)
                .height(48.dp),
        ) {
            Text("Remove ${share.label}")
        }
    }
}

private fun Modifier.semanticsShareTag(share: ShareRowUi): Modifier =
    this.then(
        Modifier.semantics {
            testTag = "share-row-${share.id.value}"
        },
    )

@Composable
private fun RemoveShareDialog(
    share: ShareRowUi,
    sharingIsActive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove shared folder?") },
        text = {
            Text(
                if (sharingIsActive) {
                    "Remove ${share.label}? Guests will lose access immediately."
                } else {
                    "Remove ${share.label} from Hold My Files? Files on your phone will not be deleted."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun safeEndpoint(status: ServerStatus.Running): BoundEndpoint? =
    runCatching { BoundEndpoint(status.address, status.port) }.getOrNull()

private fun enabledFoldersText(count: Int): String = when (count) {
    0 -> "No folders are enabled."
    1 -> "1 folder is ready to share."
    else -> "$count folders are ready to share."
}

private fun shareStatus(share: ShareRowUi): String = when (share.health) {
    ShareHealth.PermissionMissing -> "Permission lost"
    ShareHealth.StorageUnavailable -> "Storage unavailable"
    ShareHealth.Ready -> if (share.enabled) "Shared" else "Not shared"
}

private fun errorMessage(error: OwnerError): String = when (error) {
    OwnerError.FolderPermissionDenied ->
        "Read access was not granted for that folder. Choose it again to retry."
    OwnerError.DuplicateFolder -> "That folder is already in your shared folders."
    OwnerError.ShareLimitReached -> "The shared folder limit has been reached."
    OwnerError.InvalidFolder -> "That selection is not a folder that can be shared."
    OwnerError.FolderUnavailable -> "That folder is no longer available on this device."
    OwnerError.PermissionReleaseFailed ->
        "The folder was removed, but Android could not release its saved read permission."
    OwnerError.FolderLoadFailed -> "Shared folders could not be loaded."
    OwnerError.FolderUpdateFailed -> "The folder change could not be saved. Try again."
    OwnerError.LocalNetworkPermissionDenied ->
        "Local network access is required to share files with nearby devices."
    OwnerError.ServerStartFailed ->
        "Sharing could not start. Check your Wi-Fi or hotspot and try again."
}
