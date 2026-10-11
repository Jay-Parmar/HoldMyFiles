package dev.jay.holdmyfiles.network

import java.util.Locale

enum class LocalIpv4Source {
    WifiNetwork,
    NetworkInterface,
    CellularNetwork,
    VpnNetwork,
}

data class LocalIpv4Candidate(
    val address: String,
    val interfaceName: String,
    val source: LocalIpv4Source,
)

object LocalIpv4Selector {
    fun select(candidates: Iterable<LocalIpv4Candidate>): String? = candidates
        .mapNotNull { candidate -> candidate.eligibleAddress() }
        .minWithOrNull(compareBy<EligibleAddress>({ it.priority }, { it.interfaceName }, { it.value }))
        ?.address

    private fun LocalIpv4Candidate.eligibleAddress(): EligibleAddress? {
        if (source == LocalIpv4Source.CellularNetwork || source == LocalIpv4Source.VpnNetwork) {
            return null
        }

        val octets = address.parseIpv4() ?: return null
        if (!octets.isPrivate()) {
            return null
        }

        val priority = when (source) {
            LocalIpv4Source.WifiNetwork -> WIFI_NETWORK_PRIORITY
            LocalIpv4Source.NetworkInterface -> interfaceName.fallbackPriority() ?: return null
            LocalIpv4Source.CellularNetwork,
            LocalIpv4Source.VpnNetwork,
            -> return null
        }

        return EligibleAddress(
            address = octets.joinToString("."),
            interfaceName = interfaceName,
            priority = priority,
            value = octets.fold(0L) { result, octet -> result * 256 + octet },
        )
    }

    private fun String.parseIpv4(): List<Int>? {
        val parts = split('.')
        if (parts.size != IPV4_OCTET_COUNT) {
            return null
        }
        return parts.map { part ->
            if (
                part.isEmpty() ||
                part.length > MAX_IPV4_OCTET_DIGITS ||
                (part.length > 1 && part.startsWith('0')) ||
                part.any { character -> !character.isDigit() }
            ) {
                return null
            }
            part.toIntOrNull()?.takeIf { octet -> octet in 0..MAX_IPV4_OCTET }
                ?: return null
        }
    }

    private fun List<Int>.isPrivate(): Boolean =
        this[0] == 10 ||
            (this[0] == 172 && this[1] in 16..31) ||
            (this[0] == 192 && this[1] == 168)

    private fun String.fallbackPriority(): Int? {
        val normalized = lowercase(Locale.ROOT)
        return when {
            HOTSPOT_INTERFACE.matches(normalized) -> HOTSPOT_INTERFACE_PRIORITY
            WIFI_INTERFACE.matches(normalized) -> WIFI_INTERFACE_PRIORITY
            else -> null
        }
    }

    private data class EligibleAddress(
        val address: String,
        val interfaceName: String,
        val priority: Int,
        val value: Long,
    )

    private const val HOTSPOT_INTERFACE_PRIORITY = 0
    private const val WIFI_NETWORK_PRIORITY = 1
    private const val WIFI_INTERFACE_PRIORITY = 2
    private const val IPV4_OCTET_COUNT = 4
    private const val MAX_IPV4_OCTET_DIGITS = 3
    private const val MAX_IPV4_OCTET = 255
    private val HOTSPOT_INTERFACE = Regex("(?:ap|softap|sap)\\d*|ap_br_(?:wlan|swlan)\\d+")
    private val WIFI_INTERFACE = Regex("(?:wlan|swlan|wifi)\\d+")
}
