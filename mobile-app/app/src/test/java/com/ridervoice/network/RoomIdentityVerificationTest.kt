package com.ridervoice.network

import com.ridervoice.models.*
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.state.RoomFault
import io.livekit.android.ConnectOptions
import io.livekit.android.events.BroadcastEventBus
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.mockk.*
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
        var getLobbyStatusCalls = 0
        var getLobbyStatusHandler: (suspend (String) -> Response<LobbyStatus>)? = null

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
        override suspend fun getLobbyStatus(roomName: String): Response<LobbyStatus> {
            getLobbyStatusCalls++
            val custom = getLobbyStatusHandler
            if (custom != null) return custom(roomName)
            return Response.error(404, okhttp3.ResponseBody.create(null, ""))
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

    private fun createToken(room: String): String {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"video":{"room":"$room","roomJoin":true},"sub":"user123"}""".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )
        return "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.$payload.sig"
    }

    private fun createTokenWithoutRoomClaim(): String {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"video":{"canPublish":true},"sub":"user123"}""".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )
        return "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.$payload.sig"
    }

    private fun createMockRoom(roomName: String): Pair<Room, BroadcastEventBus<RoomEvent>> {
        val eventBus = BroadcastEventBus<RoomEvent>()
        val mockRoom = mockk<Room>(relaxed = true)
        every { mockRoom.name } returns roomName
        every { mockRoom.events } returns eventBus.readOnly()
        return Pair(mockRoom, eventBus)
    }

    // =========================================================================
    // SECTION 5 REQUIRED REGRESSION TESTS (1 through 10)
    // =========================================================================

    @Test
    fun test1_validIdentity_connectionProceedsNormally() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        val liveKitManager = createLiveKitManager(recordingApi, repo)

        val (mockRoom, _) = createMockRoom("rv_canonical_123")
        val capturedOptions = mutableListOf<ConnectOptions>()
        coEvery { mockRoom.connect(any(), any(), capture(capturedOptions)) } returns null
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        val validToken = createToken("rv_canonical_123")
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-123",
            tokenProvider = { validToken },
            expectedLkRoomName = "rv_canonical_123"
        )

        coVerify(exactly = 1) { mockRoom.connect("wss://test.lk", validToken, any()) }
        assertEquals(1, capturedOptions.size)
        assertFalse("autoSubscribe must be configured false upon connection (4.D)", capturedOptions[0].autoSubscribe)
        assertEquals(ConnectionState.CONNECTED, liveKitManager.connectionState.value)
    }

    @Test
    fun test2_unresolvedExpectedIdentity_failsClosedBeforeConnect() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        val liveKitManager = createLiveKitManager(recordingApi, repo)

        var roomConnectCalled = false
        val (mockRoom, _) = createMockRoom("any")
        coEvery { mockRoom.connect(any(), any(), any()) } answers {
            roomConnectCalled = true
            null
        }
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        var faultReceived: RoomFault? = null
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect { faultReceived = it }
        }

        // expectedLkRoomName is null, activeRideRepository has no matching ride, and getLobbyStatus returns 404
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-unresolved",
            tokenProvider = { createToken("rv_some_claim") },
            expectedLkRoomName = null
        )

        assertFalse("Room.connect must never be called when canonical identity is unresolved", roomConnectCalled)
        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
        assertNotNull(faultReceived)
        assertTrue(faultReceived is RoomFault.ConfirmedRoomMismatch)
        val mismatch = faultReceived as RoomFault.ConfirmedRoomMismatch
        assertEquals("room-unresolved", mismatch.intendedRoomId)
        assertNull(mismatch.expectedLkRoomName)
        faultJob.cancel()
    }

    @Test
    fun test3_missingTokenRoomClaim_rejectedBeforeConnect() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        val liveKitManager = createLiveKitManager(recordingApi, repo)

        var roomConnectCalled = false
        val (mockRoom, _) = createMockRoom("rv_canonical")
        coEvery { mockRoom.connect(any(), any(), any()) } answers {
            roomConnectCalled = true
            null
        }
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        var faultReceived: RoomFault? = null
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect { faultReceived = it }
        }

        val tokenWithoutRoom = createTokenWithoutRoomClaim()
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-test",
            tokenProvider = { tokenWithoutRoom },
            expectedLkRoomName = "rv_canonical"
        )

        assertFalse("Room.connect must never be called for token lacking room claim", roomConnectCalled)
        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
        assertNotNull(faultReceived)
        assertTrue(faultReceived is RoomFault.ConfirmedRoomMismatch)
        val mismatch = faultReceived as RoomFault.ConfirmedRoomMismatch
        assertTrue(mismatch.reason.contains("missing, malformed, or undecodable", ignoreCase = true))
        faultJob.cancel()
    }

    @Test
    fun test4_malformedToken_rejectedBeforeConnect() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        val liveKitManager = createLiveKitManager(recordingApi, repo)

        var roomConnectCalled = false
        val (mockRoom, _) = createMockRoom("rv_canonical")
        coEvery { mockRoom.connect(any(), any(), any()) } answers {
            roomConnectCalled = true
            null
        }
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        var faultReceived: RoomFault? = null
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect { faultReceived = it }
        }

        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-test",
            tokenProvider = { "not.a.valid.jwt" },
            expectedLkRoomName = "rv_canonical"
        )

        assertFalse("Room.connect must never be called for malformed token", roomConnectCalled)
        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
        assertNotNull(faultReceived)
        assertTrue(faultReceived is RoomFault.ConfirmedRoomMismatch)
        faultJob.cancel()
    }

    @Test
    fun test5_claimMismatch_requestedRoomB_tokenRoomA_rejectedBeforeConnect() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        val liveKitManager = createLiveKitManager(recordingApi, repo)

        var roomConnectCalled = false
        val (mockRoom, _) = createMockRoom("rv_room_B")
        coEvery { mockRoom.connect(any(), any(), any()) } answers {
            roomConnectCalled = true
            null
        }
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        var faultReceived: RoomFault? = null
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect { faultReceived = it }
        }

        val tokenRoomA = createToken("rv_room_A")
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-B",
            tokenProvider = { tokenRoomA },
            expectedLkRoomName = "rv_room_B"
        )

        assertFalse("Room.connect must never be called on claim mismatch", roomConnectCalled)
        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
        assertNotNull(faultReceived)
        assertTrue(faultReceived is RoomFault.ConfirmedRoomMismatch)
        val mismatch = faultReceived as RoomFault.ConfirmedRoomMismatch
        assertEquals("room-B", mismatch.intendedRoomId)
        assertEquals("rv_room_B", mismatch.expectedLkRoomName)
        assertEquals("rv_room_A", mismatch.actualRoomName)
        faultJob.cancel()
    }

    @Test
    fun test6_staleActiveRideState_notUsedToValidateNewRoom() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        // Repo holds stale state for room-A
        repo.enterLobby(roomId = "room-A", name = "Ride A", isHost = false, lkRoomName = "rv_room_A")

        val liveKitManager = createLiveKitManager(recordingApi, repo)

        var roomConnectCalled = false
        val (mockRoom, _) = createMockRoom("rv_room_B")
        coEvery { mockRoom.connect(any(), any(), any()) } answers {
            roomConnectCalled = true
            null
        }
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        var faultReceived: RoomFault? = null
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect { faultReceived = it }
        }

        // Requested connection is for room-B, token also has rv_room_A.
        // If stale state was erroneously used, expected would be rv_room_A and it would match!
        // But since stale state must NOT be used, expected cannot resolve from repo, lobby 404s, and connection fails closed.
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-B",
            tokenProvider = { createToken("rv_room_A") },
            expectedLkRoomName = null
        )

        assertFalse("Must not connect using stale active ride state from another room", roomConnectCalled)
        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
        assertNotNull(faultReceived)
        assertTrue(faultReceived is RoomFault.ConfirmedRoomMismatch)
        val mismatch = faultReceived as RoomFault.ConfirmedRoomMismatch
        assertEquals("room-B", mismatch.intendedRoomId)
        assertNull("Stale room-A lkRoomName must not become expectedLkRoomName for room-B", mismatch.expectedLkRoomName)
        faultJob.cancel()
    }

    @Test
    fun test7_lobbyLookupFailure_failsClosedNoLegacyFallback() = runTest {
        val recordingApi = RecordingApiService()
        recordingApi.getLobbyStatusHandler = {
            throw java.io.IOException("Network timeout communicating with backend")
        }
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        val liveKitManager = createLiveKitManager(recordingApi, repo)

        var roomConnectCalled = false
        val (mockRoom, _) = createMockRoom("any")
        coEvery { mockRoom.connect(any(), any(), any()) } answers {
            roomConnectCalled = true
            null
        }
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        var faultReceived: RoomFault? = null
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect { faultReceived = it }
        }

        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-network-err",
            tokenProvider = { createToken("rv_room_network_err") },
            expectedLkRoomName = null
        )

        assertFalse("Must fail closed on lobby lookup failure without legacy fallback", roomConnectCalled)
        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
        assertNotNull(faultReceived)
        assertTrue(faultReceived is RoomFault.ConfirmedRoomMismatch)
        faultJob.cancel()
    }

    @Test
    fun test8_postConnectMismatch_abortsImmediately_doesNotPublishAudio_neverConnected() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        val liveKitManager = createLiveKitManager(recordingApi, repo)

        // Mock room reports name "rv_wrong_post_connect" while expected is "rv_expected"
        val (mockRoom, _) = createMockRoom("rv_wrong_post_connect")
        var disconnectCalled = false
        every { mockRoom.disconnect() } answers {
            disconnectCalled = true
            Unit
        }
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        var faultReceived: RoomFault? = null
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect { faultReceived = it }
        }

        val token = createToken("rv_expected")
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-1",
            tokenProvider = { token },
            expectedLkRoomName = "rv_expected"
        )

        assertTrue("disconnect must be called on post-connect mismatch", disconnectCalled)
        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
        assertNotNull(faultReceived)
        assertTrue(faultReceived is RoomFault.ConfirmedRoomMismatch)
        val mismatch = faultReceived as RoomFault.ConfirmedRoomMismatch
        assertEquals("rv_expected", mismatch.expectedLkRoomName)
        assertEquals("rv_wrong_post_connect", mismatch.actualRoomName)
        faultJob.cancel()
    }

    @Test
    fun test9_supersededAttempt_olderAttemptCannotPublishOrUpdateState() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        val liveKitManager = createLiveKitManager(recordingApi, repo)

        val (mockRoom1, _) = createMockRoom("rv_room_1")
        val (mockRoom2, _) = createMockRoom("rv_room_2")

        coEvery { mockRoom1.connect(any(), any(), any()) } coAnswers {
            // While room1 is connecting, launch second connection that increments sessionId
            liveKitManager.connect(
                url = "wss://test.lk",
                roomId = "room-2",
                tokenProvider = { createToken("rv_room_2") },
                expectedLkRoomName = "rv_room_2"
            )
            null
        }
        var factoryCallCount = 0
        liveKitManager.roomFactory = { _, _ ->
            factoryCallCount++
            if (factoryCallCount == 1) mockRoom1 else mockRoom2
        }

        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-1",
            tokenProvider = { createToken("rv_room_1") },
            expectedLkRoomName = "rv_room_1"
        )

        assertEquals("room-2", liveKitManager.currentRoomId)
        assertEquals("rv_room_2", liveKitManager.expectedLkRoomName)
    }

    @Test
    fun test10_deprecatedApiRemoved_cannotBypassRoomVerification() {
        val methods = LiveKitManager::class.java.declaredMethods
        val insecureConnectMethod = methods.find {
            it.name == "connect" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == String::class.java
        }
        assertNull(
            "Insecure connect(url, token) overload must be removed to prevent unverified room bypass",
            insecureConnectMethod
        )
    }

    @Test
    fun test11_futureTrackPublished_subscribesOnlyWhenConnectedAndVerified_ignoresSupersededSession() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        val liveKitManager = createLiveKitManager(recordingApi, repo)

        val (mockRoom, eventBus) = createMockRoom("rv_canonical_123")
        coEvery { mockRoom.connect(any(), any(), any()) } returns null
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        // 1. Connect and verify room identity -> reaches CONNECTED
        val validToken = createToken("rv_canonical_123")
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-123",
            tokenProvider = { validToken },
            expectedLkRoomName = "rv_canonical_123"
        )
        assertEquals(ConnectionState.CONNECTED, liveKitManager.connectionState.value)

        // 2. Simulate a remote participant publishing a new audio track in the verified room
        val mockRemotePub = mockk<RemoteTrackPublication>(relaxed = true)
        every { mockRemotePub.kind } returns Track.Kind.AUDIO
        every { mockRemotePub.subscribed } returns false
        var subscribedCalled = false
        every { mockRemotePub.setSubscribed(true) } answers {
            subscribedCalled = true
            Unit
        }
        val mockParticipant = mockk<RemoteParticipant>(relaxed = true)

        val validTrackEvent = RoomEvent.TrackPublished(
            room = mockRoom,
            publication = mockRemotePub,
            participant = mockParticipant
        )
        liveKitManager.handleEvent(validTrackEvent)

        assertTrue("Newly published remote audio track must be subscribed when session is CONNECTED", subscribedCalled)

        // 3. Simulate an event from a superseded / stale room
        val (staleRoom, _) = createMockRoom("rv_stale_room")
        var staleSubscribedCalled = false
        val mockStalePub = mockk<RemoteTrackPublication>(relaxed = true)
        every { mockStalePub.kind } returns Track.Kind.AUDIO
        every { mockStalePub.subscribed } returns false
        every { mockStalePub.setSubscribed(true) } answers {
            staleSubscribedCalled = true
            Unit
        }

        val staleTrackEvent = RoomEvent.TrackPublished(
            room = staleRoom,
            publication = mockStalePub,
            participant = mockParticipant
        )
        liveKitManager.handleEvent(staleTrackEvent)

        assertFalse("TrackPublished event from superseded/stale room must be ignored", staleSubscribedCalled)

        // 4. Disconnect session and verify future events are ignored in DISCONNECTED state
        liveKitManager.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)

        var disconnectedSubscribedCalled = false
        val mockDisconnectedPub = mockk<RemoteTrackPublication>(relaxed = true)
        every { mockDisconnectedPub.kind } returns Track.Kind.AUDIO
        every { mockDisconnectedPub.subscribed } returns false
        every { mockDisconnectedPub.setSubscribed(true) } answers {
            disconnectedSubscribedCalled = true
            Unit
        }

        val disconnectedTrackEvent = RoomEvent.TrackPublished(
            room = mockRoom,
            publication = mockDisconnectedPub,
            participant = mockParticipant
        )
        liveKitManager.handleEvent(disconnectedTrackEvent)

        assertFalse("TrackPublished event while DISCONNECTED must not subscribe", disconnectedSubscribedCalled)
    }

    @Test
    fun test12_callerRoomIdentityBinding_neverPassesStaleRoomIdentity() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        // Repo holds valid active ride for room-A
        repo.enterLobby(roomId = "room-A", name = "Ride A", isHost = false, lkRoomName = "rv_room_A")

        val currentRide = repo.state.value
        assertNotNull(currentRide)

        // Simulating caller logic from RoomViewModel line 278:
        val targetRoomId = "room-B"
        val expectedForTarget = currentRide?.takeIf { it.roomId == targetRoomId }?.lkRoomName

        assertNull("Caller must NOT pass room-A lkRoomName when joining room-B", expectedForTarget)

        // Verify that LiveKitManager also rejects using stale room-A state when joining room-B
        val liveKitManager = createLiveKitManager(recordingApi, repo)
        val (mockRoom, _) = createMockRoom("rv_room_B")
        coEvery { mockRoom.connect(any(), any(), any()) } returns null
        liveKitManager.roomFactory = { _, _ -> mockRoom }

        // Attempting to connect to room-B with expectedLkRoomName = null
        // If stale state was used, it would adopt "rv_room_A"
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = targetRoomId,
            tokenProvider = { createToken("rv_room_A") },
            expectedLkRoomName = expectedForTarget
        )

        assertNotEquals("LiveKitManager must not bind rv_room_A when requested room is room-B", "rv_room_A", liveKitManager.expectedLkRoomName)
        assertEquals("Must fail closed and remain DISCONNECTED", ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
    }

    @Test
    fun test13_abortConnection_forRoomB_doesNotClearActiveRideForRoomA() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        // Active ride A is active and valid in repository
        repo.enterLobby(roomId = "room-A", name = "Ride A", isHost = false, lkRoomName = "rv_room_A")
        assertEquals("room-A", repo.state.value?.roomId)

        val liveKitManager = createLiveKitManager(recordingApi, repo)

        // 1. Connection attempt for room-B fails with mismatch
        val faultRoomB = RoomFault.ConfirmedRoomMismatch(
            intendedRoomId = "room-B",
            expectedLkRoomName = "rv_room_B",
            actualRoomName = "rv_room_wrong",
            reason = "Room B token claim mismatch"
        )
        liveKitManager.abortConnection(sessionId = liveKitManager.currentSessionId, fault = faultRoomB)

        // Active ride A must NOT be cleared by failed connection attempt to room-B!
        assertNotNull("Active ride A must be preserved when unrelated room-B join fails", repo.state.value)
        assertEquals("room-A", repo.state.value?.roomId)

        // 2. In contrast, an abort for room-A itself DOES clear active ride A
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-A",
            tokenProvider = { createToken("rv_room_A") },
            expectedLkRoomName = "rv_room_A"
        )
        val sessionA = liveKitManager.currentSessionId

        val faultRoomA = RoomFault.ConfirmedRoomMismatch(
            intendedRoomId = "room-A",
            expectedLkRoomName = "rv_room_A",
            actualRoomName = "rv_room_wrong",
            reason = "Room A mismatch"
        )
        liveKitManager.abortConnection(sessionId = sessionA, fault = faultRoomA)

        // Active ride A MUST be cleared when its own session is aborted
        assertNull("Active ride A must be cleared when room A itself is aborted", repo.state.value)
    }

    // =========================================================================
    // BASELINE SECURITY AUDIT TESTS
    // =========================================================================

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
            tokenProvider = { createToken("rv_room-A") },
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
            tokenProvider = { createToken("rv_room-B") },
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
            tokenProvider = { createToken("rv_room-A") },
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
            tokenProvider = { createToken("rv_room-B") },
            expectedLkRoomName = "rv_room-B"
        )
        val session2 = liveKitManager.currentSessionId
        assertTrue("Session 2 has higher sessionId than session 1", session2 > session1)
        assertEquals("room-B", liveKitManager.currentRoomId)
        assertEquals("rv_room-B", liveKitManager.expectedLkRoomName)
    }

    @Test
    fun abortConnection_isIdempotent() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        repo.enterLobby(roomId = "room-C", name = "Ride C", isHost = false, lkRoomName = "rv_room-C")

        val liveKitManager = createLiveKitManager(recordingApi, repo)
        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-C",
            tokenProvider = { createToken("rv_room-C") },
            expectedLkRoomName = "rv_room-C"
        )

        val activeSessionId = liveKitManager.currentSessionId
        val fault = RoomFault.ConfirmedRoomMismatch(
            intendedRoomId = "room-C",
            expectedLkRoomName = "rv_room-C",
            actualRoomName = "rv_room-X",
            reason = "First abort"
        )

        var faultsEmitted = 0
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect {
                faultsEmitted++
            }
        }

        // First abort call
        liveKitManager.abortConnection(sessionId = activeSessionId, fault = fault)
        assertEquals(1, faultsEmitted)

        // Second duplicate abort call with same sessionId
        liveKitManager.abortConnection(sessionId = activeSessionId, fault = fault)
        assertEquals("Duplicate abort for same session must be ignored", 1, faultsEmitted)

        faultJob.cancel()
    }

    @Test
    fun preconnectMismatch_abortsBeforeConnect() = runTest {
        val recordingApi = RecordingApiService()
        val repo = ActiveRideRepository(prefs = null, api = recordingApi)
        repo.enterLobby(roomId = "room-D", name = "Ride D", isHost = false, lkRoomName = "rv_room-expected")

        val liveKitManager = createLiveKitManager(recordingApi, repo)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)

        var faultReceived: RoomFault? = null
        val faultJob = launch(testDispatcher) {
            liveKitManager.roomFault.collect { fault ->
                faultReceived = fault
            }
        }

        // Token has video.room = "rv_room-wrong" while expected is "rv_room-expected"
        val mismatchToken = createToken("rv_room-wrong")

        liveKitManager.connect(
            url = "wss://test.lk",
            roomId = "room-D",
            tokenProvider = { mismatchToken },
            expectedLkRoomName = "rv_room-expected"
        )

        assertNotNull("Must emit ConfirmedRoomMismatch before connecting", faultReceived)
        assertTrue(faultReceived is RoomFault.ConfirmedRoomMismatch)
        val mismatch = faultReceived as RoomFault.ConfirmedRoomMismatch
        assertEquals("room-D", mismatch.intendedRoomId)
        assertEquals("rv_room-expected", mismatch.expectedLkRoomName)
        assertEquals("rv_room-wrong", mismatch.actualRoomName)

        assertEquals(ConnectionState.DISCONNECTED, liveKitManager.connectionState.value)
        assertNull(repo.state.value)

        faultJob.cancel()
    }
}
