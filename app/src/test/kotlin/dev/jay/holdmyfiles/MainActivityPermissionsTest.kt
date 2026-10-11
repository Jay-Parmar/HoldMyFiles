package dev.jay.holdmyfiles

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityPermissionsTest {
    @Test
    fun `notification permission is requested starting on API 33`() {
        val permissions = missingStartPermissions(sdkInt = 33) { false }

        assertEquals(
            listOf("android.permission.POST_NOTIFICATIONS"),
            permissions,
        )
    }

    @Test
    fun `local network permission is not requested before API 37`() {
        val permissions = missingStartPermissions(sdkInt = 36) { false }

        assertEquals(
            listOf("android.permission.POST_NOTIFICATIONS"),
            permissions,
        )
    }

    @Test
    fun `local network permission is requested on API 37`() {
        val permissions = missingStartPermissions(sdkInt = 37) { false }

        assertEquals(
            listOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.ACCESS_LOCAL_NETWORK",
            ),
            permissions,
        )
    }
}
