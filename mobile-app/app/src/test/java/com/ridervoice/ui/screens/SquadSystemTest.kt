package com.ridervoice.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ridervoice.data.repository.SquadRepository
import com.ridervoice.data.repository.SquadSyncBus
import com.ridervoice.data.repository.SquadSyncEvent
import com.ridervoice.models.AcceptFriendRequestBody
import com.ridervoice.models.DeclineFriendRequestBody
import com.ridervoice.models.Friend
import com.ridervoice.models.FriendRequest
import com.ridervoice.models.IncomingSquadRequest
import com.ridervoice.models.ProfileResponse
import com.ridervoice.models.RiderRelation
import com.ridervoice.models.RiderSearchState
import com.ridervoice.models.SentSquadRequest
import com.ridervoice.models.SentSquadRequestDto
import com.ridervoice.network.ApiService
import com.ridervoice.security.SecurePreferences
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.components.AddFriendDialog
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.viewmodels.SquadViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class SquadSystemTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun singletonRepository_cachesAcrossViewModelRecreation_noReloadOnTabSwitch() = runTest(testDispatcher) {
        val api = mockk<ApiService>(relaxed = true)
        val friends = listOf(
            Friend(id = "u2", handle = "chandu", displayName = "Chandu", bikeModel = "MH-19")
        )
        coEvery { api.getFriendsList("u1") } returns Response.success(friends)
        coEvery { api.getPendingFriendRequests() } returns Response.success(emptyList())
        coEvery { api.getSentFriendRequests() } returns Response.success(emptyList())

        val repo = SquadRepository(api, SecurePreferences(null)) { 1_000_000L }
        val bus = SquadSyncBus()
        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)

        // First visit to My Squad loads once
        val vm1 = SquadViewModel(repo, rideRepo, bus)
        vm1.ensureLoaded("u1")
        advanceUntilIdle()
        assertEquals(1, vm1.uiState.value.friends.size)
        assertFalse(vm1.uiState.value.isLoading)

        // Simulate switching tabs away and back -> brand-new ViewModel instance
        val vm2 = SquadViewModel(repo, rideRepo, bus)
        assertFalse("Returning to My Squad must NOT flash loading spinner", vm2.uiState.value.isLoading)
        assertEquals(1, vm2.uiState.value.friends.size)
        vm2.ensureLoaded("u1")
        advanceUntilIdle()

        // Must NOT re-fetch friends on tab switch
        coVerify(exactly = 1) { api.getFriendsList("u1") }
    }

    @Test
    fun removeFriend_callsBackendDelete_andRollsBackOnError() = runTest(testDispatcher) {
        val api = mockk<ApiService>(relaxed = true)
        val friend = Friend(id = "u2", handle = "chandu", displayName = "Chandu", bikeModel = null)
        coEvery { api.getFriendsList("u1") } returns Response.success(listOf(friend))
        coEvery { api.getPendingFriendRequests() } returns Response.success(emptyList())
        coEvery { api.getSentFriendRequests() } returns Response.success(emptyList())
        coEvery { api.removeFriend("u2") } returns Response.error(
            500,
            """{"error":"Server error"}""".toResponseBody("application/json".toMediaType())
        )

        val repo = SquadRepository(api, SecurePreferences(null)) { 1_000_000L }
        val vm = SquadViewModel(repo, mockk(relaxed = true), SquadSyncBus())
        vm.ensureLoaded("u1")
        advanceUntilIdle()

        // Failing delete must roll back friend into list
        vm.removeFriend("u1", "u2")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.friends.size)
        coVerify(exactly = 1) { api.removeFriend("u2") }

        // Successful delete removes friend permanently
        coEvery { api.removeFriend("u2") } returns Response.success(Unit)
        vm.removeFriend("u1", "u2")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.friends.isEmpty())
    }

    @Test
    fun rejectedRequests_autoExpireAfterSevenDays_andLiveBusUpdatesState() = runTest(testDispatcher) {
        val dayMs = 24L * 60L * 60L * 1000L
        var now = 100L * dayMs
        val api = mockk<ApiService>(relaxed = true)
        coEvery { api.getFriendsList("u1") } returns Response.success(emptyList())
        coEvery { api.getPendingFriendRequests() } returns Response.success(emptyList())
        coEvery { api.getSentFriendRequests() } returns Response.error(
            404,
            "not found".toResponseBody("text/plain".toMediaType())
        )
        coEvery { api.sendFriendRequest(any()) } returns Response.success(
            mapOf("id" to "req-1", "status" to "PENDING", "addresseeId" to "u9")
        )

        val repo = SquadRepository(api, SecurePreferences(null)) { now }
        val bus = SquadSyncBus()
        val vm = SquadViewModel(repo, mockk(relaxed = true), bus)
        vm.ensureLoaded("u1")
        advanceUntilIdle()

        // Send request -> lands in sentRequests as PENDING
        val target = ProfileResponse(id = "u9", handle = "ravi", displayName = "Ravi", bikeModel = "Himalayan")
        vm.sendSquadRequest("u1", target)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.sentRequests.size)
        assertEquals("PENDING", vm.uiState.value.sentRequests.first().status)

        // Live bus emits rejection -> flips to REJECTED
        bus.emit(SquadSyncEvent.RequestRejected("ravi"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sentRequests.first().isRejected)

        // Advance clock past 7 days -> auto-purged!
        now += (8L * dayMs)
        repo.purgeExpiredRejectedInState("u1")
        advanceUntilIdle()
        assertTrue("Rejected request older than 7 days must be auto-deleted", vm.uiState.value.sentRequests.isEmpty())
    }

    @Test
    fun addFriendDialog_showsAlreadyInSquadBadge_orGreenPersonAddIcon() {
        val memberProfile = ProfileResponse(id = "u2", handle = "chandu", displayName = "Chandu", bikeModel = "MH-19")
        composeRule.setContent {
            RiderVoiceTheme {
                AddFriendDialog(
                    onDismiss = {},
                    searchState = RiderSearchState.Found(memberProfile, RiderRelation.ALREADY_IN_SQUAD),
                    onSearchQueryChange = { _, _ -> },
                    onSendRequestToProfile = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("add_rider_already_in_squad_badge", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Already in your Squad", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("add_rider_send_icon_btn", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun addFriendDialog_availableRider_showsGreenIconButtonAndSends() {
        var sentProfile: ProfileResponse? = null
        val newRider = ProfileResponse(id = "u5", handle = "arjun", displayName = "Arjun", bikeModel = "Duke 390")
        composeRule.setContent {
            RiderVoiceTheme {
                AddFriendDialog(
                    onDismiss = {},
                    searchState = RiderSearchState.Found(newRider, RiderRelation.AVAILABLE),
                    onSearchQueryChange = { _, _ -> },
                    onSendRequestToProfile = { sentProfile = it }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("add_rider_send_icon_btn", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        assertEquals("u5", sentProfile?.id)
    }

    @Test
    fun squadRequestsScreen_rendersIncomingRequestWithAcceptAndDeclineIcons() = runTest(testDispatcher) {
        val api = mockk<ApiService>(relaxed = true)
        val req = IncomingSquadRequest(
            id = "inc-1",
            requesterId = "u7",
            status = "PENDING",
            requester = Friend(id = "u7", handle = "kabir", displayName = "Kabir", bikeModel = "Interceptor 650")
        )
        coEvery { api.getFriendsList("u1") } returns Response.success(emptyList())
        coEvery { api.getPendingFriendRequests() } returns Response.success(listOf(req))
        coEvery { api.getSentFriendRequests() } returns Response.success(emptyList())
        coEvery { api.acceptFriendRequest(AcceptFriendRequestBody("u7")) } returns Response.success(Unit)
        coEvery { api.declineFriendRequest(DeclineFriendRequestBody("u7", "inc-1")) } returns Response.success(Unit)

        val repo = SquadRepository(api, SecurePreferences(null)) { 1_000_000L }
        repo.refreshAll("u1")
        val vm = SquadViewModel(repo, mockk(relaxed = true), SquadSyncBus())

        composeRule.setContent {
            RiderVoiceTheme {
                SquadRequestsScreen(
                    onBackClick = {},
                    viewModel = vm
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Requests").assertIsDisplayed()
        composeRule.onNodeWithText("Kabir").assertIsDisplayed()
        composeRule.onNodeWithTag("request_accept_inc-1").assertIsDisplayed()
        composeRule.onNodeWithTag("request_decline_inc-1").assertIsDisplayed()
    }

    @Test
    fun squadScreen_omitsSearchAndRefresh_andConditionallyShowsRequestsFabAndPendingDivider() = runTest(testDispatcher) {
        val api = mockk<ApiService>(relaxed = true)
        val sentPending = SentSquadRequestDto(
            id = "sent-1",
            addresseeId = "u8",
            status = "PENDING",
            addressee = Friend(id = "u8", handle = "meera", displayName = "Meera", bikeModel = null)
        )
        val sentRejected = SentSquadRequestDto(
            id = "sent-2",
            addresseeId = "u9",
            status = "REJECTED",
            addressee = Friend(id = "u9", handle = "vikram", displayName = "Vikram", bikeModel = null)
        )
        coEvery { api.getFriendsList("u1") } returns Response.success(
            listOf(Friend(id = "u2", handle = "chandu", displayName = "Chandu", bikeModel = null))
        )
        coEvery { api.getPendingFriendRequests() } returns Response.success(emptyList())
        coEvery { api.getSentFriendRequests() } returns Response.success(listOf(sentPending, sentRejected))

        val repo = SquadRepository(api, SecurePreferences(null)) { 1_000_000L }
        repo.refreshAll("u1")
        val vm = SquadViewModel(repo, mockk(relaxed = true), SquadSyncBus())

        // Verify empty incoming requests -> Requests FAB does NOT exist
        assertTrue(vm.uiState.value.incomingRequests.isEmpty())
        assertEquals(2, vm.uiState.value.sentRequests.size)
        assertEquals("PENDING", vm.uiState.value.sentRequests[0].status)
        assertTrue(vm.uiState.value.sentRequests[1].isRejected)

        // Verify dismissSentRequest removes rejected entry
        vm.dismissSentRequest("u1", "sent-2")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.sentRequests.size)

        // Verify Search bar and Refresh button are absent from SquadScreen
        composeRule.setContent {
            RiderVoiceTheme {
                SquadScreen(
                    onBackClick = {},
                    viewModel = vm
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Filter squad by @handle").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Refresh Squad").assertDoesNotExist()
        composeRule.onNodeWithTag("squad_requests_fab").assertDoesNotExist()
        composeRule.onNodeWithTag("squad_add_rider_button").assertIsDisplayed()
    }
}
