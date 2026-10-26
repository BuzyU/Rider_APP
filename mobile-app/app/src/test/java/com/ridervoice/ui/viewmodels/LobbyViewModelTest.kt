package com.ridervoice.ui.viewmodels

import com.ridervoice.models.*
import com.ridervoice.network.ApiService
import com.ridervoice.state.ActiveRideRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class LobbyViewModelTest {

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
        var statusCalls = 0
        var statusResponses: MutableList<() -> Response<LobbyStatus>> = mutableListOf()
        var defaultStatusResponse: () -> Response<LobbyStatus> = {
            Response.success(
                LobbyStatus(
                    roomId = "room-test-1",
                    convoyName = "Sunday Ride",
                    isHost = true
                )
            )
        }

        override suspend fun getLobbyStatus(roomName: String): Response<LobbyStatus> {
            statusCalls++
            return if (statusResponses.isNotEmpty()) {
                statusResponses.removeAt(0).invoke()
            } else {
                defaultStatusResponse.invoke()
            }
        }

        var startRideCalls = 0
        override suspend fun startRide(roomName: String): Response<StartRideResponse> {
            startRideCalls++
            return Response.success(
                StartRideResponse(
                    token = "token-123",
                    roomName = roomName,
                    roomId = roomName,
                    lkRoomName = "lk-$roomName",
                    livekitUrl = "wss://livekit.test"
                )
            )
        }

        var removeRiderCalls = 0
        override suspend fun removeRiderFromConvoy(roomName: String, userId: String): Response<Any> {
            removeRiderCalls++
            return Response.success(Unit)
        }

        var leaveLobbyCalls = 0
        override suspend fun leaveLobby(roomName: String): Response<Any> {
            leaveLobbyCalls++
            return Response.success(Unit)
        }

        override suspend fun createConvoy(body: ConvoyCreateRequest): Response<ConvoyCreateResponse> = throw NotImplementedError()
        override suspend fun updateFcmToken(body: FcmTokenRequest): Response<Any> = throw NotImplementedError()
        override suspend fun upsertProfile(body: ProfileRequest): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun getMyProfile(): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun searchByHandle(handle: String): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun getFriendsList(userId: String): Response<List<Friend>> = throw NotImplementedError()
        override suspend fun sendFriendRequest(body: FriendRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getPendingInvites(userId: String): Response<List<RideInvite>> = throw NotImplementedError()
        override suspend fun respondToInvite(body: InviteRespondRequest): Response<Any> = throw NotImplementedError()
        override suspend fun sendRideInvite(body: SendInviteRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getJoinToken(body: JoinTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun generateShareLink(roomName: String): Response<ShareLinkResponse> = throw NotImplementedError()
        override suspend fun joinViaToken(body: JoinViaTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
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
        override suspend fun acceptRideInvite(id: String): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getAppConfig(): Response<AppConfigResponse> = throw NotImplementedError()
        override suspend fun deleteFcmToken(token: String?): Response<Any> = throw NotImplementedError()
    }

    private fun jsonError(code: Int, body: String): Response<LobbyStatus> {
        return Response.error(
            code,
            body.toResponseBody("application/json".toMediaType())
        )
    }

    @Test
    fun startPolling_success_transitionsToReady() = runTest(testDispatcher) {
        val api = FakeApiService()
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = LobbyViewModel(api, repo)

        vm.startPolling("room-1")
        advanceTimeBy(100)

        val state = vm.loadState.value
        assertTrue("State must be Ready, was $state", state is LobbyLoadState.Ready)
        assertEquals("room-test-1", (state as LobbyLoadState.Ready).status.roomId)
        assertEquals(1, api.statusCalls)
        vm.stopPolling()
    }

    @Test
    fun startPolling_503FiveTimes_transitionsToFailedWithRetryable() = runTest(testDispatcher) {
        val api = FakeApiService()
        api.defaultStatusResponse = { jsonError(503, "{\"error\": \"Service unavailable\"}") }
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = LobbyViewModel(api, repo)

        vm.startPolling("room-1")
        advanceTimeBy(35000)

        val state = vm.loadState.value
        assertTrue("State must be Failed after 5 attempts, was $state", state is LobbyLoadState.Failed)
        val failed = state as LobbyLoadState.Failed
        assertTrue("Must be retryable", failed.isRetryable)
        assertFalse("Not schema degraded", failed.isSchemaDegraded)
        assertEquals(5, api.statusCalls)
        vm.stopPolling()
    }

    @Test
    fun startPolling_503SchemaDegraded_marksSchemaDegraded() = runTest(testDispatcher) {
        val api = FakeApiService()
        api.defaultStatusResponse = {
            jsonError(503, "{\"error\": \"Updating\", \"code\": \"SCHEMA_DEGRADED\"}")
        }
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = LobbyViewModel(api, repo)

        vm.startPolling("room-1")
        advanceTimeBy(35000)

        val state = vm.loadState.value
        assertTrue("State must be Failed, was $state", state is LobbyLoadState.Failed)
        val failed = state as LobbyLoadState.Failed
        assertTrue("Must have isSchemaDegraded=true", failed.isSchemaDegraded)
        vm.stopPolling()
    }

    @Test
    fun startPolling_500Then200_recoversToReady() = runTest(testDispatcher) {
        val api = FakeApiService()
        api.statusResponses.add { jsonError(500, "{\"error\": \"Internal Server Error\"}") }
        api.statusResponses.add {
            Response.success(LobbyStatus(roomId = "room-recovered", convoyName = "Ride"))
        }

        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = LobbyViewModel(api, repo)

        vm.startPolling("room-1")

        // First attempt (500)
        advanceTimeBy(100)
        val intermediateState = vm.loadState.value
        assertTrue("First failure should be in Retrying, was $intermediateState", intermediateState is LobbyLoadState.Retrying)

        // Advance past first backoff (2s)
        advanceTimeBy(2500)
        val finalState = vm.loadState.value
        assertTrue("Second attempt should recover to Ready, was $finalState", finalState is LobbyLoadState.Ready)
        assertEquals("room-recovered", (finalState as LobbyLoadState.Ready).status.roomId)
        assertEquals(2, api.statusCalls)
        vm.stopPolling()
    }

    @Test
    fun startPolling_410Terminal_transitionsToTerminalEnded() = runTest(testDispatcher) {
        val api = FakeApiService()
        api.defaultStatusResponse = { jsonError(410, "{\"error\": \"Ride ended\"}") }
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = LobbyViewModel(api, repo)

        vm.startPolling("room-1")
        advanceTimeBy(100)

        val state = vm.loadState.value
        assertTrue("State must be Terminal, was $state", state is LobbyLoadState.Terminal)
        assertEquals(LobbyViewModel.Terminal.ENDED, (state as LobbyLoadState.Terminal).terminal)
        assertEquals(LobbyViewModel.Terminal.ENDED, vm.terminal.value)
        assertEquals(1, api.statusCalls)
    }

    @Test
    fun startPolling_403Terminal_transitionsToTerminalRemoved() = runTest(testDispatcher) {
        val api = FakeApiService()
        api.defaultStatusResponse = { jsonError(403, "{\"error\": \"Forbidden\"}") }
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = LobbyViewModel(api, repo)

        vm.startPolling("room-1")
        advanceTimeBy(100)

        val state = vm.loadState.value
        assertTrue("State must be Terminal, was $state", state is LobbyLoadState.Terminal)
        assertEquals(LobbyViewModel.Terminal.REMOVED, (state as LobbyLoadState.Terminal).terminal)
        assertEquals(1, api.statusCalls)
    }

    @Test
    fun startPolling_404Terminal_transitionsToTerminalNotFound() = runTest(testDispatcher) {
        val api = FakeApiService()
        api.defaultStatusResponse = { jsonError(404, "{\"error\": \"Not Found\"}") }
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = LobbyViewModel(api, repo)

        vm.startPolling("room-1")
        advanceTimeBy(100)

        val state = vm.loadState.value
        assertTrue("State must be Terminal, was $state", state is LobbyLoadState.Terminal)
        assertEquals(LobbyViewModel.Terminal.NOT_FOUND, (state as LobbyLoadState.Terminal).terminal)
        assertEquals(1, api.statusCalls)
    }

    @Test
    fun stopPolling_cancelsJobAndHaltsPolling() = runTest(testDispatcher) {
        val api = FakeApiService()
        api.defaultStatusResponse = { jsonError(500, "{\"error\": \"Error\"}") }
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = LobbyViewModel(api, repo)

        vm.startPolling("room-1")
        advanceTimeBy(100) // First call happens
        assertEquals(1, api.statusCalls)

        vm.stopPolling()
        advanceTimeBy(10000)

        // Should not have made further attempts after cancellation
        assertEquals(1, api.statusCalls)
    }

    @Test
    fun retryPolling_restartsSingleJobAfterFailure() = runTest(testDispatcher) {
        val api = FakeApiService()
        api.defaultStatusResponse = { jsonError(503, "{\"error\": \"Service unavailable\"}") }
        val repo = ActiveRideRepository(prefs = null, api = api)
        val vm = LobbyViewModel(api, repo)

        vm.startPolling("room-1")
        advanceTimeBy(35000)

        assertEquals(5, api.statusCalls)
        assertTrue(vm.loadState.value is LobbyLoadState.Failed)

        // Now change response to 200 and retry
        api.defaultStatusResponse = {
            Response.success(LobbyStatus(roomId = "room-retry-success", convoyName = "Ride"))
        }

        // Calling retry twice quickly should not spawn multiple jobs
        vm.retryPolling("room-1")
        vm.retryPolling("room-1")
        advanceTimeBy(100)

        val state = vm.loadState.value
        assertTrue("Must transition to Ready after retry, was $state", state is LobbyLoadState.Ready)
        assertEquals("room-retry-success", (state as LobbyLoadState.Ready).status.roomId)
        assertEquals(6, api.statusCalls)
        vm.stopPolling()
    }
}
