package com.ridervoice.mesh.link

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.ridervoice.di.MeshModule
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.models.LobbyHost
import com.ridervoice.models.LobbyStatus
import com.ridervoice.network.ApiService
import com.ridervoice.permissions.PermissionManager
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.viewmodels.LobbyViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class WifiDirectGroupManagerTest {

    private class RecordingWifiDirectController : WifiDirectGroupController {
        private val _status = MutableStateFlow(WifiDirectStatus.IDLE)
        override val status: StateFlow<WifiDirectStatus> = _status.asStateFlow()

        private val _isGroupConnected = MutableStateFlow(false)
        override val isGroupConnected: StateFlow<Boolean> = _isGroupConnected.asStateFlow()

        override val p2pEvents: Flow<WifiChangeEvent> = emptyFlow()

        val activeCalls = mutableListOf<Pair<String, Boolean>>()
        var stopCount = 0

        override fun ensureActive(session: OfflineSession, isHost: Boolean) {
            activeCalls.add(session.sessionId to isHost)
            _status.value = WifiDirectStatus.CONNECTED
            _isGroupConnected.value = true
        }

        override fun stop() {
            stopCount++
            _isGroupConnected.value = false
            _status.value = WifiDirectStatus.IDLE
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun credentialsFactory_derivesDeterministicAndroidCompliantGroupAndPassphrase() {
        val sessionA1 = OfflineSession.create(10_000L)
        val sessionA2 = OfflineSession.fromCode(sessionA1.pairingCode, now = 10_000L)!!
        val sessionB = OfflineSession.create(20_000L)

        val creds1 = WifiDirectCredentialsFactory.derive(sessionA1)!!
        val creds2 = WifiDirectCredentialsFactory.derive(sessionA2)!!
        val credsOther = WifiDirectCredentialsFactory.derive(sessionB)!!

        assertEquals(creds1, creds2)
        assertNotEquals(creds1.networkName, credsOther.networkName)
        assertNotEquals(creds1.passphrase, credsOther.passphrase)

        assertTrue(creds1.networkName.startsWith("DIRECT-RV-"))
        assertTrue(creds1.networkName.length in 10..32)
        assertTrue(creds1.passphrase.length in 8..63)
        assertTrue(creds1.passphrase.all { it.isLetterOrDigit() })
    }

    @Test
    fun lobbyViewModel_autoStartsWifiDirectOnCreateOrJoin_andStopsOnOptOutOrLeave() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val backupSession = OfflineSession.create(100_000L)
        val backupCode = backupSession.pairingCode

        val api = mockk<ApiService>(relaxed = true)
        coEvery { api.getLobbyStatus(any()) } returns Response.success(
            LobbyStatus(
                roomId = "room-p2p-1",
                convoyName = "CanyonRun",
                isHost = false,
                host = LobbyHost(id = "host-1", displayName = "Host"),
                offlineBackupCode = backupCode,
                offlineMeshEnabled = true
            )
        )
        coEvery { api.leaveLobby(any()) } returns Response.success(Unit)
        every { api.toString() } returns "FakeApiService"

        val repo = ActiveRideRepository(prefs = null, api = api)
        val sessionHolder = OfflineSessionHolder()
        val p2pController = RecordingWifiDirectController()
        val vm = LobbyViewModel(
            apiService = api,
            activeRideRepository = repo,
            sessionHolder = sessionHolder,
            wifiDirectGroupController = p2pController
        )

        try {
            vm.startPolling("room-p2p-1")
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()

            assertFalse("Wi-Fi Direct should start automatically in lobby when room is joined", p2pController.activeCalls.isEmpty())
            assertEquals(false, p2pController.activeCalls.last().second)
            assertEquals(WifiDirectStatus.CONNECTED, vm.wifiDirectStatus.value)

            vm.optOutOfOfflineMesh()
            runCurrent()
            assertTrue("Opting out in lobby must tear down Wi-Fi Direct group", p2pController.stopCount >= 1)
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun meshModule_providesWifiDirectGroupController_andValidBroadcastListNonEmpty() {
        val providesMethod = MeshModule::class.java.declaredMethods.find {
            it.isAnnotationPresent(dagger.Provides::class.java) &&
                WifiDirectGroupController::class.java.isAssignableFrom(it.returnType)
        }
        assertNotNull("MeshModule must provide WifiDirectGroupController", providesMethod)

        val endpoints = UdpMeshLink.discoverBroadcastEndpoints(45454)
        assertTrue(endpoints.isNotEmpty())
        assertTrue(PermissionManager.ridePermissions.isNotEmpty())
    }

    @Test
    fun launchSetupLoop_terminatesAfter8AttemptsWithFailed_andRespectsCooldown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var virtualTime = 1_000_000L
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val p2pMgr = mockk<WifiP2pManager>(relaxed = true)
        val channel = mockk<WifiP2pManager.Channel>(relaxed = true)
        val wifiMgr = mockk<WifiManager>(relaxed = true)
        every { wifiMgr.isWifiEnabled } returns true

        var connectCalls = 0
        every { p2pMgr.connect(any(), any(), any()) } answers {
            connectCalls++
        }

        val controller = AndroidWifiDirectGroupController(
            context = context,
            gateOpen = { true },
            dispatcher = dispatcher,
            clock = { virtualTime },
            sdkInt = 33,
            p2pManagerOverride = p2pMgr,
            wifiManagerOverride = wifiMgr,
            hasPermissionsOverride = { true },
            registerReceiverOverride = { _, _ -> },
            unregisterReceiverOverride = { _ -> },
            initializeChannelOverride = { channel },
            buildConfigOverride = { mockk<WifiP2pConfig>(relaxed = true) }
        )

        val session = OfflineSession.create(virtualTime)
        controller.ensureActive(session, isHost = false)
        // Advance beyond initial 1.5s + 8 attempts of backoff (3s + 6s + 6*12s = 82.5s)
        advanceTimeBy(100_000L)
        runCurrent()

        assertEquals(WifiDirectStatus.FAILED, controller.status.value)
        assertEquals(AndroidWifiDirectGroupController.MAX_SETUP_ATTEMPTS, connectCalls)

        // Ensure another tick inside 60s cooldown does NOT relaunch setup attempts
        virtualTime += 10_000L
        controller.ensureActive(session, isHost = false)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(AndroidWifiDirectGroupController.MAX_SETUP_ATTEMPTS, connectCalls)

        // Once 60s cooldown passes, ensureActive launches a new cycle
        virtualTime += 60_000L
        controller.ensureActive(session, isHost = false)
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue("Expected new attempts after 60s cooldown", connectCalls > AndroidWifiDirectGroupController.MAX_SETUP_ATTEMPTS)
        controller.stop()
    }

    @Test
    fun stop_neverCallsSetWifiEnabled_andApiBelow29ReturnsUnsupported() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val p2pMgr = mockk<WifiP2pManager>(relaxed = true)
        val channel = mockk<WifiP2pManager.Channel>(relaxed = true)
        val wifiMgr = mockk<WifiManager>(relaxed = true)
        every { wifiMgr.isWifiEnabled } returns false

        val controller29 = AndroidWifiDirectGroupController(
            context = context,
            gateOpen = { true },
            sdkInt = 33,
            p2pManagerOverride = p2pMgr,
            wifiManagerOverride = wifiMgr,
            hasPermissionsOverride = { true },
            registerReceiverOverride = { _, _ -> },
            unregisterReceiverOverride = { _ -> },
            initializeChannelOverride = { channel },
            buildConfigOverride = { mockk<WifiP2pConfig>(relaxed = true) }
        )
        controller29.ensureActive(OfflineSession.create(10_000L), isHost = true)
        controller29.stop()
        verify(exactly = 0) { wifiMgr.setWifiEnabled(any()) }

        val controller28 = AndroidWifiDirectGroupController(
            context = context,
            gateOpen = { true },
            sdkInt = 28,
            p2pManagerOverride = p2pMgr,
            wifiManagerOverride = wifiMgr
        )
        controller28.ensureActive(OfflineSession.create(10_000L), isHost = true)
        assertEquals(WifiDirectStatus.UNSUPPORTED, controller28.status.value)
        controller28.stop()
        verify(exactly = 0) { wifiMgr.setWifiEnabled(any()) }
    }

    @Test
    fun resetFailureCooldown_allowsImmediateReconnectAfterNewGoElected() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var virtualTime = 1_000_000L
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val p2pMgr = mockk<WifiP2pManager>(relaxed = true)
        val channel = mockk<WifiP2pManager.Channel>(relaxed = true)
        val wifiMgr = mockk<WifiManager>(relaxed = true)
        every { wifiMgr.isWifiEnabled } returns true

        var connectCalls = 0
        every { p2pMgr.connect(any(), any(), any()) } answers { connectCalls++ }

        val controller = AndroidWifiDirectGroupController(
            context = context,
            gateOpen = { true },
            dispatcher = dispatcher,
            clock = { virtualTime },
            sdkInt = 33,
            p2pManagerOverride = p2pMgr,
            wifiManagerOverride = wifiMgr,
            hasPermissionsOverride = { true },
            registerReceiverOverride = { _, _ -> },
            unregisterReceiverOverride = { _ -> },
            initializeChannelOverride = { channel },
            buildConfigOverride = { mockk<WifiP2pConfig>(relaxed = true) }
        )
        val session = OfflineSession.create(virtualTime)
        controller.ensureActive(session, isHost = false)
        advanceTimeBy(100_000L)
        runCurrent()
        assertEquals(WifiDirectStatus.FAILED, controller.status.value)
        assertEquals(AndroidWifiDirectGroupController.MAX_SETUP_ATTEMPTS, connectCalls)

        // New GO elected while remaining a client: resetFailureCooldown() unblocks immediately inside 60s
        virtualTime += 5_000L
        controller.resetFailureCooldown()
        controller.ensureActive(session, isHost = false)
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(connectCalls > AndroidWifiDirectGroupController.MAX_SETUP_ATTEMPTS)
        controller.stop()
    }

    @Test
    fun deriveForSegment_producesDistinctValidCredsPerSegment_andOrderInvariant() {
        val session = OfflineSession.create(10_000L)
        val seg01 = WifiDirectCredentialsFactory.deriveForSegment(session, 0, 1)!!
        val seg10 = WifiDirectCredentialsFactory.deriveForSegment(session, 1, 0)!!
        val seg12 = WifiDirectCredentialsFactory.deriveForSegment(session, 1, 2)!!

        assertEquals(seg01, seg10)
        assertTrue(seg01.networkName.startsWith("DIRECT-RV-"))
        assertEquals(16, seg01.passphrase.length)
        assertTrue(seg01 != seg12)
    }

    @Test
    fun controller_defaultAndPopulatedP2pOwnerAddress() {
        val recording = RecordingWifiDirectController()
        assertEquals(null, recording.p2pOwnerAddress.value)
        assertEquals(null, recording.connectionInfo())

        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val controller = AndroidWifiDirectGroupController(
            context = context,
            gateOpen = { true },
            sdkInt = 33,
            p2pManagerOverride = mockk(relaxed = true),
            wifiManagerOverride = mockk(relaxed = true)
        )
        val info = WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = false
            groupOwnerAddress = InetAddress.getByName("192.168.49.77")
        }
        controller.onConnectionInfoUpdateForTest(info)
        assertEquals("192.168.49.77", controller.p2pOwnerAddress.value)
        assertEquals(WifiDirectStatus.CONNECTED, controller.status.value)

        controller.stop()
        assertEquals(null, controller.p2pOwnerAddress.value)
    }
}
