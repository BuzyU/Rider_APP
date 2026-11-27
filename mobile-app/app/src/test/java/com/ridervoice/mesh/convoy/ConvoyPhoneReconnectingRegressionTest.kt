package com.ridervoice.mesh.convoy

import com.ridervoice.mesh.GateDecision
import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.RoomHostSelection
import com.ridervoice.mesh.RoomMeshMirrorSnapshot
import com.ridervoice.mesh.selectRoomHost
import com.ridervoice.mesh.session.HolderOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.Clock
import com.ridervoice.mesh.switching.LocalHealthSignals
import com.ridervoice.mesh.switching.LocalHealthSignalsProvider
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.switching.OfflineAvailability
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.MeshCadence
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.mesh.ui.FallbackReason
import com.ridervoice.mesh.ui.mapTransitionReasonToFallbackReason
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.state.ActiveRideRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConvoyPhoneReconnectingRegressionTest {

    @get:Rule
    val testTimeout: Timeout = Timeout(15, TimeUnit.SECONDS)

    private class InMemorySettings(initialEnabled: Boolean = false) : MeshRuntimeSettings {
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

    @After
    fun tearDown() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun syncMirror_synchronouslyUpdatesGateAndSelectRoomHostBeforeNavigation() {
        val settings = InMemorySettings(initialEnabled = false)
        OfflineMeshGate.install(settings)
        assertEquals(GateDecision.DISABLED_BY_USER, OfflineMeshGate.decision())
        assertEquals(RoomHostSelection.PLAIN_ONLINE, selectRoomHost(OfflineMeshGate.decision()))

        OfflineMeshGate.syncMirror(
            RoomMeshMirrorSnapshot(
                isLive = true,
                offlineMeshEnabled = true,
                optOut = false,
                automaticFallbackEnabled = true,
                warmStandbyEnabled = true
            )
        )

        assertTrue(OfflineMeshGate.settingsFlow.value.offlineMeshEnabled)
        assertEquals(GateDecision.ENABLED, OfflineMeshGate.decision())
        assertEquals(RoomHostSelection.OFFLINE_AWARE, selectRoomHost(OfflineMeshGate.decision()))
    }

    @Test
    fun localHealthSignalsProvider_probesImmediatelyWhenLiveKitDropsOutOfHealthyOnline() = runTest {
        var now = 10_000L
        var validated = true
        var probeOk = true
        var probeInvocations = 0
        val lkState = MutableStateFlow(ConnectionState.DISCONNECTED)

        val provider = LocalHealthSignalsProvider(
            liveKitConnectionState = lkState,
            liveKitReconnectAttempts = { 1 },
            healthProbe = {
                probeInvocations++
                probeOk
            },
            isInternetValidated = { validated },
            clock = Clock { now },
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        // Establish an initial probe timestamp while connecting (lastProbeTimeMs != 0L)
        now += 1_000L
        val s1 = provider.sampleSignals()
        assertTrue(s1.isHealthProbeSuccessful)
        assertEquals(1, probeInvocations)

        // Become healthy online (sets lastProbeResult = true)
        now += 1_000L
        lkState.value = ConnectionState.CONNECTED
        val s2 = provider.sampleSignals()
        assertTrue(s2.isHealthProbeSuccessful)

        // 1 second later (< 5s probe throttle), internet & LiveKit suddenly drop while phone enters RECONNECTING
        now += 1_000L
        lkState.value = ConnectionState.RECONNECTING
        validated = false
        probeOk = false
        val s3 = provider.sampleSignals()
        assertFalse("Expected immediate /health re-probe instead of stale true cache", s3.isHealthProbeSuccessful)
        assertEquals(2, probeInvocations)
    }

    @Test
    fun convoyFallback_succeedsEvenWhenAndroidKeepsStaleValidatedInternetDuringLiveKitReconnecting() = runTest(timeout = 5.seconds) {
        val settings = InMemorySettings(initialEnabled = true)
        OfflineMeshGate.install(settings)

        var now = 100_000L
        val clock = Clock { now }
        val roomId = "convoy-room-1"
        val sid = ConvoyHash.stableSessionUuid(roomId)
        val membership = ConvoyMembershipSnapshot.create(
            sid,
            listOf(
                ConvoyMember("u-host", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-rider", MemberRole.MESH_PARTICIPANT)
            )
        )

        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        every { rideRepo.state } returns MutableStateFlow(
            ActiveRideRepository.ActiveRide(
                roomId = roomId,
                displayName = "Test Convoy",
                isHost = true,
                phase = ActiveRideRepository.Phase.LIVE,
                convoyMembershipSnapshot = membership
            )
        )

        val lkManager = mockk<LiveKitManager>(relaxed = true)
        val lkConn = MutableStateFlow(ConnectionState.RECONNECTING)
        every { lkManager.connectionState } returns lkConn
        every { lkManager.participants } returns MutableStateFlow(emptyList())

        val arbiter = AudioOwnershipArbiter()
        arbiter.claimLiveKit()

        val coordinator = mockk<VoiceSessionCoordinator>(relaxed = true)
        every { coordinator.audioOwnershipArbiter } returns arbiter
        every { coordinator.offlineAvailability } returns MutableStateFlow(OfflineAvailability.AVAILABLE)
        coEvery { coordinator.fallbackToMesh() } coAnswers {
            arbiter.handoffLiveKitToOfflineMesh { }
        }

        val meshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { meshTransport.state } returns MutableStateFlow(VoiceTransportState.CONNECTED)
        every { meshTransport.hasValidRoute } returns MutableStateFlow(true)
        every { meshTransport.isLinkAlive } returns MutableStateFlow(true)
        every { meshTransport.participants } returns MutableStateFlow(emptyList())

        val holder = OfflineSessionHolder()
        holder.setBackup(OfflineSession.create(now))
        val secretProvider = HolderOfflineSecretProvider(holder) { now }

        val healthSignalsProvider = mockk<LocalHealthSignalsProvider>(relaxed = true)
        // Simulate hotspot/dead Wi-Fi: OS still reports isValidatedInternet=true while LiveKit is RECONNECTING & /health fails
        coEvery { healthSignalsProvider.sampleSignals() } returns LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = true,
            liveKitReconnectAttempts = 2,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            isMeshTransportAlive = true
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = lkManager,
            coordinator = coordinator,
            meshTransport = meshTransport,
            healthSignalsProvider = healthSignalsProvider,
            secretProvider = secretProvider,
            localOnlineIdentity = { "u-host" },
            clock = clock,
            dispatcher = dispatcher,
            convoyConfig = ConvoyProtocolConfig(failureConfirmationMs = 10_000L)
        )

        try {
            manager.start()
            runCurrent()
            verify(atLeast = 1) { meshTransport.setCadence(MeshCadence.BOOST) }
            assertEquals(LocalTransportState.ONLINE_SUSPECT, manager.localTransportState.value)

            // Advance past 10s confirmation window while isValidatedInternet remains stale true
            repeat(12) {
                now += 1_000L
                advanceTimeBy(1_000L)
                runCurrent()
            }

            coVerify(exactly = 1) { coordinator.fallbackToMesh() }
            assertEquals(LocalTransportState.OFFLINE_ACTIVE, manager.localTransportState.value)
            assertEquals(ConvoyModeState.OFFLINE, manager.convoyModeState.value)
        } finally {
            manager.close()
        }
    }

    @Test
    fun convoyFallback_whenNoMeshRouteYet_exposesFallbackUnavailableAndMapsReason() = runTest(timeout = 5.seconds) {
        val settings = InMemorySettings(initialEnabled = true)
        OfflineMeshGate.install(settings)

        var now = 200_000L
        val clock = Clock { now }
        val roomId = "convoy-room-no-route"
        val sid = ConvoyHash.stableSessionUuid(roomId)
        val membership = ConvoyMembershipSnapshot.create(
            sid,
            listOf(
                ConvoyMember("u-host", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-rider", MemberRole.MESH_PARTICIPANT)
            )
        )

        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        every { rideRepo.state } returns MutableStateFlow(
            ActiveRideRepository.ActiveRide(
                roomId = roomId,
                displayName = "No Route Convoy",
                isHost = true,
                phase = ActiveRideRepository.Phase.LIVE,
                convoyMembershipSnapshot = membership
            )
        )

        val lkManager = mockk<LiveKitManager>(relaxed = true)
        every { lkManager.connectionState } returns MutableStateFlow(ConnectionState.RECONNECTING)
        every { lkManager.participants } returns MutableStateFlow(emptyList())

        val arbiter = AudioOwnershipArbiter()
        arbiter.claimLiveKit()

        val coordinator = mockk<VoiceSessionCoordinator>(relaxed = true)
        every { coordinator.audioOwnershipArbiter } returns arbiter
        every { coordinator.offlineAvailability } returns MutableStateFlow(OfflineAvailability.AVAILABLE)

        val meshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { meshTransport.state } returns MutableStateFlow(VoiceTransportState.CONNECTED)
        every { meshTransport.hasValidRoute } returns MutableStateFlow(false)
        every { meshTransport.isLinkAlive } returns MutableStateFlow(true)
        every { meshTransport.participants } returns MutableStateFlow(emptyList())

        val holder = OfflineSessionHolder()
        holder.setBackup(OfflineSession.create(now))
        val secretProvider = HolderOfflineSecretProvider(holder) { now }

        val healthSignalsProvider = mockk<LocalHealthSignalsProvider>(relaxed = true)
        coEvery { healthSignalsProvider.sampleSignals() } returns LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = true,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = false,
            isMeshTransportAlive = true
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = lkManager,
            coordinator = coordinator,
            meshTransport = meshTransport,
            healthSignalsProvider = healthSignalsProvider,
            secretProvider = secretProvider,
            localOnlineIdentity = { "u-host" },
            clock = clock,
            dispatcher = dispatcher,
            convoyConfig = ConvoyProtocolConfig(failureConfirmationMs = 10_000L)
        )

        try {
            manager.start()
            runCurrent()

            repeat(11) {
                now += 1_000L
                advanceTimeBy(1_000L)
                runCurrent()
            }

            assertEquals(LocalTransportState.FALLBACK_UNAVAILABLE, manager.localTransportState.value)
            assertEquals(
                FallbackReason.NO_RIDERS_NEARBY,
                mapTransitionReasonToFallbackReason(manager.lastTransitionReason.value, OfflineAvailability.AVAILABLE)
            )
        } finally {
            manager.close()
        }
    }
}
