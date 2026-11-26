package com.ridervoice.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ridervoice.models.Friend
import com.ridervoice.models.InviterDetails
import com.ridervoice.models.RideInvite
import com.ridervoice.models.RoomDetails
import com.ridervoice.ui.components.RvStateHost
import com.ridervoice.ui.components.UiLoadState
import com.ridervoice.ui.screens.InviteFriendsContent
import com.ridervoice.ui.screens.InvitesInboxScreen
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.viewmodels.InviteFriendsViewModel
import com.ridervoice.ui.viewmodels.InvitesInboxViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class UnifiedLoadStatesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rvStateHost_rendersLoadingEmptyErrorAndContentAndRetryCallbackFires() {
        var state by mutableStateOf<UiLoadState<List<String>>>(UiLoadState.Loading)
        var actionCount = 0
        var retryCount = 0

        composeRule.setContent {
            RiderVoiceTheme {
                RvStateHost(state = state) { items ->
                    Text(items.joinToString(", "))
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()

        composeRule.runOnUiThread {
            state = UiLoadState.Empty(
                title = "Nothing yet",
                body = "Add riders to start",
                actionText = "Add",
                onAction = { actionCount++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_empty").assertIsDisplayed()
        composeRule.onNodeWithText("Add").performClick()
        assertEquals(1, actionCount)

        composeRule.runOnUiThread {
            state = UiLoadState.Error(
                message = "Connection timed out",
                onRetry = { retryCount++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
        composeRule.onNodeWithText("Connection timed out").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retryCount)

        composeRule.runOnUiThread {
            state = UiLoadState.Content(listOf("Alpha", "Bravo"))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Alpha, Bravo").assertIsDisplayed()
    }

    @Test
    fun invitesInboxScreen_transitionsThroughLoadingErrorEmptyAndContent() {
        val invitesFlow = MutableStateFlow<List<RideInvite>>(emptyList())
        val isLoadingFlow = MutableStateFlow(true)
        val errorFlow = MutableStateFlow<String?>(null)
        val vm = mockk<InvitesInboxViewModel>(relaxed = true) {
            every { invites } returns invitesFlow
            every { isLoading } returns isLoadingFlow
            every { error } returns errorFlow
        }

        composeRule.setContent {
            RiderVoiceTheme {
                InvitesInboxScreen(
                    viewModel = vm,
                    onInviteAccepted = {},
                    onBackClick = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()

        composeRule.runOnUiThread {
            isLoadingFlow.value = false
            errorFlow.value = "Network unreachable"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        verify(atLeast = 2) { vm.loadInvites() }

        composeRule.runOnUiThread {
            errorFlow.value = null
            invitesFlow.value = listOf(
                RideInvite(
                    id = "inv-1",
                    roomId = "room-1",
                    inviterId = "s-1",
                    inviteeId = "me",
                    status = "PENDING",
                    inviter = InviterDetails(handle = "rohan", displayName = "Rohan"),
                    room = RoomDetails(name = "Coastal Run")
                )
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Coastal Run").assertIsDisplayed()
    }

    @Test
    fun inviteFriendsPanel_transitionsThroughLoadingErrorEmptyAndContent() {
        val friendsFlow = MutableStateFlow<List<Friend>>(emptyList())
        val isLoadingFlow = MutableStateFlow(true)
        val errorFlow = MutableStateFlow<String?>(null)
        val vm = mockk<InviteFriendsViewModel>(relaxed = true) {
            every { friends } returns friendsFlow
            every { isLoading } returns isLoadingFlow
            every { error } returns errorFlow
        }

        composeRule.setContent {
            RiderVoiceTheme {
                InviteFriendsContent(
                    convoyName = "Coastal Run",
                    viewModel = vm
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()

        composeRule.runOnUiThread {
            isLoadingFlow.value = false
            errorFlow.value = "Squad unavailable"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        verify(atLeast = 2) { vm.loadFriends() }

        composeRule.runOnUiThread {
            errorFlow.value = null
            friendsFlow.value = listOf(
                Friend(id = "f-1", handle = "vikram", displayName = "Vikram", bikeModel = "Himalayan")
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Vikram").assertIsDisplayed()
    }
}
