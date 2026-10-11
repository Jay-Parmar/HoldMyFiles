@file:Suppress("HardcodedText")

package dev.jay.holdmyfiles.ui.home

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
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

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun OwnerHomeScreen(
    state: OwnerHomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(
                start = 18.dp,
                top = 16.dp,
                end = 18.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "app-header") {
                OwnerHeader()
            }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "Shared folders",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = if (state.shares.size == 1) "1 FOLDER" else "${state.shares.size} FOLDERS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = { onAction(HomeAction.AddFolder) },
                        enabled = !state.addingFolder,
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
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
private fun OwnerHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrandMark(
            background = MaterialTheme.colorScheme.primary,
            foreground = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(52.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "Hold My Files",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "LOCAL  /  READ ONLY",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = "01",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ServerStatusCard(
    state: OwnerHomeUiState,
    onAction: (HomeAction) -> Unit,
) {
    val running = state.serverStatus is ServerStatus.Running
    val containerColor = if (running) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (running) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (running) {
                    testTag = "live-sharing-panel"
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (running) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (running) 8.dp else 2.dp),
    ) {
        Box {
            if (!running) {
                NetworkArtwork(
                    color = MaterialTheme.colorScheme.primary,
                    mutedColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 14.dp, end = 10.dp)
                        .size(112.dp)
                        .alpha(0.72f),
                )
            }
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
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
}

@Composable
private fun StoppedStatus(
    state: OwnerHomeUiState,
    onAction: (HomeAction) -> Unit,
) {
    StatusBadge("OFFLINE")
    Column(
        modifier = Modifier.fillMaxWidth(0.68f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusTitle("Not sharing")
        Text(
            text = enabledFoldersText(state.enabledShareCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = { onAction(HomeAction.StartSharing) },
        enabled = state.canStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
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
    StatusBadge("CONNECTING")
    ProgressHeading("Starting sharing")
    Text("Preparing a private PIN and opening the local server.")
    OutlinedButton(
        onClick = { onAction(HomeAction.StopSharing) },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusBadge("LIVE", active = true)
            StatusTitle("Sharing is active")
        }
        NetworkArtwork(
            color = MaterialTheme.colorScheme.onPrimary,
            mutedColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f),
            modifier = Modifier.size(82.dp),
        )
    }
    if (state.enabledShareCount == 0) {
        Text("Sharing is active, but no folders are enabled.")
    } else {
        Text("Guests on the same Wi-Fi or hotspot can connect now.")
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onPrimary,
        contentColor = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("SHARING URL", style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(
                    text = endpoint?.origin ?: "Local address unavailable",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Text("SHARING PIN", style = MaterialTheme.typography.labelMedium)
    PinDigits(
        pin = pin,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Sharing PIN ${pin.toCharArray().joinToString(" ")}"
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = { onAction(HomeAction.CopyUrl) },
            enabled = endpoint != null,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("Copy URL")
        }
        OutlinedButton(
            onClick = { onAction(HomeAction.CopyPin) },
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("Copy PIN")
        }
    }
    OutlinedButton(
        onClick = { onAction(HomeAction.ShareAccessDetails) },
        enabled = endpoint != null,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text("Share access details")
    }
    Button(
        onClick = { onAction(HomeAction.StopSharing) },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onPrimary,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text("Stop sharing")
    }
}

@Composable
private fun PinDigits(
    pin: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pin.forEach { digit ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f),
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = digit.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun StoppingStatus() {
    StatusBadge("CLOSING")
    ProgressHeading("Stopping sharing")
    Text("Closing guest connections and invalidating access.")
    Button(
        onClick = {},
        enabled = false,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text("Stopping")
    }
}

@Composable
private fun FailedStatus(
    state: OwnerHomeUiState,
    onAction: (HomeAction) -> Unit,
) {
    StatusBadge("NEEDS ATTENTION", warning = true)
    StatusTitle("Sharing could not start")
    Text("Check that Wi-Fi or a hotspot is active, then try again.")
    Button(
        onClick = { onAction(HomeAction.StartSharing) },
        enabled = state.canStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
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
private fun StatusBadge(
    label: String,
    active: Boolean = false,
    warning: Boolean = false,
) {
    val background = when {
        active -> MaterialTheme.colorScheme.onPrimary
        warning -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        active -> MaterialTheme.colorScheme.primary
        warning -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = background,
        contentColor = content,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(content, RoundedCornerShape(50)),
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TransportWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecurityMark(
                background = MaterialTheme.colorScheme.tertiaryContainer,
                foreground = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(48.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "Trusted networks only",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Guests need the same Wi-Fi or hotspot. Traffic is not encrypted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
    ) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BrandMark(
                background = MaterialTheme.colorScheme.primaryContainer,
                foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(68.dp),
            )
            Text(
                text = "Nothing here yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Pick a folder for guests to browse. Your files always stay read-only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onAdd,
                enabled = !addingFolder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
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
    val readyAndShared = share.health == ShareHealth.Ready && share.enabled

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(Modifier.semanticsShareTag(share)),
        colors = CardDefaults.cardColors(
            containerColor = if (readyAndShared) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            1.dp,
            if (readyAndShared) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
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
                    testTag = "share-toggle-${share.id.value}"
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark(
                background = if (readyAndShared) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                foreground = if (readyAndShared) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(50.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = share.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (share.health) {
                        ShareHealth.Ready -> if (share.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        ShareHealth.PermissionMissing,
                        ShareHealth.StorageUnavailable,
                        -> MaterialTheme.colorScheme.error
                    },
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Read-only access",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onRemove,
                enabled = !updating,
                modifier = Modifier.height(48.dp),
            ) {
                Text("Remove ${share.label}")
            }
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
    ShareHealth.PermissionMissing -> "PERMISSION LOST"
    ShareHealth.StorageUnavailable -> "STORAGE UNAVAILABLE"
    ShareHealth.Ready -> if (share.enabled) "SHARED" else "NOT SHARED"
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
