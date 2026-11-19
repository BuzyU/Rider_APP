package com.ridervoice.state

import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.AudioDeviceRouter
import com.ridervoice.audio.HardwarePTTManager
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.audio.VoxEngine
import com.ridervoice.diagnostics.collector.NetworkDiagnosticsCollector
import com.ridervoice.models.Participant
import com.ridervoice.models.RiderLocation
import com.ridervoice.network.ApiService
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.InRideSosAlert
import com.ridervoice.network.LiveKitManager
import com.ridervoice.network.NetworkHealth
import com.ridervoice.network.NetworkResilienceManager
import com.ridervoice.security.SecurePreferences
import com.ridervoice.services.LocationService
import com.ridervoice.services.RideRecorder
import com.ridervoice.services.ServiceWatchdog
import com.ridervoice.services.ThermalManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP-155: Verifies that ghost participant expiry runs on an independent per-ghost coroutine
 * scheduled when the rider becomes a ghost and NEVER depends on another participants emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomViewModelGhostExpiryTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        participantsFlow: MutableStateFlow<List<Participant>>,
        connectionFlow: MutableStateFlow<ConnectionState>,
        fakeNow: () -> Long
    ): RoomViewModel {
        val lk = mockk<LiveKitManager>(relaxed = true)
        every { lk.participants } returns participantsFlow
        every { lk.connectionState } returns connectionFlow
        every { lk.remoteLocations } returns MutableStateFlow(emptyMap<String, RiderLocation>())
        every { lk.activeSpeaker } returns MutableStateFlow<String?>(null)
        every { lk.isMicEnabled } returns MutableStateFlow(false)
        every { lk.deafened } returns MutableStateFlow(false)
        every { lk.convoyEnded } returns MutableStateFlow(false)
        every { lk.inRideSosAlert } returns MutableStateFlow<InRideSosAlert?>(null)
        every { lk.roomFault } returns kotlinx.coroutines.flow.MutableSharedFlow()

        val router = mockk<AudioDeviceRouter>(relaxed = true)
        every { router.activeDevice } returns MutableStateFlow<AudioDevice>(AudioDevice.Speakerphone)
        every { router.routerState } returns MutableStateFlow(RouterState.ACTIVE)
        every { router.requestedDevice } returns MutableStateFlow<AudioDevice>(AudioDevice.Speakerphone)
        every { router.routedInput } returns MutableStateFlow(RoutedDeviceInfo())
        every { router.routedOutput } returns MutableStateFlow(RoutedDeviceInfo())

        val vox = mockk<VoxEngine>(relaxed = true)
        every { vox.isMicOpen } returns MutableStateFlow(false)
        every { vox.noiseFloor } returns MutableStateFlow(300f)
        every { vox.currentAmplitude } returns MutableStateFlow(0f)

        val locSvc = mockk<LocationService>(relaxed = true)
        every { locSvc.currentLocation } returns MutableStateFlow(null)

        val netRes = mockk<NetworkResilienceManager>(relaxed = true)
        every { netRes.networkHealth } returns MutableStateFlow(NetworkHealth.CONNECTED)

        val diag = mockk<NetworkDiagnosticsCollector>(relaxed = true)
        every { diag.isCollecting } returns MutableStateFlow(false)
        every { diag.latestSelfMetric } returns MutableStateFlow(null)
        every { diag.sampleCount } returns MutableStateFlow(0)

        val api = mockk<ApiService>(relaxed = true)
        val activeRideRepo = ActiveRideRepository(prefs = null, api = api)

        val vm = RoomViewModel(
            apiService = api,
            liveKitManager = lk,
            locationService = locSvc,
            audioDeviceRouter = router,
            voxEngine = vox,
            networkResilienceManager = netRes,
            rideRecorder = mockk<RideRecorder>(relaxed = true),
            thermalManager = mockk<ThermalManager>(relaxed = true),
            serviceWatchdog = mockk<ServiceWatchdog>(relaxed = true),
            securePrefs = SecurePreferences(null),
            hardwarePTTManager = mockk<HardwarePTTManager>(relaxed = true),
            networkDiagnosticsCollector = diag,
            activeRideRepository = activeRideRepo
        )
        vm.clock = fakeNow
        return vm
    }

    @Test
    fun ghostExpiresAtExactly120sWithZeroFurtherParticipantEvents() = runTest(testDispatcher) {
        var now = 10_000L
        val participantsFlow = MutableStateFlow(
            listOf(
                Participant(identity = "host_1", displayName = "Host"),
                Participant(identity = "rider_2", displayName = "Bob")
            )
        )
        val connFlow = MutableStateFlow(ConnectionState.CONNECTED)
        val vm = createViewModel(participantsFlow, connFlow) { now }
        runCurrent()

        assertEquals(2, vm.participants.value.size)

        // Bob drops at t=10_000; only Host remains in incoming list. ZERO further emissions after this!
        participantsFlow.value = listOf(Participant(identity = "host_1", displayName = "Host"))
        runCurrent()

        val bobGhost = vm.participants.value.firstOrNull { it.identity == "rider_2" }
        assertTrue("Disconnected rider must transition to ghost", bobGhost?.isGhost == true)

        // Advance virtual clock to 119,999ms: ghost must STILL be present
        now += 119_999L
        advanceTimeBy(119_999L)
        runCurrent()
        assertTrue(
            "Ghost must still exist at 119,999ms",
            vm.participants.value.any { it.identity == "rider_2" && it.isGhost }
        )

        // Advance final 1ms to hit 120,000ms with NO new participant emissions
        now += 1L
        advanceTimeBy(1L)
        runCurrent()
        assertFalse(
            "Ghost must automatically expire at exactly 120s without needing another participant event",
            vm.participants.value.any { it.identity == "rider_2" }
        )
        assertEquals(1, vm.participants.value.size)
    }

    @Test
    fun returningRiderBefore120sCancelsScheduledGhostRemoval() = runTest(testDispatcher) {
        var now = 50_000L
        val participantsFlow = MutableStateFlow(
            listOf(
                Participant(identity = "host_1", displayName = "Host"),
                Participant(identity = "rider_2", displayName = "Bob")
            )
        )
        val connFlow = MutableStateFlow(ConnectionState.CONNECTED)
        val vm = createViewModel(participantsFlow, connFlow) { now }
        runCurrent()

        // Bob becomes a ghost at t=50_000
        participantsFlow.value = listOf(Participant(identity = "host_1", displayName = "Host"))
        runCurrent()
        assertTrue(vm.participants.value.first { it.identity == "rider_2" }.isGhost)

        // Bob reconnects at t=50_000 + 60_000
        now += 60_000L
        advanceTimeBy(60_000L)
        participantsFlow.value = listOf(
            Participant(identity = "host_1", displayName = "Host"),
            Participant(identity = "rider_2", displayName = "Bob")
        )
        runCurrent()

        val restoredBob = vm.participants.value.firstOrNull { it.identity == "rider_2" }
        assertFalse("Returning rider must clear ghost flag", restoredBob?.isGhost == true)

        // Advance past the original 120s + 60s expiry mark with no further events
        now += 120_000L
        advanceTimeBy(120_000L)
        runCurrent()

        val stillHere = vm.participants.value.firstOrNull { it.identity == "rider_2" }
        assertTrue("Returning rider must remain active because ghost expiry job was cancelled", stillHere != null && !stillHere.isGhost)
    }
}
