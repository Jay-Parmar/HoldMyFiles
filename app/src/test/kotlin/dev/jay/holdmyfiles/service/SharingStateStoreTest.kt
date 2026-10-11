package dev.jay.holdmyfiles.service

import dev.jay.holdmyfiles.core.ServerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingStateStoreTest {
    @Test
    fun `start transition is immediate and idempotent`() {
        val store = SharingStateStore()

        assertTrue(store.beginStart())
        assertEquals(ServerStatus.Starting, store.status.value)
        assertFalse(store.beginStart())
        assertEquals(ServerStatus.Starting, store.status.value)
    }

    @Test
    fun `a cleaned failed run can start again`() {
        val store = SharingStateStore(ServerStatus.Failed("Safe message"))

        assertTrue(store.beginStart())
        assertEquals(ServerStatus.Starting, store.status.value)
    }
}
