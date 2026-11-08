package com.ridervoice.mesh

import android.media.AudioRecord
import com.ridervoice.mesh.audio.AudioRecordSource
import com.ridervoice.mesh.audio.HardwarePttPort
import com.ridervoice.mesh.audio.MeshVoxBridge
import com.ridervoice.mesh.audio.OfflineAudioCapture
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.LocalHealthSignals
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingAndAudioLifecycleTest {

    private class FakeMeshLink : MeshLink {
        override val linkId = "fake-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
    }

    private class FakeVoiceTransport : VoiceTransport {
        override val state: StateFlow<VoiceTransportState> = MutableStateFlow(VoiceTransportState.CONNECTED)
        override val micState: StateFlow<MicState> = MutableStateFlow(MicState.OFF)
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.OFFLINE_MESH)
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val participants: StateFlow<List<com.ridervoice.mesh.transport.UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override suspend fun start(session: VoiceSession) {}
        override suspend fun stop() {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: com.ridervoice.mesh.transport.AudioRoute) {}
    }

    private class FakeAudioRecordSource : AudioRecordSource {
        override val state: Int = AudioRecord.STATE_INITIALIZED
        override val audioSessionId: Int = 42
        override fun startRecording() {}
        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = sizeInBytes
        override fun stop() {}
        override fun release() {}
    }

    @Before
    fun setUp() {
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = true
            override val warmStandbyEnabled = true
        })
    }

    @After
    fun tearDown() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    // A26: Partial recovery (internet back, probe ok, LiveKit reconnecting) must NOT fall back to offline mesh
    @Test
    fun stateMachine_partialRecovery_doesNotFallBack() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )
        sm.beginSession("ride-1", now)
        val down = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(down)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, sm.state.value)

        // Internet returns and probe succeeds, but LiveKit is still reconnecting
        val partial = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = true,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        now += 12_000L
        sm.evaluate(partial)

        // On 7919cf8: isOnlineHealthy() was false so it fell back to OFFLINE_ACTIVE.
        // Rule: total loss was cleared, so it must return to ONLINE, NOT fall back.
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    // A26: In FALLBACK_UNAVAILABLE, handoff requires continuous total loss; if internet returns, return to ONLINE
    @Test
    fun stateMachine_fallbackUnavailable_requiresTotalLoss() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now }
        )
        sm.beginSession("ride-1", now)
        val noMeshTotalLoss = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = false,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(noMeshTotalLoss)
        now += 12_000L
        sm.evaluate(noMeshTotalLoss)
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, sm.state.value)

        // Internet returns (validated=true), and route appears
        val internetBackWithRoute = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = false,
            isLiveKitReconnecting = true,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(internetBackWithRoute)

        // On 7919cf8: checked only hasValidMeshRoute && isGateAllowed() without re-checking isTotalLoss(),
        // so it handed off to offline mesh. Expected: return to ONLINE.
        assertEquals(AutoSwitchState.ONLINE, sm.state.value)
    }

    // A25: When OFFLINE_ACTIVE and secret disappears, machine must still recover to online
    @Test
    fun stateMachine_offlineActive_recoversEvenWhenOfflineUnavailable() = runTest {
        var now = 100_000L
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = { true }
        )
        sm.beginSession("ride-1", now)
        val down = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(down)
        now += 12_000L
        sm.evaluate(down)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, sm.state.value)

        // offlineAvailable becomes false (session expired or cleared), but internet is recoverable
        val recoverableNoOffline = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = true,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true,
            offlineAvailable = false,
            isMeshTransportAlive = true
        )
        now += 25_000L
        sm.evaluate(recoverableNoOffline, offlineAvailable = false)

        // On 7919cf8: if (!offlineAvailable) return prevented recovery forever!
        // Expected: proceeds to ONLINE_RECOVERING
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, sm.state.value)
    }

    // A27: endSession during handoff must return promptly without blocking behind handoff mutex
    @Test
    fun stateMachine_endSession_duringHandoff_returnsPromptly() = runTest {
        var now = 100_000L
        val handoffStarted = CompletableDeferred<Unit>()
        val handoffBlocker = CompletableDeferred<Unit>()
        val sm = AutomaticSwitchStateMachine(
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = {
                handoffStarted.complete(Unit)
                handoffBlocker.await()
                true
            }
        )
        sm.beginSession("ride-1", now)
        val down = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 3,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true,
            offlineAvailable = true,
            isMeshTransportAlive = true
        )
        sm.evaluate(down)
        now += 12_000L

        val evalJob = launch { sm.evaluate(down) }
        handoffStarted.await()

        // Handoff is currently in flight. On 7919cf8, mutex.withLock was held for entire handoff!
        val endSessionJob = launch { sm.endSession() }

        val completedPromptly = withTimeoutOrNull(200L) {
            endSessionJob.join()
            true
        } ?: false

        handoffBlocker.complete(Unit)
        evalJob.join()
        endSessionJob.join()

        // On 7919cf8, completedPromptly is false because endSession blocks behind mutex
        assertTrue("endSession must return promptly during handoff", completedPromptly)
    }

    // A6: Bridge must not touch hardware PTT until session start / attach
    @Test
    fun bridge_doesNotTouchHardwarePtt_untilSessionStart() {
        var currentCallback: ((Boolean) -> Unit)? = null
        val fakePort = object : HardwarePttPort {
            override var onMicToggleRequest: ((Boolean) -> Unit)?
                get() = currentCallback
                set(value) { currentCallback = value }
            override fun activateSession() {}
            override fun deactivateSession() {}
        }
        val roomCallback: (Boolean) -> Unit = {}
        fakePort.onMicToggleRequest = roomCallback

        val bridge = MeshVoxBridge(transport = FakeVoiceTransport(), hardwarePttPort = fakePort)
        // On 7919cf8: bridge init clobbered fakePort.onMicToggleRequest immediately!
        assertTrue("Bridge must not touch hardware PTT in init", fakePort.onMicToggleRequest === roomCallback)
    }

    // A6: Bridge close must not clobber callback if someone else replaced it
    @Test
    fun bridge_detach_whenCallbackReplaced_leavesTheirs() {
        var currentCallback: ((Boolean) -> Unit)? = null
        val fakePort = object : HardwarePttPort {
            override var onMicToggleRequest: ((Boolean) -> Unit)?
                get() = currentCallback
                set(value) { currentCallback = value }
            override fun activateSession() {}
            override fun deactivateSession() {}
        }
        val roomCallback: (Boolean) -> Unit = {}
        fakePort.onMicToggleRequest = roomCallback

        val bridge = MeshVoxBridge(transport = FakeVoiceTransport(), hardwarePttPort = fakePort)
        val newerCallback: (Boolean) -> Unit = {}
        fakePort.onMicToggleRequest = newerCallback

        bridge.close()
        // On 7919cf8: bridge.close() clobbers newerCallback with roomCallback!
        assertTrue("Bridge close must not clobber callback if replaced by another component", fakePort.onMicToggleRequest === newerCallback)
    }

    // A39: mic toggle must not survive stop (open-mic privacy bug)
    @Test
    fun micToggle_doesNotSurviveStop() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { FakeMeshLink() },
            audioRecordFactory = { FakeAudioRecordSource() },
            hasMicPermission = { true }
        )
        val s1 = OfflineSession.create(1_000L)
        transport.start(VoiceSession.Offline(s1))
        transport.setMicrophoneEnabled(true)
        assertEquals(MicState.ON, transport.micState.value)

        transport.stop()
        assertEquals(MicState.OFF, transport.micState.value)

        // Start session 2
        val s2 = OfflineSession.create(2_000L)
        transport.start(VoiceSession.Offline(s2))

        // On 7919cf8: micWanted was not reset in stop(), so session 2 auto-opened the mic (MicState.ON)!
        assertEquals("Mic must not automatically open in new session", MicState.OFF, transport.micState.value)
        transport.stop()
    }

    // A40: enableAudio failure must roll back playback and locks
    @Test
    fun enableAudioFailure_rollsBackPlaybackAndLocks() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { FakeMeshLink() },
            audioTrackFactory = { null },
            hasMicPermission = { true }
        )
        transport.startDiscoveryOnly(OfflineSession.create(1_000L))
        transport.setMicrophoneEnabled(true)

        val firstRes = transport.enableAudio()
        assertTrue("Initial enableAudio with playback failure must fail", firstRes is EnableAudioResult.Failure)

        // Partial failure must roll back playback and ownership
        val retryRes = transport.enableAudio()
        assertFalse("Retry must not report Success when audio is uninitialized", retryRes is EnableAudioResult.Success)
        transport.stop()
    }

    // A41: startDiscoveryOnly with different session replaces running session
    @Test
    fun startWithDifferentSession_replacesRunningSession() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { FakeMeshLink() }
        )
        val s1 = OfflineSession.create(1_000L)
        val s2 = OfflineSession.create(2_000L)

        transport.startDiscoveryOnly(s1)
        assertEquals(s1.sessionId, transport.diagnostics.value.sessionId)

        // Start with different session
        transport.startDiscoveryOnly(s2)

        // On 7919cf8: early return `if (active) return` ignored s2 and kept s1 running!
        assertEquals("Different session must replace currently running session", s2.sessionId, transport.diagnostics.value.sessionId)
        transport.stop()
    }

    // A42: slow read returning 0 must not drop partial frame bytes
    @Test
    fun slowRead_doesNotDropPartialFrame() = runTest {
        val deliveredFrames = mutableListOf<ByteArray>()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var readCount = 0

        val fakeSource = object : AudioRecordSource {
            override val state: Int = AudioRecord.STATE_INITIALIZED
            override val audioSessionId: Int = 42
            override fun startRecording() {}
            override fun stop() {}
            override fun release() {}
            override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                readCount++
                return when (readCount) {
                    1 -> 0
                    2 -> 0
                    3 -> {
                        // First partial read: 320 bytes filled with 0x01
                        for (i in 0 until 320) audioData[offsetInBytes + i] = 1
                        320
                    }
                    4 -> 0 // empty read in the middle of a frame
                    5 -> {
                        // Second partial read: 320 bytes filled with 0x02
                        for (i in 0 until 320) audioData[offsetInBytes + i] = 2
                        320
                    }
                    else -> -1 // stop recording
                }
            }
        }

        val capture = OfflineAudioCapture(
            gateAllowed = { true },
            canClaimAudio = { true },
            hasMicPermission = { true },
            onHardwareBusy = {},
            dispatcher = testDispatcher,
            audioRecordFactory = { fakeSource },
            onPcmFrame = { frame -> deliveredFrames.add(frame) }
        )
        capture.setMicEnabled(true)
        capture.start()

        // Advance coroutine virtual time through delays
        testScheduler.advanceTimeBy(500L)
        capture.stop()

        // On 7919cf8: read == 0 caused `break` out of inner loop, dropping the 320 bytes from read 3.
        // Exactly ONE 640-byte frame must be delivered with 1s in [0..319] and 2s in [320..639]
        assertEquals("Must deliver exactly 1 complete 640-byte frame", 1, deliveredFrames.size)
        val frame = deliveredFrames[0]
        assertEquals(1.toByte(), frame[0])
        assertEquals(1.toByte(), frame[319])
        assertEquals(2.toByte(), frame[320])
        assertEquals(2.toByte(), frame[639])
    }
}
