package com.ridervoice.mesh

import com.ridervoice.mesh.offline.BatteryOptimizationChecker
import com.ridervoice.mesh.offline.DeviceCapabilityChecker
import com.ridervoice.mesh.offline.OfflineConfigCoordinator
import com.ridervoice.mesh.offline.OfflineConfigStatus
import com.ridervoice.mesh.offline.PermissionChecker
import com.ridervoice.mesh.offline.TransportReadinessChecker
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.models.*
import com.ridervoice.network.ApiService
import com.ridervoice.security.SecurePreferences
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.viewmodels.AppSettingsState
import com.ridervoice.ui.viewmodels.SettingsViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * WP-158 & WP-163: Verifies per-room global-flag mirroring in App/RoomMeshFlagMirror,
 * lobby provisioning independence, mid-ride process death restoration, room isolation,
 * legacy false pref immunity, and fresh-install defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMeshFlagMirrorTest {

    private class InMemoryRuntimeSettings(
        initialEnabled: Boolean = false
    ) : MeshRuntimeSettings {
        private val _flow = MutableStateFlow(
            MeshSettingsSnapshot(initialEnabled, initialEnabled, initialEnabled)
        )
        override val settingsFlow: StateFlow<MeshSettingsSnapshot> = _flow.asStateFlow()
        override val offlineMeshEnabled: Boolean get() = _flow.value.offlineMeshEnabled
        override val automaticFallbackEnabled: Boolean get() = _flow.value.automaticFallbackEnabled
        override val warmStandbyEnabled: Boolean get() = _flow.value.warmStandbyEnabled

        override fun setOfflineMeshEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(offlineMeshEnabled = v)
        }
        override fun setAutomaticFallbackEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(automaticFallbackEnabled = v)
        }
        override fun setWarmStandbyEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(warmStandbyEnabled = v)
        }
    }

    private class FakeLobbyApi : ApiService {
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
        override suspend fun getLobbyStatus(roomName: String): Response<LobbyStatus> =
            Response.success(LobbyStatus(roomId = roomName, convoyName = roomName, offlineMeshEnabled = true, isRideStarted = true))
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
        override suspend fun refreshToken(body: RefreshTokenRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun joinLobbyUnified(body: LobbyJoinRequest): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun leaveLobby(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun acceptRideInvite(id: String): Response<StartRideResponse> = throw NotImplementedError()
        override suspend fun getAppConfig(): Response<AppConfigResponse> = throw NotImplementedError()
        override suspend fun deleteFcmToken(token: String?): Response<Any> = Response.success(Unit)
        override suspend fun finalizeDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
        override suspend fun getLatestDiagnosticRun(roomName: String): Response<Any> = throw NotImplementedError()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    private fun ActiveRideRepository.ActiveRide?.toMirrorSnapshot(): RoomMeshMirrorSnapshot? =
        this?.let { ride ->
            val cfg = ride.meshConfig ?: RoomMeshConfig.DEFAULT
            RoomMeshMirrorSnapshot(
                isLive = ride.phase == ActiveRideRepository.Phase.LIVE,
                offlineMeshEnabled = cfg.offlineMeshEnabled,
                optOut = cfg.optOut,
                automaticFallbackEnabled = cfg.automaticFallbackEnabled,
                warmStandbyEnabled = cfg.warmStandbyEnabled
            )
        }

    @Test
    fun a_lobbyOfflineProvisioningSucceedsWhileGlobalRuntimeFlagsAreFalse() = runTest {
        val prefs = SecurePreferences(null)
        val settings = InMemoryRuntimeSettings(initialEnabled = false)
        OfflineMeshGate.install(settings)
        val repo = ActiveRideRepository(prefs, FakeLobbyApi())
        repo.enterLobby("room-lobby-a", "Mountain Pass", isHost = true)

        RoomMeshFlagMirror.sync(repo.state.value.toMirrorSnapshot(), settings)
        assertFalse("Global flags must remain OFF in Phase.LOBBY", settings.offlineMeshEnabled)
        assertFalse(settings.automaticFallbackEnabled)
        assertFalse(settings.warmStandbyEnabled)

        // Provision backup code during LOBBY even though global MeshRuntimeSettings are false
        val holder = OfflineSessionHolder(kdfIterations = 1000)
        val coordinator = OfflineConfigCoordinator(
            sessionHolder = holder,
            deviceCapabilityChecker = object : DeviceCapabilityChecker { override fun hasMeshNetworkingCapability() = true },
            permissionChecker = object : PermissionChecker { override fun hasRecordAudioPermission() = true },
            batteryOptimizationChecker = object : BatteryOptimizationChecker { override fun isIgnoringBatteryOptimizations() = true },
            transportReadinessChecker = object : TransportReadinessChecker { override fun canBindMeshTransport() = true },
            dispatcher = Dispatchers.Unconfined
        )
        coordinator.startConfig(
            scope = this,
            backupCode = "K7QXM2PA9TDE4WZH",
            isHostMeshEnabled = true,
            isOptedOut = false
        )
        advanceUntilIdle()
        assertEquals(OfflineConfigStatus.READY, coordinator.state.value.status)
        assertNotNull("Backup session must be provisioned during lobby phase", holder.getBackup())
    }

    @Test
    fun b_midRideProcessDeathRestoresPerRoomFlagsSynchronouslyBeforeSwitching() {
        // Shared in-memory prefs simulating disk persistence across process restart
        val persistedPrefs = SecurePreferences(null)
        val settingsBeforeDeath = InMemoryRuntimeSettings(initialEnabled = false)
        val repoBeforeDeath = ActiveRideRepository(persistedPrefs, FakeLobbyApi())

        repoBeforeDeath.enterLobby("room-live-1", "Konkan Coast", isHost = false)
        repoBeforeDeath.setMeshConfig(
            RoomMeshConfig(
                offlineMeshEnabled = true,
                automaticFallbackEnabled = true,
                warmStandbyEnabled = true,
                configCompleted = true,
                status = OfflineConfigStatus.READY
            )
        )
        repoBeforeDeath.goLive()
        RoomMeshFlagMirror.sync(repoBeforeDeath.state.value.toMirrorSnapshot(), settingsBeforeDeath)
        assertTrue(settingsBeforeDeath.offlineMeshEnabled)

        // Simulate process death: brand new ActiveRideRepository + brand new MeshRuntimeSettings starting false
        val settingsAfterRestart = InMemoryRuntimeSettings(initialEnabled = false)
        OfflineMeshGate.install(settingsAfterRestart)
        val repoAfterRestart = ActiveRideRepository(persistedPrefs, FakeLobbyApi())

        // Synchronous boot mirror (matches App.onCreate before MeshAutoSwitchLauncher.start)
        RoomMeshFlagMirror.sync(repoAfterRestart.state.value.toMirrorSnapshot(), settingsAfterRestart)

        assertNotNull(repoAfterRestart.state.value)
        assertEquals(ActiveRideRepository.Phase.LIVE, repoAfterRestart.state.value?.phase)
        assertTrue("offlineMeshEnabled must be restored synchronously on restart mid-ride", settingsAfterRestart.offlineMeshEnabled)
        assertTrue("automaticFallbackEnabled must be restored synchronously on restart", settingsAfterRestart.automaticFallbackEnabled)
        assertTrue("warmStandbyEnabled must be restored synchronously on restart", settingsAfterRestart.warmStandbyEnabled)
        assertTrue(OfflineMeshGate.isOfflineAllowed(buildFlag = true))
    }

    @Test
    fun c_leavingRoomResetsFlagsAndSecondRoomNeverInheritsFirstRoomConfig() {
        val prefs = SecurePreferences(null)
        val settings = InMemoryRuntimeSettings(initialEnabled = false)
        val repo = ActiveRideRepository(prefs, FakeLobbyApi())

        // Room 1 opted out
        repo.enterLobby("room-1", "OptedOutRide", isHost = true)
        repo.setMeshConfig(RoomMeshConfig.DISABLED)
        repo.goLive()
        RoomMeshFlagMirror.sync(repo.state.value.toMirrorSnapshot(), settings)
        assertFalse(settings.offlineMeshEnabled)

        // Leave Room 1 -> flags cleared
        repo.clear(ActiveRideRepository.ClearReason.LEFT)
        RoomMeshFlagMirror.sync(repo.state.value.toMirrorSnapshot(), settings)
        assertFalse(settings.offlineMeshEnabled)
        assertFalse(settings.automaticFallbackEnabled)
        assertFalse(settings.warmStandbyEnabled)

        // Enter Room 2 without explicitly setting DISABLED -> must NOT inherit Room 1's DISABLED config!
        repo.enterLobby("room-2", "FreshRide", isHost = false)
        assertNull("New room must not inherit previous room's meshConfig", repo.state.value?.meshConfig)
        repo.goLive()
        RoomMeshFlagMirror.sync(repo.state.value.toMirrorSnapshot(), settings)
        assertTrue("Fresh room must get default offline mesh ON", settings.offlineMeshEnabled)
        assertTrue(settings.automaticFallbackEnabled)
        assertTrue(settings.warmStandbyEnabled)
    }

    @Test
    fun d_oldPersistedUserFalseValueCannotDisableOfflineForNewRoom() {
        val prefs = SecurePreferences(null)
        // Simulate legacy user setting where offline_mesh_enabled = false was persisted on disk
        val settings = InMemoryRuntimeSettings(initialEnabled = false)
        assertFalse(settings.offlineMeshEnabled)

        val repo = ActiveRideRepository(prefs, FakeLobbyApi())
        repo.enterLobby("room-new", "HighwayPack", isHost = true)
        // Even in LOBBY, legacy false is irrelevant; upon going LIVE, per-room default overrides legacy false
        repo.goLive()
        RoomMeshFlagMirror.sync(repo.state.value.toMirrorSnapshot(), settings)

        assertTrue("Per-room LIVE transition must override legacy persisted offline=false", settings.offlineMeshEnabled)
        assertTrue(settings.automaticFallbackEnabled)
        assertTrue(settings.warmStandbyEnabled)
    }

    @Test
    fun freshInstallDefaults_allRequiredOnlineAndOfflineSettingsOnByDefault() {
        val freshPrefs = SecurePreferences(null)
        val settingsVm = SettingsViewModel(
            securePrefs = freshPrefs,
            uploadManager = mockk(relaxed = true)
        )
        val state: AppSettingsState = settingsVm.settingsState.value

        assertTrue("VOX / openMic must be ON by default", state.openMic)
        assertTrue("Noise cancellation / suppression must be ON by default", state.noiseCancellation)
        assertTrue("Hardware PTT must be ON by default", state.hardwarePtt)
        assertTrue("Hybrid VOX must be ON by default", state.hybridVox)
        assertTrue("Diagnostics sharing when host enables must default ON", state.shareDiagnosticsWhenHostEnabled)

        // Per-room offline backup + auto fallback + warm standby default ON
        assertTrue(RoomMeshConfig.DEFAULT.offlineMeshEnabled)
        assertTrue(RoomMeshConfig.DEFAULT.automaticFallbackEnabled)
        assertTrue(RoomMeshConfig.DEFAULT.warmStandbyEnabled)
        assertFalse(RoomMeshConfig.DEFAULT.optOut)
        assertTrue("Release build flag OFFLINE_MESH_BUILD_ENABLED must default ON", com.ridervoice.BuildConfig.OFFLINE_MESH_BUILD_ENABLED)
        assertTrue("Release build flag PER_ROOM_OFFLINE_CONFIG_ENABLED must default ON", com.ridervoice.BuildConfig.PER_ROOM_OFFLINE_CONFIG_ENABLED)
    }
}
