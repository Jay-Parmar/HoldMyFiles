package dev.jay.holdmyfiles

import android.app.Application
import android.net.Uri
import dev.jay.holdmyfiles.network.AndroidLocalIpv4Resolver
import dev.jay.holdmyfiles.network.LocalIpv4Resolver
import dev.jay.holdmyfiles.server.AndroidGuestAssetLoader
import dev.jay.holdmyfiles.server.DefaultSharingRunFactory
import dev.jay.holdmyfiles.server.SharingRunFactory
import dev.jay.holdmyfiles.service.AndroidSharingController
import dev.jay.holdmyfiles.service.SharingController
import dev.jay.holdmyfiles.service.SharingServiceDependencies
import dev.jay.holdmyfiles.service.SharingStateStore
import dev.jay.holdmyfiles.sharing.DataStoreShareRepository
import dev.jay.holdmyfiles.sharing.ObservableShareRepository
import dev.jay.holdmyfiles.sharing.SelectedFolderRegistrar
import dev.jay.holdmyfiles.storage.SafReadOnlyStorageGateway

class HoldMyFilesApplication :
    Application(),
    OwnerDependencies,
    SharingServiceDependencies {
    override val sharingStateStore = SharingStateStore()

    override val shareRepository: ObservableShareRepository<Uri> by lazy {
        DataStoreShareRepository(this)
    }

    override val selectedFolderRegistrar: SelectedFolderRegistrar by lazy {
        SelectedFolderRegistrar(contentResolver, shareRepository)
    }

    override val sharingController: SharingController by lazy {
        AndroidSharingController(this, sharingStateStore)
    }

    override val localIpv4Resolver: LocalIpv4Resolver by lazy {
        AndroidLocalIpv4Resolver(this)
    }

    private val storageGateway by lazy {
        SafReadOnlyStorageGateway(contentResolver)
    }

    private val guestAssetLoader by lazy {
        AndroidGuestAssetLoader(assets)
    }

    override val sharingRunFactory: SharingRunFactory by lazy {
        DefaultSharingRunFactory(
            catalog = shareRepository,
            gateway = storageGateway,
            guestAssets = guestAssetLoader::load,
            shareSnapshots = shareRepository.snapshots,
        )
    }
}
