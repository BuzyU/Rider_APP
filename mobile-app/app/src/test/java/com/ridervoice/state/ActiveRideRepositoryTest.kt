package com.ridervoice.state

import com.ridervoice.models.*
import com.ridervoice.network.ApiService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveRideRepositoryTest {

    private class FakeApi : ApiService {
        var refreshResult: Response<StartRideResponse> = Response.success(
            StartRideResponse(
                token = "fresh_token_abc",
                roomName = "convoy-test",
                roomId = "room-uuid-123",
                livekitUrl = "wss://livekit.test",
                diagnosticsEnabled = true,
                testNumber = 42
            )
        )

        var lobbyStatusResult: Response<LobbyStatus> = Response.success(
            LobbyStatus(
                roomId = "room-uuid-123",
                convoyName = "convoy-test",
                isHost = true,
                isRideStarted = false
            )
        )

        override suspend fun refreshToken(body: RefreshTokenRequest): Response<StartRideResponse> = refreshResult
        override suspend fun getLobbyStatus(roomName: String): Response<LobbyStatus> = lobbyStatusResult

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
        override suspend fun joinLobbyUnified(body: LobbyJoinRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun leaveLobby(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun acceptRideInvite(id: String): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getAppConfig(): Response<AppConfigResponse> = throw NotImplementedError()
        override suspend fun deleteFcmToken(token: String?): Response<Any> = throw NotImplementedError()
    }

    @Test
    fun enterLobby_setsPhaseToLobby() {
        val repo = ActiveRideRepository(prefs = null, api = FakeApi())
        assertNull(repo.state.value)

        repo.enterLobby(roomId = "room-1", name = "Sunday Ghat", isHost = true, joinCode = "ABCD1234")

        val state = repo.state.value
        assertNotNull(state)
        assertEquals("room-1", state?.roomId)
        assertEquals("Sunday Ghat", state?.displayName)
        assertTrue(state?.isHost == true)
        assertEquals(ActiveRideRepository.Phase.LOBBY, state?.phase)
        assertEquals("ABCD1234", state?.joinCode)
    }

    @Test
    fun applyTokenPayload_and_goLive() {
        val repo = ActiveRideRepository(prefs = null, api = FakeApi())
        repo.enterLobby("room-1", "Sunday Ghat", false)

        repo.applyTokenPayload(
            TokenPayload(
                token = "lk_token_999",
                livekitUrl = "wss://lk.domain",
                roomId = "room-1",
                roomName = "Sunday Ghat",
                diagnosticsEnabled = true,
                testNumber = 5
            )
        )

        assertEquals("lk_token_999", repo.state.value?.token)
        assertEquals("wss://lk.domain", repo.state.value?.livekitUrl)
        assertTrue(repo.state.value?.diagnosticsEnabled == true)
        assertEquals(5, repo.state.value?.testNumber)

        repo.goLive()
        assertEquals(ActiveRideRepository.Phase.LIVE, repo.state.value?.phase)
    }

    @Test
    fun clear_snapshotsPostRideAndResetsState() {
        val repo = ActiveRideRepository(prefs = null, api = FakeApi())
        repo.enterLobby("room-1", "Sunday Ghat", true)
        repo.applyTokenPayload(TokenPayload("tok", "url", "room-1", "Sunday Ghat", diagnosticsEnabled = true, testNumber = 7))

        assertNull(repo.lastEnded.value)
        repo.clear(ActiveRideRepository.ClearReason.ENDED_BY_HOST)

        assertNull(repo.state.value)
        val snapshot = repo.lastEnded.value
        assertNotNull(snapshot)
        assertEquals("Sunday Ghat", snapshot?.convoyName)
        assertEquals(7, snapshot?.testNumber)
        assertTrue(snapshot?.diagnosticsEnabled == true)
        assertEquals("ENDED_BY_HOST", snapshot?.endedBy)
    }

    @Test
    fun ensureFreshToken_refreshesWhenMissingOrExpired() = runTest {
        val fakeApi = FakeApi()
        val repo = ActiveRideRepository(prefs = null, api = fakeApi)
        repo.enterLobby("room-uuid-123", "convoy-test", false)

        val result = repo.ensureFreshToken()
        assertTrue(result.isSuccess)
        assertEquals("fresh_token_abc", result.getOrNull())
        assertEquals("fresh_token_abc", repo.state.value?.token)
        assertEquals("wss://livekit.test", repo.state.value?.livekitUrl)
    }

    @Test
    fun ensureFreshToken_403ClearsRemoved() = runTest {
        val fakeApi = FakeApi().apply {
            refreshResult = Response.error(403, "{}".toResponseBody("application/json".toMediaType()))
        }
        val repo = ActiveRideRepository(prefs = null, api = fakeApi)
        repo.enterLobby("room-1", "convoy", false)

        val result = repo.ensureFreshToken()
        assertTrue(result.isFailure)
        assertNull(repo.state.value)
        assertEquals("REMOVED", repo.lastEnded.value?.endedBy)
    }

    @Test
    fun ensureFreshToken_410ClearsEnded() = runTest {
        val fakeApi = FakeApi().apply {
            refreshResult = Response.error(410, "{}".toResponseBody("application/json".toMediaType()))
        }
        val repo = ActiveRideRepository(prefs = null, api = fakeApi)
        repo.enterLobby("room-1", "convoy", false)

        val result = repo.ensureFreshToken()
        assertTrue(result.isFailure)
        assertNull(repo.state.value)
        assertEquals("ENDED_BY_HOST", repo.lastEnded.value?.endedBy)
    }

    @Test
    fun applyTokenPayload_propagatesLkRoomName() {
        val repo = ActiveRideRepository(prefs = null, api = FakeApi())
        repo.enterLobby("room-uuid-1", "Sunday Ghat", false, lkRoomName = "rv_room-uuid-1")

        repo.applyTokenPayload(
            TokenPayload(
                token = "lk_token_123",
                livekitUrl = "wss://lk.domain",
                roomId = "room-uuid-1",
                roomName = "Sunday Ghat",
                lkRoomName = "rv_room-uuid-1"
            )
        )

        val state = repo.state.value
        assertNotNull(state)
        assertEquals("rv_room-uuid-1", state?.lkRoomName)
    }

    @Test
    fun ensureFreshToken_403NotAMember_returnsNotMemberError() = runTest {
        val fakeApi = FakeApi().apply {
            refreshResult = Response.error(403, "{\"code\":\"NOT_A_MEMBER\"}".toResponseBody("application/json".toMediaType()))
        }
        val repo = ActiveRideRepository(prefs = null, api = fakeApi)
        repo.enterLobby("room-1", "convoy", false)

        val result = repo.ensureFreshToken()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("NOT_A_MEMBER") == true)
        assertNull(repo.state.value)
        assertEquals("REMOVED", repo.lastEnded.value?.endedBy)
    }

    @Test
    fun ensureFreshToken_404ConfirmedNotFound_clearsEndedByHost() = runTest {
        val fakeApi = FakeApi().apply {
            refreshResult = Response.error(404, "{\"code\":\"ROOM_NOT_FOUND\"}".toResponseBody("application/json".toMediaType()))
            lobbyStatusResult = Response.error(404, "{}".toResponseBody("application/json".toMediaType()))
        }
        val repo = ActiveRideRepository(prefs = null, api = fakeApi)
        repo.enterLobby("room-1", "convoy", false)

        val result = repo.ensureFreshToken()
        assertTrue(result.isFailure)
        assertNull(repo.state.value)
        assertEquals("ENDED_BY_HOST", repo.lastEnded.value?.endedBy)
    }
}
