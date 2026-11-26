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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.data.local.entities.RideSessionEntity
import com.ridervoice.models.Friend
import com.ridervoice.models.InviterDetails
import com.ridervoice.models.LobbyStatus
import com.ridervoice.models.RideInvite
import com.ridervoice.models.RoomDetails
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.components.RvStateHost
import com.ridervoice.ui.components.UiLoadState
import com.ridervoice.ui.screens.HomeScreen
import com.ridervoice.ui.screens.InviteFriendsContent
import com.ridervoice.ui.screens.InvitesInboxScreen
import com.ridervoice.ui.screens.LobbyScreen
import com.ridervoice.ui.screens.RideReplayScreen
import com.ridervoice.ui.screens.RideStatsScreen
import com.ridervoice.ui.screens.RoutePlannerScreen
import com.ridervoice.ui.screens.SquadScreen
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.viewmodels.HomeState
import com.ridervoice.ui.viewmodels.HomeViewModel
import com.ridervoice.ui.viewmodels.InviteFriendsViewModel
import com.ridervoice.ui.viewmodels.InvitesInboxViewModel
import com.ridervoice.ui.viewmodels.LobbyLoadState
import com.ridervoice.ui.viewmodels.LobbyViewModel
import com.ridervoice.ui.viewmodels.NearbyRider
import com.ridervoice.ui.viewmodels.RideStatsState
import com.ridervoice.ui.viewmodels.RideStatsViewModel
import com.ridervoice.ui.viewmodels.RoutePlannerState
import com.ridervoice.ui.viewmodels.RoutePlannerViewModel
import com.ridervoice.ui.viewmodels.SquadUiState
import com.ridervoice.ui.viewmodels.SquadViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

    @Test
    fun homeScreen_transitionsLoadingWithoutEmptyFlash_errorRetry_emptyAndContent() {
        val homeFlow = MutableStateFlow(HomeState())
        val vm = mockk<HomeViewModel>(relaxed = true) {
            every { uiState } returns homeFlow
        }
        var isLoading by mutableStateOf(true)
        var errorMsg by mutableStateOf<String?>(null)
        var retryCount = 0

        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        every { mockAuth.currentUser } returns null
        mockkStatic(FirebaseAuth::class)
        try {
            every { FirebaseAuth.getInstance() } returns mockAuth
            composeRule.setContent {
                RiderVoiceTheme {
                    HomeScreen(
                        viewModel = vm,
                        onHostRideClick = {},
                        onJoinRideClick = {},
                        onStartRideClick = {},
                        onSquadClick = {},
                        onSettingsClick = {},
                        onRoutePlannerClick = {},
                        onRideHistoryClick = {},
                        onDeviceSetupClick = {},
                        isLoading = isLoading,
                        errorMessage = errorMsg,
                        onRetry = { retryCount++ }
                    )
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()
            composeRule.onNodeWithTag("rv_state_empty").assertDoesNotExist()

            composeRule.runOnUiThread {
                isLoading = false
                errorMsg = "Sync failed"
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
            composeRule.onNodeWithText("Retry").performClick()
            assertEquals(1, retryCount)

            composeRule.runOnUiThread { errorMsg = null }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("rv_state_empty").assertIsDisplayed()

            composeRule.runOnUiThread {
                homeFlow.value = HomeState(hasActiveRide = true, activeRideName = "Konkan Trail")
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Konkan Trail").assertIsDisplayed()
        } finally {
            unmockkStatic(FirebaseAuth::class)
        }
    }

    @Test
    fun squadScreen_transitionsLoadingWithoutEmptyFlash_errorRetry_emptyAndContent() {
        val squadFlow = MutableStateFlow(SquadUiState(isLoading = true, hasLoadedOnce = false))
        val vm = mockk<SquadViewModel>(relaxed = true) {
            every { uiState } returns squadFlow
        }
        val mockUser = mockk<FirebaseUser>(relaxed = true) { every { uid } returns "me" }
        val mockAuth = mockk<FirebaseAuth>(relaxed = true) { every { currentUser } returns mockUser }
        mockkStatic(FirebaseAuth::class)
        try {
            every { FirebaseAuth.getInstance() } returns mockAuth
            composeRule.setContent {
                RiderVoiceTheme {
                    SquadScreen(onBackClick = {}, viewModel = vm)
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()
            composeRule.onNodeWithTag("rv_state_empty").assertDoesNotExist()

            composeRule.runOnUiThread {
                squadFlow.value = SquadUiState(isLoading = false, hasLoadedOnce = true, errorMessage = "Squad fetch error")
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
            composeRule.onNodeWithText("Retry").performClick()
            verify(atLeast = 1) { vm.pullToRefresh("me") }

            composeRule.runOnUiThread {
                squadFlow.value = SquadUiState(isLoading = false, hasLoadedOnce = true, friends = emptyList())
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("rv_state_empty").assertIsDisplayed()

            composeRule.runOnUiThread {
                squadFlow.value = SquadUiState(
                    isLoading = false,
                    hasLoadedOnce = true,
                    friends = listOf(Friend(id = "f1", handle = "asha", displayName = "Asha", bikeModel = "Himalayan"))
                )
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Asha").assertIsDisplayed()
        } finally {
            unmockkStatic(FirebaseAuth::class)
        }
    }

    @Test
    fun lobbyScreen_transitionsLoadingErrorRetryAndEmptyJoinedRiders() {
        val loadFlow = MutableStateFlow<LobbyLoadState>(LobbyLoadState.Loading)
        val statusFlow = MutableStateFlow<LobbyStatus?>(null)
        val vm = mockk<LobbyViewModel>(relaxed = true) {
            every { loadState } returns loadFlow
            every { lobbyStatus } returns statusFlow
            every { activeRideState } returns MutableStateFlow<ActiveRideRepository.ActiveRide?>(null)
            every { error } returns MutableStateFlow<String?>(null)
            every { terminal } returns MutableStateFlow<LobbyViewModel.Terminal?>(null)
        }
        val homeVm = mockk<HomeViewModel>(relaxed = true) {
            every { uiState } returns MutableStateFlow(HomeState())
        }

        composeRule.setContent {
            RiderVoiceTheme {
                LobbyScreen(
                    convoyName = "Konkan",
                    viewModel = vm,
                    homeViewModel = homeVm,
                    onStartRide = {},
                    onBackClick = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("rv_state_empty").assertDoesNotExist()

        composeRule.runOnUiThread {
            loadFlow.value = LobbyLoadState.Failed(reason = "Lobby timed out", isRetryable = true)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        verify(atLeast = 1) { vm.retryPolling("Konkan") }

        composeRule.runOnUiThread {
            val emptyParticipantStatus = LobbyStatus(
                roomId = "r1",
                convoyName = "Konkan",
                isHost = false,
                invites = emptyList()
            )
            loadFlow.value = LobbyLoadState.Ready(emptyParticipantStatus)
            statusFlow.value = emptyParticipantStatus
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_empty").assertIsDisplayed()
    }

    @Test
    fun rideStatsScreen_transitionsLoadingWithoutEmptyFlash_errorRetry_emptyAndContent() {
        val statsFlow = MutableStateFlow(RideStatsState())
        val vm = mockk<RideStatsViewModel>(relaxed = true) {
            every { uiState } returns statsFlow
        }
        var isLoading by mutableStateOf(true)
        var errorMsg by mutableStateOf<String?>(null)
        var retryCount = 0

        composeRule.setContent {
            RiderVoiceTheme {
                RideStatsScreen(
                    viewModel = vm,
                    onBackClick = {},
                    isLoading = isLoading,
                    errorMessage = errorMsg,
                    onRetry = { retryCount++ }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("rv_state_empty").assertDoesNotExist()

        composeRule.runOnUiThread {
            isLoading = false
            errorMsg = "History load error"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retryCount)

        composeRule.runOnUiThread { errorMsg = null }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_empty").assertIsDisplayed()

        composeRule.runOnUiThread {
            val now = 1_763_727_120_000L
            statsFlow.value = RideStatsState(
                totalRidesCount = 1,
                sessions = listOf(
                    RideSessionEntity(
                        id = "s-1",
                        roomName = "Ghat Run",
                        startTime = now,
                        endTime = now + 60_000L,
                        totalDistanceMeters = 12_000f
                    )
                )
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ghat Run").assertIsDisplayed()
    }

    @Test
    fun rideReplayScreen_transitionsLoadingWithoutEmptyFlash_errorRetry_emptyAndContent() {
        var isLoading by mutableStateOf(true)
        var errorMsg by mutableStateOf<String?>(null)
        var waypoints by mutableStateOf<List<RawWaypointEntity>>(emptyList())
        var retryCount = 0

        composeRule.setContent {
            RiderVoiceTheme {
                RideReplayScreen(
                    rideId = "r-1",
                    waypoints = waypoints,
                    onBack = {},
                    errorMessage = errorMsg,
                    isLoading = isLoading,
                    onRetry = { retryCount++ }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("rv_state_empty").assertDoesNotExist()

        composeRule.runOnUiThread {
            isLoading = false
            errorMsg = "Corrupt track"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retryCount)

        composeRule.runOnUiThread {
            errorMsg = null
            waypoints = listOf(
                RawWaypointEntity(sessionId = "r-1", lat = 18.52, lng = 73.85, speedMps = 12f, heading = 90f, timestamp = 1000L),
                RawWaypointEntity(sessionId = "r-1", lat = 18.53, lng = 73.86, speedMps = 14f, heading = 95f, timestamp = 2000L)
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No GPS track recorded").assertDoesNotExist()
    }

    @Test
    fun routePlannerScreen_transitionsLoadingWithoutEmptyFlash_errorRetry_emptyAndContent() {
        val routeFlow = MutableStateFlow(RoutePlannerState())
        val vm = mockk<RoutePlannerViewModel>(relaxed = true) {
            every { uiState } returns routeFlow
        }
        var isLoading by mutableStateOf(true)
        var errorMsg by mutableStateOf<String?>(null)
        var retryCount = 0

        composeRule.setContent {
            RiderVoiceTheme {
                RoutePlannerScreen(
                    viewModel = vm,
                    onBackClick = {},
                    isLoading = isLoading,
                    errorMessage = errorMsg,
                    onRetry = { retryCount++ }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("rv_state_empty").assertDoesNotExist()

        composeRule.runOnUiThread {
            isLoading = false
            errorMsg = "Route unavailable"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retryCount)

        composeRule.runOnUiThread { errorMsg = null }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_empty").assertIsDisplayed()

        composeRule.runOnUiThread {
            routeFlow.value = RoutePlannerState(
                nearbyRiders = listOf(NearbyRider(handle = "arjun", distanceKm = "3.2"))
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("@arjun").assertIsDisplayed()
    }
}
