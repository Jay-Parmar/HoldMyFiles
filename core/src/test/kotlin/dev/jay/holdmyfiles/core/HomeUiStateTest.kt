package dev.jay.holdmyfiles.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun `server cannot start without an enabled share`() {
        val state = HomeUiState(enabledShareCount = 0)

        assertFalse(state.canStart)
    }

    @Test
    fun `server can start when a share is enabled`() {
        val state = HomeUiState(enabledShareCount = 1)

        assertTrue(state.canStart)
    }

    @Test
    fun `running server can stop but cannot start again`() {
        val state = HomeUiState(
            enabledShareCount = 1,
            serverStatus = ServerStatus.Running(
                address = "192.168.1.4",
                port = 8080,
                pin = "123456",
            ),
        )

        assertFalse(state.canStart)
        assertTrue(state.canStop)
    }
}
