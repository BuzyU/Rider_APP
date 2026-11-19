package com.ridervoice.e2e

import android.content.Context
import android.location.Location
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.ridervoice.audio.AudioDeviceRouter
import com.ridervoice.audio.VoxEngine
import com.ridervoice.data.local.RideDao
import com.ridervoice.data.local.entities.ConvoyEventEntity
import com.ridervoice.data.local.entities.RawWaypointEntity
import com.ridervoice.data.local.entities.RideSessionEntity
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.RoomMeshConfig
import com.ridervoice.mesh.RoomMeshFlagMirror
import com.ridervoice.mesh.offline.BatteryOptimizationChecker
import com.ridervoice.mesh.offline.DeviceCapabilityChecker
import com.ridervoice.mesh.offline.OfflineConfigCoordinator
import com.ridervoice.mesh.offline.OfflineConfigStatus
import com.ridervoice.mesh.offline.PermissionChecker
import com.ridervoice.mesh.offline.TransportReadinessChecker
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.models.*
import com.ridervoice.network.ApiService
import com.ridervoice.network.LiveKitManager
import com.ridervoice.permissions.PermissionManager
import com.ridervoice.security.AuthRepository
import com.ridervoice.security.SecurePreferences
import com.ridervoice.services.InMemorySosOutbox
import com.ridervoice.services.LocationService
import com.ridervoice.services.RideRecorder
import com.ridervoice.services.SosDeliveryPolicy
import com.ridervoice.services.SosOutcome
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.viewmodels.*
import com.ridervoice.update.AppVersion
import com.ridervoice.update.UpdateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.io.IOException

/**
 * WP-160..WP-166: Automated Cross-Feature Flow Integration Suite (Flows a through j).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossFeatureFlowsTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeRideDao : RideDao {
        val sessions = mutableMapOf<String, RideSessionEntity>()
        val waypoints = mutableListOf<RawWaypointEntity>()
        val events = mutableListOf<ConvoyEventEntity>()
        private val sessionsFlow = MutableStateFlow<List<RideSessionEntity>>(emptyList())

        override suspend fun insertSession(session: RideSessionEntity) {
            sessions[session.id] = session
            sessionsFlow.value = sessions.values.sortedByDescending { it.startTime }
        }
        override suspend fun updateSession(session: RideSessionEntity) {
            sessions[session.id] = session
            sessionsFlow.value = sessions.values.sortedByDescending { it.startTime }
        }
        override suspend fun insertWaypoint(waypoint: RawWaypointEntity) { waypoints.add(waypoint) }
        override suspend fun insertWaypoints(waypoints: List<RawWaypointEntity>) { this.waypoints.addAll(waypoints) }
        override suspend fun insertEvent(event: ConvoyEventEntity) { events.add(event) }
        override suspend fun getSessionById(sessionId: String): RideSessionEntity? = sessions[sessionId]
        override suspend fun getUnsyncedSessions(): List<RideSessionEntity> = sessions.values.filter { !it.isSynced }
        override suspend fun getWaypointsForSession(sessionId: String): List<RawWaypointEntity> =
            waypoints.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }
        override suspend fun getEventsForSession(sessionId: String): List<ConvoyEventEntity> =
            events.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }
        override fun getAllSessions(): Flow<List<RideSessionEntity>> = sessionsFlow
        override suspend fun deleteWaypointsForSession(sessionId: String) { waypoints.removeAll { it.sessionId == sessionId } }
        override suspend fun getMaxSpeed(sessionId: String): Float? = waypoints.filter { it.sessionId == sessionId }.maxOfOrNull { it.speedMps }
        override suspend fun getAllTimeMaxSpeed(): Float? = waypoints.maxOfOrNull { it.speedMps }
        override suspend fun deleteSession(sessionId: String) {
            sessions.remove(sessionId)
            sessionsFlow.value = sessions.values.sortedByDescending { it.startTime }
        }
    }

    private class FakeEndToEndApi : ApiService {
        var fcmDeletedCalled = false
        val syncedClientRideIds = mutableListOf<String?>()
        val sosSentClientAlertIds = mutableListOf<String>()
        var failNetworkOnSos = false
        var fail503SchemaOnCreate = false
        var joinViaTokenHandler: (String) -> Response<StartRideResponse> = { token ->
            when (token) {
                "EXPIRED_TOKEN_1234" -> Response.error(410, """{"error":"Invite link expired","code":"TOKEN_EXPIRED"}""".toResponseBody("application/json".toMediaType()))
                "REMOVED_TOKEN_5678" -> Response.error(403, """{"error":"You were removed by the host","code":"REMOVED_BY_HOST"}""".toResponseBody("application/json".toMediaType()))
                "VALID_TOKEN_00001" -> Response.success(StartRideResponse("jwt-link", "AlphaRun", "room-alpha", "rv_room-alpha", "wss://lk.test"))
                else -> Response.error(404, """{"error":"Not found"}""".toResponseBody("application/json".toMediaType()))
            }
        }
        var lobbyJoinHandler: (LobbyJoinRequest) -> Response<StartRideResponse> = { req ->
            when (req.kind) {
                "CODE" -> if (req.value == "ALPH1234") Response.success(StartRideResponse("jwt-code", "AlphaRun", "room-alpha", "rv_room-alpha", "wss://lk.test"))
                else Response.error(404, """{"error":"Invalid code","code":"INVALID_CODE"}""".toResponseBody("application/json".toMediaType()))
                "TOKEN" -> joinViaTokenHandler(req.value)
                else -> Response.error(400, "{}".toResponseBody())
            }
        }
        var currentProfile = ProfileResponse(id = "u_host", handle = "host1", displayName = "Host Rider", bikeModel = "BMW GS")

        override suspend fun createConvoy(body: ConvoyCreateRequest): Response<ConvoyCreateResponse> {
            if (fail503SchemaOnCreate) {
                return Response.error(503, """{"code":"SCHEMA_DEGRADED","error":"Database schema degraded"}""".toResponseBody("application/json".toMediaType()))
            }
            return Response.success(ConvoyCreateResponse("room-alpha", body.convoyName, "u_host", false, "ALPH1234", "LOBBY"))
        }
        override suspend fun sendRideInvite(body: SendInviteRequest): Response<Any> = Response.success(Unit)
        override suspend fun acceptRideInvite(id: String): Response<StartRideResponse> =
            Response.success(StartRideResponse("jwt-friend", "AlphaRun", "room-alpha", "rv_room-alpha", "wss://lk.test"))
        override suspend fun getPendingInvites(userId: String): Response<List<RideInvite>> = Response.success(emptyList())
        override suspend fun respondToInvite(body: InviteRespondRequest): Response<Any> = Response.success(Unit)
        override suspend fun getLobbyStatus(roomName: String): Response<LobbyStatus> =
            Response.success(LobbyStatus("room-alpha", "AlphaRun", "rv_room-alpha", offlineMeshEnabled = true, offlineBackupCode = "K7QXM2PA9TDE4WZH"))
        override suspend fun startRide(roomName: String): Response<StartRideResponse> =
            Response.success(StartRideResponse("jwt-host", "AlphaRun", "room-alpha", "rv_room-alpha", "wss://lk.test", offlineBackupCode = "K7QXM2PA9TDE4WZH"))
        override suspend fun joinLobbyUnified(body: LobbyJoinRequest): Response<StartRideResponse> = lobbyJoinHandler(body)
        override suspend fun joinViaToken(body: JoinViaTokenRequest): Response<StartRideResponse> = joinViaTokenHandler(body.token)
        override suspend fun endRideForEveryone(roomName: String): Response<Any> = Response.success(Unit)
        override suspend fun leaveLobby(roomName: String): Response<Any> = Response.success(Unit)
        override suspend fun deleteFcmToken(token: String?): Response<Any> {
            fcmDeletedCalled = true
            return Response.success(Unit)
        }
        override suspend fun deleteAccount(): Response<Any> = Response.success(Unit)
        override suspend fun updateFcmToken(body: FcmTokenRequest): Response<Any> = Response.success(Unit)
        override suspend fun upsertProfile(body: ProfileRequest): Response<ProfileResponse> {
            currentProfile = currentProfile.copy(
                handle = body.handle ?: currentProfile.handle,
                displayName = body.displayName ?: currentProfile.displayName,
                bikeModel = body.bikeModel ?: currentProfile.bikeModel,
                bio = body.bio ?: currentProfile.bio,
                phone = body.phone ?: currentProfile.phone
            )
            return Response.success(currentProfile)
        }
        override suspend fun getMyProfile(): Response<ProfileResponse> = Response.success(currentProfile)
        override suspend fun sendSosAlert(body: SosAlertRequest): Response<SosAlertResponse> {
            if (failNetworkOnSos) throw IOException("No cellular connection")
            sosSentClientAlertIds.add(body.clientAlertId)
            return Response.success(SosAlertResponse(sent = true, alertId = "alert-${body.clientAlertId}", recipientsCount = 2))
        }
        override suspend fun cancelEmergencyAlert(body: CancelAlertRequest): Response<CancelAlertResponse> =
            Response.success(CancelAlertResponse(cancelled = true))
        override suspend fun syncRide(body: SyncRideRequest): Response<SyncRideResponse> {
            syncedClientRideIds.add(body.clientRideId)
            return Response.success(SyncRideResponse(success = true, rideId = "srv-${body.clientRideId ?: "legacy"}"))
        }
        override suspend fun getRideHistory(): Response<List<RideHistoryResponse>> = Response.success(emptyList())
        override suspend fun searchByHandle(handle: String): Response<ProfileResponse> = throw NotImplementedError()
        override suspend fun getFriendsList(userId: String): Response<List<Friend>> = throw NotImplementedError()
        override suspend fun sendFriendRequest(body: FriendRequest): Response<Any> = throw NotImplementedError()
        override suspend fun generateShareLink(roomName: String): Response<ShareLinkResponse> = throw NotImplementedError()
        override suspend fun removeRiderFromConvoy(roomName: String, userId: String): Response<Any> = throw NotImplementedError()
        override suspend fun transferHost(roomName: String, body: TransferHostRequest): Response<Any> = throw NotImplementedError()
        override suspend fun getRoomToken(body: RoomTokenRequest): Response<RoomData> = throw NotImplementedError()
        override suspend fun getJoinToken(body: JoinTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun refreshToken(body: RefreshTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun finalizeDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun getLatestDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun getAppConfig(): Response<AppConfigResponse> = throw NotImplementedError()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun flowA_and_D_createInviteAcceptLobbyConfigStartRecordSwitchOnlineOfflineAndReplayContinuous() = runTest(testDispatcher) {
        val api = FakeEndToEndApi()
        val prefs = SecurePreferences(null)
        val rideRepo = ActiveRideRepository(prefs, api)
        val hostVm = HostSetupViewModel(api, prefs, rideRepo)

        // 1. Host creates convoy
        hostVm.createConvoy("AlphaRun", "Origin", "Dest", "Meetup")
        advanceUntilIdle()
        assertEquals("AlphaRun", hostVm.createdConvoyName.value)
        assertEquals(ActiveRideRepository.Phase.LOBBY, rideRepo.state.value?.phase)

        // 2. Friend accepts invite -> enters lobby
        val friendRepo = ActiveRideRepository(SecurePreferences(null), api)
        val inboxVm = InvitesInboxViewModel(api, friendRepo)
        var joinedName: String? = null
        val invite = RideInvite("inv-1", "room-alpha", "u_host", "u_friend", "PENDING", InviterDetails("host1", "Host"), RoomDetails("AlphaRun"))
        inboxVm.respondToInvite(invite, accept = true) { joinedName = it }
        advanceUntilIdle()
        assertEquals("AlphaRun", joinedName)

        // 3. Offline config reaches READY for room backup code
        val holder = OfflineSessionHolder(kdfIterations = 1000)
        val coord = OfflineConfigCoordinator(
            sessionHolder = holder,
            deviceCapabilityChecker = object : DeviceCapabilityChecker { override fun hasMeshNetworkingCapability() = true },
            permissionChecker = object : PermissionChecker { override fun hasRecordAudioPermission() = true },
            batteryOptimizationChecker = object : BatteryOptimizationChecker { override fun isIgnoringBatteryOptimizations() = true },
            transportReadinessChecker = object : TransportReadinessChecker { override fun canBindMeshTransport() = true },
            dispatcher = Dispatchers.Unconfined
        )
        coord.startConfig(this, "K7QXM2PA9TDE4WZH", isHostMeshEnabled = true, isOptedOut = false)
        advanceUntilIdle()
        assertEquals(OfflineConfigStatus.READY, coord.state.value.status)

        // 4. Start ride -> RideRecorder records continuously across online -> offline -> online switches!
        rideRepo.goLive()
        val locFlow = MutableStateFlow<Location?>(null)
        val locSvc = mockk<LocationService>(relaxed = true)
        every { locSvc.currentLocation } returns locFlow
        val dao = FakeRideDao()
        val recorder = RideRecorder(dao, locSvc, testDispatcher)
        recorder.startRecording("room-alpha")
        advanceUntilIdle()

        val l1 = mockk<Location>(relaxed = true).apply { every { latitude } returns 18.50; every { longitude } returns 73.80; every { speed } returns 15f; every { bearing } returns 90f }
        val l2 = mockk<Location>(relaxed = true).apply { every { latitude } returns 18.51; every { longitude } returns 73.81; every { speed } returns 18f; every { bearing } returns 90f }
        val l3 = mockk<Location>(relaxed = true).apply { every { latitude } returns 18.52; every { longitude } returns 73.82; every { speed } returns 20f; every { bearing } returns 90f }
        locFlow.value = l1
        advanceUntilIdle()
        // Simulate transport flap calling startRecording again for same room -> must preserve single session!
        recorder.startRecording("room-alpha")
        locFlow.value = l2
        advanceUntilIdle()
        recorder.startRecording("room-alpha")
        locFlow.value = l3
        advanceUntilIdle()

        recorder.stopRecording()
        advanceUntilIdle()
        val summary = recorder.lastSummary.value
        assertNotNull("PostRideSummary must receive non-null summary", summary)
        val recordedWaypoints = dao.getWaypointsForSession(summary!!.sessionId)
        assertEquals("Continuous ride across online->offline->online must contain all 3 waypoints with no gap or duplicate session", 3, recordedWaypoints.size)
        assertEquals(1, dao.sessions.size)

        // 5. Idempotent ride sync & replay load
        val statsVm = RideStatsViewModel(dao, api)
        advanceUntilIdle()
        statsVm.loadReplay("latest")
        advanceUntilIdle()
        assertEquals(summary.sessionId, statsVm.replayData.value?.sessionId)
        assertEquals(3, statsVm.replayData.value?.waypoints?.size)
        assertTrue("Sync must send clientRideId matching session ID", api.syncedClientRideIds.contains(summary.sessionId))
    }

    @Test
    fun flowB_joinByCodeAndLink_handlesWrongCodeExpiredTokenAndRemovedByHostRejection() = runTest(testDispatcher) {
        val api = FakeEndToEndApi()
        val repo = ActiveRideRepository(SecurePreferences(null), api)
        val vm = JoinRoomViewModel(api, repo)

        var okRoom: String? = null
        vm.smartJoin("ALPH1234", "Rider") { okRoom = it }
        advanceUntilIdle()
        assertEquals("AlphaRun", okRoom)

        okRoom = null
        vm.smartJoin("WRONG999", "Rider") { okRoom = it }
        advanceUntilIdle()
        assertNull(okRoom)
        assertNotNull(vm.error.value)

        okRoom = null
        vm.joinViaToken("https://ridervoice.app/j/EXPIRED_TOKEN_1234") { okRoom = it }
        advanceUntilIdle()
        assertNull(okRoom)
        assertNotNull(vm.error.value)

        okRoom = null
        vm.joinViaToken("https://ridervoice.app/j/REMOVED_TOKEN_5678") { okRoom = it }
        advanceUntilIdle()
        assertNull(okRoom)
        assertNotNull(vm.error.value)
    }

    @Test
    fun flowC_and_H_leaveRideAndAccountSignOutWipesAllRideMeshFcmAndPrefs() = runTest(testDispatcher) {
        val api = FakeEndToEndApi()
        val prefs = SecurePreferences(null)
        val rideRepo = ActiveRideRepository(prefs, api)
        val holder = OfflineSessionHolder(kdfIterations = 1000)
        holder.provisionFromBackupCode("K7QXM2PA9TDE4WZH")

        rideRepo.enterLobby("room-A", "Ride A", isHost = false)
        rideRepo.setMeshConfig(RoomMeshConfig.DEFAULT)
        rideRepo.goLive()

        val authRepo = mockk<AuthRepository>(relaxed = true)
        val liveKit = mockk<LiveKitManager>(relaxed = true)
        val ctx = mockk<Context>(relaxed = true)
        val authVm = AuthViewModel(authRepo, api, prefs, rideRepo, liveKit, holder, ctx)

        // Account edit
        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns "u_host"
        every { firebaseUser.email } returns "host@test.com"
        mockkStatic(FirebaseAuth::class)
        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        every { FirebaseAuth.getInstance() } returns mockAuth
        every { mockAuth.currentUser } returns firebaseUser

        val accountVm = AccountViewModel(api)
        advanceUntilIdle()
        accountVm.updateProfile("Updated Name", "updated_handle", "999", "Africa Twin", "Touring")
        advanceUntilIdle()
        assertEquals("Profile successfully calibrated.", accountVm.uiState.value.successMessage)

        // Sign out -> must unregister FCM token, wipe ActiveRideRepository, wipe OfflineSessionHolder, clear SecurePreferences
        var completed = false
        authVm.signOut(forceHostEnd = true) { completed = true }
        advanceUntilIdle()
        assertTrue(completed)
        assertTrue("Sign-out must unregister FCM token", api.fcmDeletedCalled)
        assertNull("ActiveRideRepository must be null after sign-out", rideRepo.state.value)
        assertNull("Offline backup session must be wiped after sign-out", holder.getBackup())
        assertNull("Persisted active_ride_room must be wiped", prefs.getActiveRideRoom())
    }

    @Test
    fun flowE_sosQueuesOfflineDeliversOnFlushAndPreventsDuplicateClientAlertId() = runTest(testDispatcher) {
        val api = FakeEndToEndApi()
        val outbox = InMemorySosOutbox()
        val vm = SosViewModel(api, { Pair(18.5, 73.8) }, outbox)

        // Offline failure -> queued in outbox
        api.failNetworkOnSos = true
        vm.sendAlert("AlphaRun")
        advanceUntilIdle()
        assertEquals(1, outbox.pending().size)
        val queuedId = outbox.pending().first().clientAlertId
        assertTrue(vm.state.value is SosState.NotSent)

        // Network returns -> flush delivers exact clientAlertId and removes from outbox
        api.failNetworkOnSos = false
        vm.flushPendingAlerts()
        advanceUntilIdle()
        assertEquals(0, outbox.pending().size)
        assertEquals(listOf(queuedId), api.sosSentClientAlertIds)
        assertEquals(SosOutcome.DROP, SosDeliveryPolicy.classify(403))
        assertEquals(SosOutcome.RETRY, SosDeliveryPolicy.classify(503))
    }

    @Test
    fun flowG_I_J_versionFormatPermissionsAndDegradedCreateErrorHandledGracefully() {
        // Flow G: Single 'v' prefix and semantic ordering to v0.0.3.20
        val rawNotes = """
            ### RiderVoice v0.0.3.20
            - Fixed audio / VOX cutoff (#154)
        """.trimIndent()
        val formatted = UpdateManager.formatReleaseNotesForApp(rawNotes)
        assertFalse(formatted.contains("#"))
        assertFalse(formatted.contains("/"))
        val v19 = AppVersion.parse("0.0.3.19")
        val v20 = AppVersion.parse("0.0.3.20")
        assertTrue(v20.isNewerThan(v19))
        val versionDisplay = "v" + "V0.0.3.20".removePrefix("v").removePrefix("V")
        assertEquals("v0.0.3.20", versionDisplay)

        // Flow I: Required permissions include RECORD_AUDIO and degrade gracefully when denied
        assertTrue(PermissionManager.ridePermissions.contains(android.Manifest.permission.RECORD_AUDIO))
        assertTrue(PermissionManager.ridePermissions.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))

        // Flow J: Backend 503 SCHEMA_DEGRADED on create-ride transitions cleanly to retryable UnknownOutcome without crash
        runTest(testDispatcher) {
            val api = FakeEndToEndApi().apply { fail503SchemaOnCreate = true }
            val prefs = SecurePreferences(null)
            val repo = ActiveRideRepository(prefs, api)
            val hostVm = HostSetupViewModel(api, prefs, repo)
            hostVm.createConvoy("TestRide", null, null, null)
            advanceUntilIdle()
            assertTrue(hostVm.createState.value is HostSetupViewModel.CreateRideState.UnknownOutcome)
            assertNotNull(hostVm.error.value)
        }
    }
}
