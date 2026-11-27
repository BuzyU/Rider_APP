package com.ridervoice.mesh.link

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

        val creds1 = WifiDirectCredentialsFactory.derive(sessionA1)
        val creds2 = WifiDirectCredentialsFactory.derive(sessionA2)
        val credsOther = WifiDirectCredentialsFactory.derive(sessionB)

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
}
