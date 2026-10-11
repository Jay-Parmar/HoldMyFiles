package dev.jay.holdmyfiles.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.jay.holdmyfiles.core.ServerStatus
import dev.jay.holdmyfiles.core.security.RandomNumberSource
import dev.jay.holdmyfiles.core.security.RunPinGenerator
import dev.jay.holdmyfiles.core.storage.ShareId
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

    @Test
    fun configuredFolderCardSupportsSharingAndRemoval() {
        val shareId = ShareId("travel")
        val receivedActions = mutableListOf<HomeAction>()
        composeRule.setContent {
            HoldMyFilesTheme {
                OwnerHomeScreen(
                    state = OwnerHomeUiState(
                        shares = listOf(
                            ShareRowUi(
                                id = shareId,
                                label = "Travel photos",
                                enabled = true,
                            ),
                        ),
                        sharesLoadState = SharesLoadState.Ready,
                    ),
                    onAction = receivedActions::add,
                )
            }
        }

        composeRule.onNodeWithText("1 FOLDER").assertIsDisplayed()
        composeRule.onNodeWithTag("share-toggle-travel").performClick()
        composeRule.onNodeWithText("Remove Travel photos").performClick()

        assertEquals(
            listOf(
                HomeAction.SetEnabled(shareId, false),
                HomeAction.RequestRemove(shareId),
            ),
            receivedActions,
        )
    }

    @Test
    fun runningDashboardKeepsAccessDetailsActionable() {
        val shareId = ShareId("travel")
        val pin = RunPinGenerator(RandomNumberSource { 123_456 }).generate()
        val receivedActions = mutableListOf<HomeAction>()
        composeRule.setContent {
            HoldMyFilesTheme {
                OwnerHomeScreen(
                    state = OwnerHomeUiState(
                        shares = listOf(
                            ShareRowUi(
                                id = shareId,
                                label = "Travel photos",
                                enabled = true,
                            ),
                        ),
                        sharesLoadState = SharesLoadState.Ready,
                        serverStatus = ServerStatus.Running(
                            address = "192.168.1.4",
                            port = 12_345,
                            pin = pin,
                        ),
                    ),
                    onAction = receivedActions::add,
                )
            }
        }

        composeRule.onNodeWithTag("live-sharing-panel").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Sharing PIN 1 2 3 4 5 6"))
            .assertExists()
        listOf(
            "Copy URL" to HomeAction.CopyUrl,
            "Copy PIN" to HomeAction.CopyPin,
            "Share access details" to HomeAction.ShareAccessDetails,
            "Stop sharing" to HomeAction.StopSharing,
        ).forEach { (label, action) ->
            composeRule.onNodeWithText(label).performScrollTo().performClick()
            assertEquals(action, receivedActions.last())
        }
    }
}
