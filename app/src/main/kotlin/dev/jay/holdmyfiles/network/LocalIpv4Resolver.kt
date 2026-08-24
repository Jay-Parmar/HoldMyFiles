package dev.jay.holdmyfiles.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

sealed interface LocalIpv4Resolution {
    data class Available(val address: String) : LocalIpv4Resolution

    data object PermissionRequired : LocalIpv4Resolution

    data object Unavailable : LocalIpv4Resolution
}

fun interface LocalIpv4Resolver {
    fun resolve(): LocalIpv4Resolution
}

class AndroidLocalIpv4Resolver(
    context: Context,
) : LocalIpv4Resolver {
    private val applicationContext = context.applicationContext
    private val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun resolve(): LocalIpv4Resolution {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            applicationContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return LocalIpv4Resolution.PermissionRequired
        }

        val selected = LocalIpv4Selector.select(
            wifiNetworkCandidates() + interfaceCandidates(),
        )
        return selected?.let(LocalIpv4Resolution::Available)
            ?: LocalIpv4Resolution.Unavailable
    }

    private fun wifiNetworkCandidates(): List<LocalIpv4Candidate> = runCatching {
        listOfNotNull(connectivityManager.activeNetwork).flatMap { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return@flatMap emptyList()
            if (
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return@flatMap emptyList()
            }

            val properties = connectivityManager.getLinkProperties(network)
                ?: return@flatMap emptyList()
            val interfaceName = properties.interfaceName.orEmpty()
            properties.linkAddresses.mapNotNull { linkAddress ->
                val address = linkAddress.address as? Inet4Address ?: return@mapNotNull null
                LocalIpv4Candidate(
                    address = address.hostAddress.orEmpty(),
                    interfaceName = interfaceName,
                    source = LocalIpv4Source.WifiNetwork,
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun interfaceCandidates(): List<LocalIpv4Candidate> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { networkInterface ->
            if (
                !networkInterface.isUp ||
                networkInterface.isLoopback ||
                networkInterface.isPointToPoint ||
                networkInterface.isVirtual
            ) {
                return@flatMap emptyList()
            }

            Collections.list(networkInterface.inetAddresses).mapNotNull { address ->
                val ipv4Address = address as? Inet4Address ?: return@mapNotNull null
                LocalIpv4Candidate(
                    address = ipv4Address.hostAddress.orEmpty(),
                    interfaceName = networkInterface.name.orEmpty(),
                    source = LocalIpv4Source.NetworkInterface,
                )
            }
        }
    }.getOrDefault(emptyList())
}
