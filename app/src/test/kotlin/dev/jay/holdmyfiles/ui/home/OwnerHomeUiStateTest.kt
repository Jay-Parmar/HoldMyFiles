package dev.jay.holdmyfiles.ui.home

import dev.jay.holdmyfiles.core.ServerStatus
import dev.jay.holdmyfiles.core.security.RandomNumberSource
import dev.jay.holdmyfiles.core.security.RunPinGenerator
import dev.jay.holdmyfiles.core.storage.ShareId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerHomeUiStateTest {
    @Test
    fun `an empty loaded share list cannot start sharing`() {
        val state = OwnerHomeUiState(
            sharesLoadState = SharesLoadState.Ready,
        )

        assertFalse(state.canStart)
    }

    @Test
    fun `an enabled accessible folder can start sharing`() {
        val state = OwnerHomeUiState(
            shares = listOf(
                ShareRowUi(
                    id = ShareId("photos"),
                    label = "Photos",
                    enabled = true,
                ),
            ),
            sharesLoadState = SharesLoadState.Ready,
        )

        assertTrue(state.canStart)
    }

    @Test
    fun `an enabled folder with missing permission cannot start sharing`() {
        val state = OwnerHomeUiState(
            shares = listOf(
                ShareRowUi(
                    id = ShareId("photos"),
                    label = "Photos",
                    enabled = true,
                    health = ShareHealth.PermissionMissing,
                ),
            ),
            sharesLoadState = SharesLoadState.Ready,
        )

        assertFalse(state.canStart)
        assertEquals(0, state.enabledShareCount)
    }

    @Test
    fun `running state does not reveal the pin in diagnostic output`() {
        val pin = RunPinGenerator(RandomNumberSource { 123_456 }).generate()
        val state = OwnerHomeUiState(
            sharesLoadState = SharesLoadState.Ready,
            serverStatus = ServerStatus.Running(
                address = "192.168.1.4",
                port = 12_345,
                pin = pin,
            ),
        )

        assertFalse(state.toString().contains("123456"))
        assertTrue(state.toString().contains("redacted"))
    }
}
