package com.ridervoice.ui.viewmodels

import com.ridervoice.models.*
import com.ridervoice.network.ApiService
import com.ridervoice.security.SecurePreferences
import com.ridervoice.state.ActiveRideRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class HostSetupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeApiService : ApiService {
        var createCalls = 0
        var createRequestCaptured: ConvoyCreateRequest? = null
        var createResponse: Response<ConvoyCreateResponse> = Response.success(
            ConvoyCreateResponse(
                roomId = "room-uuid-1",
                convoyName = "Sunday Ghat Ride",
                hostId = "host-user-1",
                joinCode = "RIDE123"
            )
        )

        override suspend fun createConvoy(body: ConvoyCreateRequest): Response<ConvoyCreateResponse> {
            createCalls++
            createRequestCaptured = body
            return createResponse
        }

        override suspend fun updateFcmToken(body: FcmTokenRequest): Response<Any> = Response.success(Unit)
        override suspend fun upsertProfile(body: ProfileRequest): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun getMyProfile(): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun searchByHandle(handle: String): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun getFriendsList(userId: String): Response<List<Friend>> = throw NotImplementedError()
        override suspend fun sendFriendRequest(body: FriendRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getPendingInvites(userId: String): Response<List<RideInvite>> = throw NotImplementedError()
        override suspend fun respondToInvite(body: InviteRespondRequest): Response<Any> = throw NotImplementedError()
        override suspend fun sendRideInvite(body: SendInviteRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getLobbyStatus(roomName: String): Response<LobbyStatus> = throw NotImplementedError()
        override suspend fun startRide(roomName: String): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getJoinToken(body: JoinTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun generateShareLink(roomName: String): Response<ShareLinkResponse> = throw NotImplementedError()
        override suspend fun joinViaToken(body: JoinViaTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun removeRiderFromConvoy(roomName: String, userId: String): Response<Any> = throw NotImplementedError()
        override suspend fun endRideForEveryone(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun transferHost(roomName: String, body: TransferHostRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getRoomToken(body: RoomTokenRequest): Response<RoomData> = throw NotImplementedError()
        override suspend fun sendSosAlert(body: SosAlertRequest): Response<SosAlertResponse> = throw NotImplementedError()
        override suspend fun cancelEmergencyAlert(body: CancelAlertRequest): Response<CancelAlertResponse> = throw NotImplementedError()
        override suspend fun syncRide(body: SyncRideRequest): Response<SyncRideResponse> = throw NotImplementedError()
        override suspend fun getRideHistory(): Response<List<RideHistoryResponse>> = throw NotImplementedError()
        override suspend fun finalizeDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun getLatestDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun refreshToken(body: RefreshTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun joinLobbyUnified(body: LobbyJoinRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun leaveLobby(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun acceptRideInvite(id: String): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getAppConfig(): Response<AppConfigResponse> = throw NotImplementedError()
        override suspend fun deleteFcmToken(token: String?): Response<Any> = throw NotImplementedError()
    }

    @Test
    fun getOrCreateClientRequestId_persistsAndReusesIdUntilCleared() {
        val prefs = SecurePreferences(context = null)
        val api = FakeApiService()
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = HostSetupViewModel(api, prefs, repo)

        val id1 = vm.getOrCreateClientRequestId()
        assertFalse(id1.isBlank())

        val id2 = vm.getOrCreateClientRequestId()
        assertEquals("Subsequent call must return the same clientRequestId", id1, id2)

        vm.clearPendingAttempt()
        val id3 = vm.getOrCreateClientRequestId()
        assertFalse("New ID must be generated after clearing pending attempt", id1 == id3)
    }

    @Test
    fun createConvoy_success_transitionsToSucceededAndEntersLobby() = runTest(testDispatcher) {
        val prefs = SecurePreferences(context = null)
        val api = FakeApiService()
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = HostSetupViewModel(api, prefs, repo)

        vm.createConvoy(
            convoyName = "Sunday Ghat Ride",
            origin = "Pune",
            destination = "Lonavala",
            meetupPoint = "Chandani Chowk"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.createState.value
        assertTrue("State must be Succeeded, was $state", state is HostSetupViewModel.CreateRideState.Succeeded)
        assertEquals("Sunday Ghat Ride", (state as HostSetupViewModel.CreateRideState.Succeeded).convoyName)
        assertEquals("Sunday Ghat Ride", vm.createdConvoyName.value)
        assertEquals(1, api.createCalls)

        // Verify active ride repository lobby entry
        val rideState = repo.state.value
        assertNotNull(rideState)
        assertEquals("room-uuid-1", rideState?.roomId)
        assertEquals("Sunday Ghat Ride", rideState?.displayName)
        assertTrue(rideState?.isHost == true)
        assertEquals("RIDE123", rideState?.joinCode)

        // Verify pending attempt was cleared on success
        val pendingId = prefs.getString(HostSetupViewModel.KEY_PENDING_CREATE_REQUEST_ID, "")
        assertEquals("", pendingId)
    }

    @Test
    fun createConvoy_singleFlight_rejectsConcurrentDuplicateCalls() = runTest(testDispatcher) {
        val prefs = SecurePreferences(context = null)
        val api = FakeApiService()
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = HostSetupViewModel(api, prefs, repo)

        // Call 1
        vm.createConvoy(
            convoyName = "Sunday Ghat Ride",
            origin = "Pune",
            destination = "Lonavala",
            meetupPoint = null
        )

        // Concurrent Call 2 before scheduler advances Call 1
        vm.createConvoy(
            convoyName = "Sunday Ghat Ride",
            origin = "Pune",
            destination = "Lonavala",
            meetupPoint = null
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Only one API request should have been dispatched", 1, api.createCalls)
    }

    @Test
    fun createConvoy_conflict_transitionsToConflictState() = runTest(testDispatcher) {
        val prefs = SecurePreferences(context = null)
        val api = FakeApiService().apply {
            val errBody = """{"error":"A room with this name already exists"}"""
                .toResponseBody("application/json".toMediaType())
            createResponse = Response.error(409, errBody)
        }
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = HostSetupViewModel(api, prefs, repo)

        vm.createConvoy(
            convoyName = "Duplicate Ride",
            origin = null,
            destination = null,
            meetupPoint = null
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.createState.value
        assertTrue("State must be Conflict on 409, was $state", state is HostSetupViewModel.CreateRideState.Conflict)
        assertEquals("A room with this name already exists", (state as HostSetupViewModel.CreateRideState.Conflict).message)
        assertNotNull(vm.error.value)
    }

    @Test
    fun createConvoy_networkFailure_transitionsToUnknownOutcomeWithSameRequestId() = runTest(testDispatcher) {
        val prefs = SecurePreferences(context = null)
        val api = FakeApiService().apply {
            val errBody = "Internal Server Error".toResponseBody("text/plain".toMediaType())
            createResponse = Response.error(500, errBody)
        }
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = HostSetupViewModel(api, prefs, repo)

        val fixedRequestId = "client-req-uuid-test"
        vm.createConvoy(
            convoyName = "Network Glitch Ride",
            origin = null,
            destination = null,
            meetupPoint = null,
            clientRequestId = fixedRequestId
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.createState.value
        assertTrue("State must be UnknownOutcome on 500, was $state", state is HostSetupViewModel.CreateRideState.UnknownOutcome)
        val outcome = state as HostSetupViewModel.CreateRideState.UnknownOutcome
        assertEquals(fixedRequestId, outcome.clientRequestId)
    }
}
