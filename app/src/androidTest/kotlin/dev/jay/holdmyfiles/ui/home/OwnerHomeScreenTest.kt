package dev.jay.holdmyfiles.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.jay.holdmyfiles.ui.theme.HoldMyFilesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OwnerHomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stoppedDashboardGuidesTheFirstFolderSelection() {
        var receivedAction: HomeAction? = null
        composeRule.setContent {
            HoldMyFilesTheme {
                OwnerHomeScreen(
                    state = OwnerHomeUiState(sharesLoadState = SharesLoadState.Ready),
                    onAction = { action -> receivedAction = action },
                )
            }
        }

        composeRule.onNodeWithText("OFFLINE").assertIsDisplayed()
        composeRule.onNodeWithText("Start sharing").assertIsNotEnabled()
        composeRule.onNodeWithText("Choose a folder").performClick()

        assertEquals(HomeAction.AddFolder, receivedAction)
    }
}
