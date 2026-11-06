package com.ridervoice.mesh.audit

import com.ridervoice.mesh.audio.MicController
import com.ridervoice.mesh.session.HolderOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.LocalHealthSignals
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryPlanComplianceTest {

    private val failingOnline = LocalHealthSignals(
        isValidatedInternet = false,
        isLiveKitConnected = false,
        isLiveKitReconnecting = false,
        liveKitReconnectAttempts = 3,
        isHealthProbeSuccessful = false,
        hasValidMeshRoute = true,
        offlineAvailable = true,
        isMeshTransportAlive = true
    )

    @Test
    fun onlineRecoverable_liveKitDisconnected_neverTransitionsToRecoveringOrOnline() = runTest {
        var now = 100_000L
        var handbackCalled = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                handbackCalled = true
                true
            }
        )

        // Enter OFFLINE_ACTIVE
        sm.evaluate(failingOnline)
        now += 10_001L
        sm.evaluate(failingOnline)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Internet + probe OK, but LiveKit disconnected
        val internetOkLiveKitDown = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )

        // Advance past dwell time for 60 seconds
        for (i in 1..60) {
            now += 1_000L
            sm.evaluate(internetOkLiveKitDown)
            assertEquals("Must stay in OFFLINE_ACTIVE while LiveKit is disconnected", AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
            assertFalse("Must never call onHandoffToOnline while LiveKit is disconnected", handbackCalled)
        }
    }

    @Test
    fun deadMesh_onlineNotRecovered_staysPutInOfflineActive() = runTest {
        var now = 100_000L
        var handbackCalled = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                handbackCalled = true
                true
            }
        )

        sm.evaluate(failingOnline)
        now += 10_001L
        sm.evaluate(failingOnline)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // Dead mesh, but online is NOT recovered (LiveKit still disconnected)
        val deadMeshOnlineNotRecovered = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = false,
            offlineAvailable = true,
            isMeshTransportAlive = false
        )

        now += 25_000L
        sm.evaluate(deadMeshOnlineNotRecovered)

        assertEquals("Dead mesh without recovered LiveKit must remain in OFFLINE_ACTIVE", AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
        assertFalse(handbackCalled)
    }

    @Test
    fun liveKitFlapDuringRecoveryWindow_restartsStabilityWindow() = runTest {
        var now = 200_000L
        var handbackCalled = false
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                handbackCalled = true
                true
            }
        )

        sm.evaluate(failingOnline)
        now += 10_001L
        sm.evaluate(failingOnline)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        val recoveredSignals = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = true,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )

        // Dwell expires -> enter ONLINE_RECOVERING
        now += 21_000L
        sm.evaluate(recoveredSignals)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)

        // At 4 seconds into 8s window: LiveKit flaps
        now += 4_000L
        val flappingSignals = recoveredSignals.copy(isLiveKitConnected = false)
        sm.evaluate(flappingSignals)
        assertEquals("Must abort to OFFLINE_ACTIVE on LiveKit flap", AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)
        assertFalse(handbackCalled)

        // LiveKit reconnects -> enters ONLINE_RECOVERING again
        now += 1_000L
        sm.evaluate(recoveredSignals)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)

        // Another 4 seconds passes (only 4s of new window) -> still recovering
        now += 4_000L
        sm.evaluate(recoveredSignals)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)
        assertFalse(handbackCalled)

        // Full 8s reached on new window -> handback executes
        now += 4_001L
        sm.evaluate(recoveredSignals)
        assertTrue("Handback called when full window satisfied", handbackCalled)
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    @Test
    fun coordinatorRecoverToLiveKit_failsFastWithoutPausingMesh_whenLiveKitNotConnected() = runTest {
        var meshCapturePaused = false
        val fakeMesh = object : VoiceTransport, MeshControl {
            override val state = MutableStateFlow(VoiceTransportState.CONNECTED)
            override val participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
            override val activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
            override val audioOwner = MutableStateFlow(com.ridervoice.mesh.transport.AudioOwner.OFFLINE_MESH)
            override val micState = MutableStateFlow(MicState.ON)
            override val hasValidRoute = MutableStateFlow(true)
            override val micController: MicController? = null
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: AudioRoute) {}
            override suspend fun pauseCaptureOnly() { meshCapturePaused = true }
            override suspend fun resumeCaptureOnly() {}
            override suspend fun enableAudio(): EnableAudioResult = EnableAudioResult.Success
            override suspend fun disableAudio() {}
            override suspend fun startDiscoveryOnly(session: OfflineSession) {}
            override fun setHelloIntervalMs(intervalMs: Long) {}
        }

        val fakeLk = object : VoiceTransport {
            override val state = MutableStateFlow(VoiceTransportState.STOPPED)
            override val participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
            override val activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
            override val audioOwner = MutableStateFlow(com.ridervoice.mesh.transport.AudioOwner.NONE)
            override val micState = MutableStateFlow(MicState.OFF)
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: AudioRoute) {}
        }

        val arbiter = AudioOwnershipArbiter()
        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = fakeLk,
            meshTransport = fakeMesh,
            audioOwnershipArbiter = arbiter,
            secretProvider = HolderOfflineSecretProvider(OfflineSessionHolder())
        )

        val result = coordinator.recoverToLiveKit()
        assertFalse("recoverToLiveKit must return false when LiveKit is not connected", result)
        assertFalse("recoverToLiveKit must not pause mesh capture when LiveKit is not connected", meshCapturePaused)
    }
}
