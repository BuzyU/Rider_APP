package com.ridervoice.network

import com.ridervoice.models.*
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.state.RoomFault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class RoomIdentityVerificationTest {

    private class RecordingApiService : ApiService {
        var endRideCalls = 0
        var removeRiderCalls = 0
        var lastEndedRoom: String? = null
        var lastRemovedUser: String? = null

        override suspend fun endRideForEveryone(roomName: String): Response<Any> {
            endRideCalls++
            lastEndedRoom = roomName
            return Response.success(Unit)
        }

        override suspend fun removeRiderFromConvoy(roomName: String, userId: String): Response<Any> {
            removeRiderCalls++
            lastRemovedUser = userId
            return Response.success(Unit)
        }

        override suspend fun refreshToken(body: RefreshTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getLobbyStatus(roomName: String): Response<LobbyStatus> = throw NotImplementedError()
        override suspend fun updateFcmToken(body: FcmTokenRequest): Response<Any> = Response.success(Unit)
        override suspend fun upsertProfile(body: ProfileRequest): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun getMyProfile(): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun searchByHandle(handle: String): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun getFriendsList(userId: String): Response<List<Friend>> = throw NotImplementedError()
        override suspend fun sendFriendRequest(body: FriendRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getPendingInvites(userId: String): Response<List<RideInvite>> = throw NotImplementedError()
        override suspend fun respondToInvite(body: InviteRespondRequest): Response<Any> = throw NotImplementedError()
        override suspend fun sendRideInvite(body: SendInviteRequest): Response<Any> = throw NotImplementedError()
        override suspend fun createConvoy(body: ConvoyCreateRequest): Response<ConvoyCreateResponse> = throw NotImplementedError()
        override suspend fun startRide(roomName: String): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getJoinToken(body: JoinTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun generateShareLink(roomName: String): Response<ShareLinkResponse> = throw NotImplementedError()
        override suspend fun joinViaToken(body: JoinViaTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun transferHost(roomName: String, body: TransferHostRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getRoomToken(body: RoomTokenRequest): Response<RoomData> = throw NotImplementedError()
        override suspend fun sendSosAlert(body: SosAlertRequest): Response<SosAlertResponse> = throw NotImplementedError()
        override suspend fun cancelEmergencyAlert(body: CancelAlertRequest): Response<CancelAlertResponse> = throw NotImplementedError()
        override suspend fun syncRide(body: SyncRideRequest): Response<SyncRideResponse> = throw NotImplementedError()
        override suspend fun getRideHistory(): Response<List<RideHistoryResponse>> = throw NotImplementedError()
        override suspend fun finalizeDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun getLatestDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun joinLobbyUnified(body: LobbyJoinRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun leaveLobby(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun acceptRideInvite(id: String): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getAppConfig(): Response<AppConfigResponse> = throw NotImplementedError()
        override suspend fun deleteFcmToken(token: String?): Response<Any> = throw NotImplementedError()
    }

    private fun createLiveKitManager(api: ApiService, repo: ActiveRideRepository): LiveKitManager {
        val router = com.ridervoice.audio.AudioDeviceRouter(
            gateway = com.ridervoice.audio.FakeAudioManagerGateway()
        )
        val vox = com.ridervoice.audio.VoxEngine(audioDeviceRouter = router)
        return LiveKitManager(
            context = android.app.Application(),
            audioDeviceRouter = router,
            voxEngine = vox,
            apiService = api,
            activeRideRepository = repo,
            appEvents = com.ridervoice.state.AppEvents()
        )
    }

    @Test
    fun confirmedMismatch_abortsSession_andDoesNotCallGlobalTeardownApis() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        repo.enterLobby(roomId = "room-A", name = "Ride A", isHost = false, lkRoomName = "rv_room-A")

        val liveKitManager = createLiveKitManager(recordingApi, repo)

        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        var faultReceived: RoomFault? = null
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect { fault ->
                faultReceived = fault
            }
        }

        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-A",
            tokenProvider = { "token-A" },
            expectedLkRoomName = "rv_room-A"
        )

        val activeSessionId = liveKitManager.currentSessionId
        assertTrue("SessionId must be monotonic and > 0", activeSessionId > 0)
        assertEquals("room-A", liveKitManager.currentRoomId)
        assertEquals("rv_room-A", liveKitManager.expectedLkRoomName)

        val fault = RoomFault.ConfirmedRoomMismatch(
            intendedRoomId = "room-A",
            expectedLkRoomName = "rv_room-A",
            actualRoomName = "rv_room-B",
            reason = "Connected room does not match expected canonical room"
        )
        liveKitManager.abortConnection(sessionId = activeSessionId, fault = fault)

        assertNotNull(faultReceived)
        assertTrue(faultReceived is RoomFault.ConfirmedRoomMismatch)
        val emitted = faultReceived as RoomFault.ConfirmedRoomMismatch
        assertEquals("room-A", emitted.intendedRoomId)
        assertEquals("rv_room-B", emitted.actualRoomName)

        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
        assertNull(repo.state.value)

        assertEquals("endRideForEveryone must NEVER be called on rider mismatch", 0, recordingApi.endRideCalls)
        assertEquals("removeRiderFromConvoy must NEVER be called on rider mismatch", 0, recordingApi.removeRiderCalls)

        faultJob.cancel()
    }

    @Test
    fun abortConnection_withStaleSessionId_isIgnored() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        repo.enterLobby(roomId = "room-B", name = "Ride B", isHost = false, lkRoomName = "rv_room-B")

        val liveKitManager = createLiveKitManager(recordingApi, repo)

        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-B",
            tokenProvider = { "token-B" },
            expectedLkRoomName = "rv_room-B"
        )

        val currentSessionId = liveKitManager.currentSessionId
        val staleSessionId = currentSessionId - 1

        val fault = RoomFault.ConfirmedRoomMismatch(
            intendedRoomId = "room-A",
            expectedLkRoomName = "rv_room-A",
            actualRoomName = "rv_room-X",
            reason = "Stale mismatch"
        )
        liveKitManager.abortConnection(sessionId = staleSessionId, fault = fault)

        assertNotNull(repo.state.value)
        assertEquals("room-B", repo.state.value?.roomId)
    }

    @Test
    fun sequentialRides_isolateSessionLifecycle() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)

        val liveKitManager = createLiveKitManager(recordingApi, repo)

        // Session 1: Room A
        repo.enterLobby(roomId = "room-A", name = "Ride A", isHost = false, lkRoomName = "rv_room-A")
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-A",
            tokenProvider = { "token-A" },
            expectedLkRoomName = "rv_room-A"
        )
        val session1 = liveKitManager.currentSessionId

        // Disconnect Session 1
        liveKitManager.disconnect()
        val sessionAfterDisconnect = liveKitManager.currentSessionId
        assertTrue("Disconnect increments session ID", sessionAfterDisconnect > session1)

        // Session 2: Room B
        repo.enterLobby(roomId = "room-B", name = "Ride B", isHost = true, lkRoomName = "rv_room-B")
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-B",
            tokenProvider = { "token-B" },
            expectedLkRoomName = "rv_room-B"
        )
        val session2 = liveKitManager.currentSessionId
        assertTrue("Session 2 has higher sessionId than session 1", session2 > session1)
        assertEquals("room-B", liveKitManager.currentRoomId)
        assertEquals("rv_room-B", liveKitManager.expectedLkRoomName)
    }
}
