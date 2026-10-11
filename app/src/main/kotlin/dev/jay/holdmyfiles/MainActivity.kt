package dev.jay.holdmyfiles

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.jay.holdmyfiles.core.security.RunPin
import dev.jay.holdmyfiles.core.server.BoundEndpoint
import dev.jay.holdmyfiles.service.SharingController
import dev.jay.holdmyfiles.sharing.ObservableShareRepository
import dev.jay.holdmyfiles.sharing.SelectedFolderRegistrar
import dev.jay.holdmyfiles.ui.home.HomeAction
import dev.jay.holdmyfiles.ui.home.HomeEffect
import dev.jay.holdmyfiles.ui.home.OwnerHomeScreen
import dev.jay.holdmyfiles.ui.home.OwnerHomeViewModel
import dev.jay.holdmyfiles.ui.theme.HoldMyFilesTheme
import kotlinx.coroutines.launch

interface OwnerDependencies {
    val shareRepository: ObservableShareRepository<Uri>
    val selectedFolderRegistrar: SelectedFolderRegistrar
    val sharingController: SharingController
}

class MainActivity : ComponentActivity() {
    private val ownerViewModel: OwnerHomeViewModel by lazy(LazyThreadSafetyMode.NONE) {
        val dependencies = application as? OwnerDependencies
            ?: error("Application does not provide owner dependencies")
        ViewModelProvider(
            this,
            ownerViewModelFactory(dependencies),
        )[OwnerHomeViewModel::class.java]
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        ownerViewModel.folderSelected(uri)
    }

    private val startPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        ownerViewModel.startPermissionsResolved(
            localNetworkGranted = hasRequiredLocalNetworkPermission(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HoldMyFilesTheme {
                val state by ownerViewModel.state.collectAsStateWithLifecycle()
                OwnerHomeScreen(
                    state = state,
                    onAction = ownerViewModel::onAction,
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ownerViewModel.effects.collect(::handleEffect)
            }
        }
    }

    private fun handleEffect(effect: HomeEffect) {
        when (effect) {
            HomeEffect.OpenFolderPicker -> folderPicker.launch(null)
            HomeEffect.RequestStartPermissions -> requestPermissionsThenStart()
            is HomeEffect.CopyUrl -> copyUrl(effect.endpoint)
            is HomeEffect.CopyPin -> copyPin(effect.pin)
            is HomeEffect.ShareAccess -> shareAccess(effect.endpoint, effect.pin)
        }
    }

    private fun requestPermissionsThenStart() {
        val missing = missingStartPermissions(
            sdkInt = Build.VERSION.SDK_INT,
            isGranted = { permission ->
                ContextCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED
            },
        )
        if (missing.isEmpty()) {
            ownerViewModel.startPermissionsResolved(localNetworkGranted = true)
        } else {
            startPermissionRequest.launch(missing.toTypedArray())
        }
    }

    private fun hasRequiredLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_API ||
            ContextCompat.checkSelfPermission(this, ACCESS_LOCAL_NETWORK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun copyUrl(endpoint: BoundEndpoint) {
        clipboard().setPrimaryClip(
            ClipData.newPlainText("Hold My Files URL", endpoint.origin),
        )
        showLegacyCopyConfirmation()
    }

    private fun copyPin(pin: RunPin) {
        val clip = ClipData.newPlainText("Hold My Files PIN", pin.displayValue())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard().setPrimaryClip(clip)
        showLegacyCopyConfirmation()
    }

    private fun shareAccess(
        endpoint: BoundEndpoint,
        pin: RunPin,
    ) {
        val text = buildString {
            appendLine("Hold My Files")
            appendLine("Open on the same Wi-Fi or hotspot:")
            appendLine(endpoint.origin)
            appendLine("PIN: ${pin.displayValue()}")
            appendLine()
            append("Read-only access. The connection is not encrypted.")
        }
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(sendIntent, "Share access details"))
    }

    private fun clipboard(): ClipboardManager =
        getSystemService(ClipboardManager::class.java)

    private fun showLegacyCopyConfirmation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun missingStartPermissions(
    sdkInt: Int,
    isGranted: (String) -> Boolean,
): List<String> = buildList {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU && !isGranted(POST_NOTIFICATIONS_PERMISSION)) {
        add(POST_NOTIFICATIONS_PERMISSION)
    }
    if (sdkInt >= LOCAL_NETWORK_PERMISSION_API && !isGranted(ACCESS_LOCAL_NETWORK_PERMISSION)) {
        add(ACCESS_LOCAL_NETWORK_PERMISSION)
    }
}

private fun ownerViewModelFactory(dependencies: OwnerDependencies): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OwnerHomeViewModel::class.java))
            return OwnerHomeViewModel(
                shareRepository = dependencies.shareRepository,
                selectedFolderRegistrar = dependencies.selectedFolderRegistrar,
                sharingController = dependencies.sharingController,
            ) as T
        }
    }

private const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
private const val LOCAL_NETWORK_PERMISSION_API = 37
