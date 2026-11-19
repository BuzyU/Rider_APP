package com.ridervoice.e2e

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.audio.AudioDeviceRouter
import com.ridervoice.audio.AudioManagerGateway
import com.ridervoice.mesh.RoomMeshConfig
import com.ridervoice.mesh.transport.AudioRoute as MeshAudioRoute
import com.ridervoice.mesh.offline.BatteryOptimizationChecker
import com.ridervoice.mesh.offline.DeviceCapabilityChecker
import com.ridervoice.mesh.offline.OfflineConfigCoordinator
import com.ridervoice.mesh.offline.OfflineConfigStatus
import com.ridervoice.mesh.offline.PermissionChecker
import com.ridervoice.mesh.offline.TransportReadinessChecker
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.ui.MeshBottomBar
import com.ridervoice.mesh.ui.MicPermissionCard
import com.ridervoice.models.Participant
import com.ridervoice.models.RideSession
import com.ridervoice.models.SosAlertRequest
import com.ridervoice.network.ApiService
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.permissions.PermissionManager
import com.ridervoice.security.AuthRepository
import com.ridervoice.security.SecurePreferences
import com.ridervoice.services.LocationService
import com.ridervoice.services.MeshForegroundService
import com.ridervoice.services.PersistentSosOutbox
import com.ridervoice.services.ThermalManager
import com.ridervoice.services.VoiceForegroundService
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.screens.EmergencyAlertContent
import com.ridervoice.ui.screens.SosScreen
import com.ridervoice.ui.screens.room.RideTopBar
import com.ridervoice.ui.screens.room.TalkControls
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.viewmodels.AuthViewModel
import com.ridervoice.ui.viewmodels.SosState
import com.ridervoice.update.AppReleaseInfo
import com.ridervoice.update.UpdateManager
import com.ridervoice.update.UpdateUiState
import com.ridervoice.utils.RvLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.security.MessageDigest

/**
 * WP-173 (flows h, i, g), WP-174 (timer removal), and WP-175 (RvLog [METRIC] [RvLog] strings).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class Wp173To175RegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun resolveSourceFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/java/com/ridervoice/$relativePath"),
            File("app/src/main/java/com/ridervoice/$relativePath"),
            File("mobile-app/app/src/main/java/com/ridervoice/$relativePath")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not resolve source file $relativePath from ${File(".").absolutePath}")
    }

    @Test
    fun wp173_flowH_signOutAndAccountDeletion_wipeAllRideMeshTokenAndPreferenceStores() = runTest(testDispatcher) {
        mockkStatic(FirebaseAuth::class)
        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        every { mockAuth.currentUser } returns null
        every { FirebaseAuth.getInstance() } returns mockAuth

        val securePrefs = SecurePreferences(app)
        securePrefs.saveToken("jwt-secret-token")
        securePrefs.saveRoomName("convoy-wipe")
        securePrefs.saveActiveRide("convoy-wipe", true, "rv_room-wipe")

        val api = mockk<ApiService>(relaxed = true)
        val rideRepo = ActiveRideRepository(securePrefs, api)
        rideRepo.enterLobby("room-wipe", "convoy-wipe", isHost = true)
        rideRepo.setMeshConfig(RoomMeshConfig.DEFAULT)
        rideRepo.goLive()
        val holder = OfflineSessionHolder(kdfIterations = 1000)
        assertTrue(holder.provisionFromBackupCode("K7QXM2PA9TDE4WZH"))
        RideSession.livekitToken = "lk-tok-wipe"
        RideSession.activeRoomId = "room-wipe"

        // Populate SharedPreferences stores that must also be wiped on sign-out & account deletion
        app.getSharedPreferences("mesh_runtime_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("offline_mesh_enabled", true)
            .putBoolean("auto_fallback_enabled", true)
            .apply()
        app.getSharedPreferences("user_profile", Context.MODE_PRIVATE)
            .edit()
            .putString("display_name", "Ghost Rider")
            .apply()
        val outbox = PersistentSosOutbox(app, com.google.gson.Gson())
        outbox.enqueue(SosAlertRequest(roomName = "convoy-wipe", lat = 18.5, lng = 73.8))
        assertEquals(1, outbox.pending().size)

        val liveKit = mockk<LiveKitManager>(relaxed = true)
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val authVm = AuthViewModel(authRepo, api, securePrefs, rideRepo, liveKit, holder, app)

        var signOutDone = false
        authVm.signOut(forceHostEnd = true) { signOutDone = true }
        advanceUntilIdle()

        assertTrue("signOut completion callback must run", signOutDone)
        assertNull("SecurePreferences token must be null", securePrefs.getToken())
        assertNull("SecurePreferences active room must be null", securePrefs.getActiveRideRoom())
        assertNull("ActiveRideRepository state must be null", rideRepo.state.value)
        assertNull("OfflineSessionHolder must be cleared", holder.getBackup())
        assertEquals("RideSession token must be cleared", "", RideSession.livekitToken)
        assertFalse(
            "mesh_runtime_prefs must be cleared on sign-out",
            app.getSharedPreferences("mesh_runtime_prefs", Context.MODE_PRIVATE).all.isNotEmpty()
        )
        assertFalse(
            "user_profile prefs must be cleared on sign-out",
            app.getSharedPreferences("user_profile", Context.MODE_PRIVATE).all.isNotEmpty()
        )
        assertTrue(
            "sos_outbox must be cleared on sign-out",
            outbox.pending().isEmpty()
        )

        // Account deletion contract across ApiService, AuthRepository, and AuthViewModel
        val apiDelete = ApiService::class.java.methods.firstOrNull { it.name == "deleteAccount" }
        val repoDelete = AuthRepository::class.java.methods.firstOrNull { it.name == "deleteAccount" }
        val vmDelete = AuthViewModel::class.java.methods.firstOrNull { it.name == "deleteAccount" }
        assertNotNull("ApiService must expose deleteAccount()", apiDelete)
        assertNotNull("AuthRepository must expose deleteAccount()", repoDelete)
        assertNotNull("AuthViewModel must expose deleteAccount()", vmDelete)

        // Populate mesh_identity_prefs and verify account deletion invokes API & clears everything
        app.getSharedPreferences("mesh_identity_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("device_id", "dev-delete-1")
            .apply()
        app.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_etag", "etag-1")
            .apply()
        app.getSharedPreferences("mesh_runtime_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("offline_mesh_enabled", true)
            .apply()

        var deletedDone = false
        val onDeleteComplete: () -> Unit = { deletedDone = true }
        // Invoke deleteAccount(forceHostEnd=true, onHostConfirmationRequired=null, onDeleteComplete=...)
        vmDelete!!.invoke(authVm, true, null, onDeleteComplete)
        advanceUntilIdle()

        assertTrue("deleteAccount completion callback must fire", deletedDone)
        io.mockk.coVerify(exactly = 1) { api.deleteAccount() }
        io.mockk.coVerify(exactly = 1) { authRepo.deleteAccount() }
        assertFalse(
            "mesh_identity_prefs must be cleared on account deletion",
            app.getSharedPreferences("mesh_identity_prefs", Context.MODE_PRIVATE).all.isNotEmpty()
        )
        assertFalse(
            "app_update_prefs must be cleared on account deletion",
            app.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE).all.isNotEmpty()
        )
    }

    @Test
    fun wp173_flowI_firstRunPermissionsDeniedThenGrantedOneByOne_degradesWithoutCrash() {
        val shadowApp = Shadows.shadowOf(app)
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        // Stage 0: all 4 permissions denied
        shadowApp.denyPermissions(*perms)

        val locSvc = LocationService(app, mockk<ThermalManager>(relaxed = true))
        val gateway = mockk<AudioManagerGateway>(relaxed = true)
        every { gateway.isModernRoutingSupported() } returns true
        every { gateway.getAvailableCommunicationDevices() } returns emptyList()
        val router = AudioDeviceRouter(app, null, gateway, CoroutineScope(Dispatchers.Unconfined))

        val stages = listOf(
            null to "All Denied (0/4)",
            Manifest.permission.RECORD_AUDIO to "+Mic (1/4)",
            Manifest.permission.ACCESS_FINE_LOCATION to "+Location (2/4)",
            Manifest.permission.POST_NOTIFICATIONS to "+Notifications (3/4)",
            Manifest.permission.BLUETOOTH_CONNECT to "+Bluetooth (4/4)"
        )

        for ((grantNow, stageLabel) in stages) {
            if (grantNow != null) {
                shadowApp.grantPermissions(grantNow)
            }

            val hasMic = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val hasLoc = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            // PermissionManager invariants never throw at any stage
            assertTrue("requiredPermissions non-empty at $stageLabel", PermissionManager.requiredPermissions.isNotEmpty())
            assertTrue("ridePermissions non-empty at $stageLabel", PermissionManager.ridePermissions.isNotEmpty())

            // Services & routers degrade gracefully without throwing SecurityException
            locSvc.startTracking()
            assertEquals("LocationService tracking matches grant state at $stageLabel", hasLoc, locSvc.isTracking.value)
            locSvc.stopTracking()

            VoiceForegroundService.start(app)
            MeshForegroundService.start(app)
            router.reEvaluatePriority(skipBluetooth = false)

            // OfflineConfigCoordinator degrades to permission error without mic and reaches READY once granted
            val holder = OfflineSessionHolder(kdfIterations = 1000)
            val coord = OfflineConfigCoordinator(
                sessionHolder = holder,
                deviceCapabilityChecker = object : DeviceCapabilityChecker { override fun hasMeshNetworkingCapability() = true },
                permissionChecker = object : PermissionChecker { override fun hasRecordAudioPermission() = hasMic },
                batteryOptimizationChecker = object : BatteryOptimizationChecker { override fun isIgnoringBatteryOptimizations() = true },
                transportReadinessChecker = object : TransportReadinessChecker { override fun canBindMeshTransport() = true },
                dispatcher = Dispatchers.Unconfined
            )
            coord.startConfig(CoroutineScope(Dispatchers.Unconfined), "K7QXM2PA9TDE4WZH", isHostMeshEnabled = true, isOptedOut = false)
            if (!hasMic) {
                assertFalse("OfflineConfig must not reach READY without mic at $stageLabel", coord.state.value.status == OfflineConfigStatus.READY)
            } else {
                assertEquals("OfflineConfig must reach READY once mic granted at $stageLabel", OfflineConfigStatus.READY, coord.state.value.status)
            }
        }

        // Compose screens render without crashing under zero permissions denied state
        shadowApp.denyPermissions(*perms)
        composeRule.setContent {
            RiderVoiceTheme {
                MicPermissionCard()
                TalkControls(
                    isMicEnabled = false,
                    isDeafened = false,
                    isPttPressed = false,
                    isVoxOpen = false,
                    onToggleMic = {},
                    onToggleDeafen = {},
                    onPttPressStart = {},
                    onPttPressEnd = {},
                    onSosClick = {}
                )
                MeshBottomBar(
                    isMicEnabled = false,
                    isDeafened = false,
                    activeRoute = MeshAudioRoute.SPEAKER,
                    isVoxEnabled = false,
                    isPttActive = false,
                    onToggleMic = {},
                    onToggleDeafen = {},
                    onCycleAudioRoute = {},
                    onToggleVox = {},
                    onPttPressChange = {},
                    onLeave = {}
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun wp173_flowG_updateManager_checkDownloadChecksumAndUnknownSourcesInstallState() = runBlocking {
        // Fake PackageManager to verify RequestUnknownSourcesPermission -> Installing state transitions
        var allowUnknownSources = false
        val fakePm = mockk<PackageManager>(relaxed = true)
        val installedPkgInfo = PackageInfo().apply {
            packageName = app.packageName
            versionName = "0.0.3.20"
            @Suppress("DEPRECATION")
            versionCode = 1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode = 1L
            }
        }
        val archivePkgInfo = PackageInfo().apply {
            packageName = app.packageName
            versionName = "0.0.9.9"
            @Suppress("DEPRECATION")
            versionCode = 99
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode = 99L
            }
        }
        every { fakePm.canRequestPackageInstalls() } answers { allowUnknownSources }
        every { fakePm.getPackageInfo(any<String>(), any<Int>()) } returns installedPkgInfo
        @Suppress("DEPRECATION")
        every { fakePm.getPackageArchiveInfo(any<String>(), any<Int>()) } returns archivePkgInfo
        @Suppress("DEPRECATION")
        every { fakePm.resolveContentProvider(any<String>(), any<Int>()) } returns null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            every { fakePm.getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>()) } returns installedPkgInfo
            every { fakePm.getPackageArchiveInfo(any<String>(), any<PackageManager.PackageInfoFlags>()) } returns archivePkgInfo
            every { fakePm.resolveContentProvider(any<String>(), any<PackageManager.ComponentInfoFlags>()) } returns null
        }
        val testContext = object : ContextWrapper(app) {
            override fun getPackageManager(): PackageManager = fakePm
            override fun getApplicationContext(): Context = this
        }

        val payloadBytes = "fake-apk-payload-for-test".toByteArray()
        val expectedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(payloadBytes)
            .joinToString("") { "%02x".format(it) }

        var apiMode = "304_EMPTY"
        val releaseJson = """
            {
              "tag_name": "v0.0.9.9",
              "name": "RiderVoice v0.0.9.9",
              "body": "### Release Notes\n- Added field test improvements",
              "published_at": "2026-11-19T10:00:00Z",
              "assets": [
                {
                  "name": "Rider_APP-v0.0.9.9.apk",
                  "size": ${payloadBytes.size},
                  "browser_download_url": "https://github.com/BuzyU/Rider_APP/releases/download/v0.0.9.9/Rider_APP-v0.0.9.9.apk"
                },
                {
                  "name": "Rider_APP-v0.0.9.9.apk.sha256",
                  "size": 70,
                  "browser_download_url": "https://github.com/BuzyU/Rider_APP/releases/download/v0.0.9.9/Rider_APP-v0.0.9.9.apk.sha256"
                }
              ]
            }
        """.trimIndent()

        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when {
                    url.contains("api.github.com") && apiMode == "304_EMPTY" ->
                        okhttp3.Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(304).message("Not Modified")
                            .body("".toResponseBody("application/json".toMediaType())).build()
                    url.contains("api.github.com") && apiMode == "RELEASE_200" ->
                        okhttp3.Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .header("ETag", "\"etag-0099\"")
                            .body(releaseJson.toResponseBody("application/json".toMediaType())).build()
                    url.endsWith(".sha256") ->
                        okhttp3.Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body("$expectedSha256  Rider_APP-v0.0.9.9.apk".toResponseBody("text/plain".toMediaType())).build()
                    url.endsWith(".apk") ->
                        okhttp3.Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .header("Content-Length", payloadBytes.size.toString())
                            .body(payloadBytes.toResponseBody("application/vnd.android.package-archive".toMediaType())).build()
                    else ->
                        okhttp3.Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                            .body("".toResponseBody()).build()
                }
            }
            .build()

        app.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        File(app.cacheDir, "updates").deleteRecursively()
        val manager = UpdateManager(testContext)
        UpdateManager::class.java.getDeclaredField("httpClient").apply { isAccessible = true; set(manager, fakeClient) }
        UpdateManager::class.java.getDeclaredField("downloadHttpClient").apply { isAccessible = true; set(manager, fakeClient) }

        val scope = CoroutineScope(Dispatchers.IO)
        try {
            // 1. 304 with empty cache must emit v0.0.3.20 default notes (NOT stale v0.0.3.19 notes)
            manager.checkForUpdates(scope, forceRefresh = true)
            val upToDateState = withTimeout(5_000) {
                manager.uiState.first { it is UpdateUiState.UpToDate || it is UpdateUiState.Error }
            }
            assertTrue("Expected UpToDate on 304 empty cache, got $upToDateState", upToDateState is UpdateUiState.UpToDate)
            val notes = (upToDateState as UpdateUiState.UpToDate).releaseNotes ?: ""
            assertEquals(UpdateManager.DEFAULT_V00320_NOTES, notes)

            // 2. Newer 0.0.9.9 release -> UpdateAvailable -> ConfirmDownload -> bad SHA-256 rejected
            apiMode = "RELEASE_200"
            manager.checkForUpdates(scope, forceRefresh = true)
            val availState = withTimeout(5_000) {
                manager.uiState.first { it is UpdateUiState.UpdateAvailable || it is UpdateUiState.Error }
            }
            assertTrue("Expected UpdateAvailable for 0.0.9.9, got $availState", availState is UpdateUiState.UpdateAvailable)
            val relInfo = (availState as UpdateUiState.UpdateAvailable).releaseInfo
            manager.promptDownloadConfirmation(relInfo)
            assertTrue(manager.uiState.value is UpdateUiState.ConfirmDownload)

            // Corrupt checksum download must reject with Error
            val corruptRelease = relInfo.copy(sha256Checksum = "0".repeat(64))
            manager.startDownload(corruptRelease, scope)
            val errState = withTimeout(5_000) {
                manager.uiState.first { it is UpdateUiState.Error || it is UpdateUiState.ReadyToInstall }
            }
            assertTrue("Corrupt checksum must transition to Error, got $errState", errState is UpdateUiState.Error)
            assertTrue((errState as UpdateUiState.Error).message.contains("checksum mismatch", ignoreCase = true))

            // Valid checksum download must transition to ReadyToInstall
            manager.startDownload(relInfo, scope)
            val readyState = withTimeout(5_000) {
                manager.uiState.first { it is UpdateUiState.ReadyToInstall || it is UpdateUiState.Error }
            }
            assertTrue("Valid checksum must transition to ReadyToInstall, got $readyState", readyState is UpdateUiState.ReadyToInstall)
            val readyApk = (readyState as UpdateUiState.ReadyToInstall).apkFile
            assertTrue("Downloaded APK must exist at ${readyApk.absolutePath}", readyApk.exists())

            // 3. Install permission state handling: denied -> RequestUnknownSourcesPermission; granted -> Installing / Installer launch
            allowUnknownSources = false
            manager.installUpdate(readyApk, relInfo)
            assertTrue(
                "Missing unknown-sources permission must emit RequestUnknownSourcesPermission, got ${manager.uiState.value}",
                manager.uiState.value is UpdateUiState.RequestUnknownSourcesPermission
            )

            allowUnknownSources = true
            manager.installUpdate(readyApk, relInfo)
            assertTrue(
                "Granted unknown-sources permission must advance past RequestUnknownSourcesPermission (got ${manager.uiState.value})",
                manager.uiState.value is UpdateUiState.Installing || manager.uiState.value is UpdateUiState.Error
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun wp174_noAvoidableTimersRemainInLiveKitRouterOrLobbyViewModel() {
        val lkSrc = resolveSourceFile("network/LiveKitManager.kt").readText()
        val routerSrc = resolveSourceFile("audio/AudioDeviceRouter.kt").readText()
        val lobbySrc = resolveSourceFile("ui/viewmodels/LobbyViewModel.kt").readText()

        assertFalse(
            "LiveKitManager.republishAudioTrackForRouteChange must not poll with delay(100)",
            lkSrc.contains("while (audioDeviceRouter.routerState.value != RouterState.ACTIVE")
        )
        assertFalse(
            "AudioDeviceRouter.startVerificationLogging must not schedule delayed verification coroutines (500/1000/1500ms)",
            routerSrc.contains("logDiagnosticsCheck(\"500ms\"") || routerSrc.contains("logDiagnosticsCheck(\"1500ms\"")
        )
        assertFalse(
            "LobbyViewModel 401 refresh must not wait delay(1000L)",
            lobbySrc.contains("if (refreshed) {\n                                        delay(1000L)") ||
                lobbySrc.contains("if (refreshed) {\r\n                                        delay(1000L)")
        )
    }

    @Test
    fun wp175_rvLogMetric_emitsExactPrefixAndRealAudioTokens() {
        ShadowLog.clear()
        RvLog.resetAudioCounters()

        RvLog.recordGateOpen(0.01f, 0.03f, 0.0f)
        RvLog.recordGateClose("hold_expiry", 0.01f, 0.02f, 400L, 120L)
        RvLog.recordGateClose("max_open_guard", 0.01f, 0.02f, 30000L, 80L)
        RvLog.recordRouteRepublish("executed", "BLUETOOTH_SCO", "route_change")
        RvLog.recordPlayoutCounters(1L, 2L, 0L)

        val voxLogs = ShadowLog.getLogsForTag("RV_VOX").map { it.msg }
        val audioLogs = ShadowLog.getLogsForTag("RV_AUDIO").map { it.msg }
        val meshAudioLogs = ShadowLog.getLogsForTag("RV_MESH_AUDIO").map { it.msg }

        assertTrue("RV_VOX metric must start with [METRIC] [RvLog]: $voxLogs", voxLogs.any { it.contains("[METRIC]") && it.contains("RvLog") && it.contains("gate=OPEN") })
        assertTrue("RV_VOX must log hold_expiry: $voxLogs", voxLogs.any { it.contains("[METRIC]") && it.contains("reason=hold_expiry") })
        assertTrue("RV_VOX must log max_open_guard: $voxLogs", voxLogs.any { it.contains("[METRIC]") && it.contains("reason=max_open_guard") })
        assertTrue("RV_AUDIO must log [METRIC] [RvLog] route_republish: $audioLogs", audioLogs.any { it.contains("[METRIC]") && it.contains("RvLog") && it.contains("route_republish") })
        assertTrue("RV_MESH_AUDIO must log [METRIC] [RvLog] playout: $meshAudioLogs", meshAudioLogs.any { it.contains("[METRIC]") && it.contains("RvLog") && it.contains("underruns=") && it.contains("concealed=") })
    }
}
