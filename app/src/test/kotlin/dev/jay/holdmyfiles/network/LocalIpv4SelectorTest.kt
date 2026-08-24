package dev.jay.holdmyfiles.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalIpv4SelectorTest {
    @Test
    fun `selects a private Wi-Fi address and ignores unsafe transports`() {
        val selected = LocalIpv4Selector.select(
            listOf(
                LocalIpv4Candidate(
                    address = "10.0.0.7",
                    interfaceName = "rmnet_data0",
                    source = LocalIpv4Source.CellularNetwork,
                ),
                LocalIpv4Candidate(
                    address = "192.168.44.3",
                    interfaceName = "tun0",
                    source = LocalIpv4Source.VpnNetwork,
                ),
                LocalIpv4Candidate(
                    address = "0.0.0.0",
                    interfaceName = "wlan0",
                    source = LocalIpv4Source.WifiNetwork,
                ),
                LocalIpv4Candidate(
                    address = "192.168.1.23",
                    interfaceName = "wlan0",
                    source = LocalIpv4Source.WifiNetwork,
                ),
            ),
        )

        assertEquals("192.168.1.23", selected)
    }

    @Test
    fun `uses a private hotspot interface when Android exposes no Wi-Fi network`() {
        val selected = LocalIpv4Selector.select(
            listOf(
                LocalIpv4Candidate(
                    address = "192.168.43.1",
                    interfaceName = "ap0",
                    source = LocalIpv4Source.NetworkInterface,
                ),
            ),
        )

        assertEquals("192.168.43.1", selected)
    }

    @Test
    fun `rejects public link-local malformed and unknown-interface addresses`() {
        val candidates = listOf(
            LocalIpv4Candidate("8.8.8.8", "wlan0", LocalIpv4Source.WifiNetwork),
            LocalIpv4Candidate("169.254.1.3", "wlan0", LocalIpv4Source.WifiNetwork),
            LocalIpv4Candidate("192.168.1.2.example", "wlan0", LocalIpv4Source.WifiNetwork),
            LocalIpv4Candidate("172.15.1.2", "wlan0", LocalIpv4Source.WifiNetwork),
            LocalIpv4Candidate("192.168.1.2", "eth0", LocalIpv4Source.NetworkInterface),
        )

        assertNull(LocalIpv4Selector.select(candidates))
    }

    @Test
    fun `prefers an active hotspot over the connected Wi-Fi network`() {
        val selected = LocalIpv4Selector.select(
            listOf(
                LocalIpv4Candidate("192.168.43.1", "ap0", LocalIpv4Source.NetworkInterface),
                LocalIpv4Candidate("10.20.30.40", "wlan0", LocalIpv4Source.WifiNetwork),
            ),
        )

        assertEquals("192.168.43.1", selected)
    }
}
