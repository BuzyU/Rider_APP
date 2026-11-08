package com.ridervoice.mesh.audit

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.audio.AudioTrackSink
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.link.NetworkLocks
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

class StartAudioFailureTeardownTest {

    private class CountingNetworkLocks : NetworkLocks {
        var acquireCount = 0
        var releaseCount = 0
        override fun acquire() { acquireCount++ }
        override fun release() { releaseCount++ }
    }

    private class FakeAudioTrackSink : AudioTrackSink {
        override val state: Int = android.media.AudioTrack.STATE_INITIALIZED
        override fun play() {}
        override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = sizeInBytes
        override fun stop() {}
        override fun release() {}
    }

    private class FakeMeshLink : MeshLink {
        override val linkId: String = "fake-link"
        private val _state = MutableStateFlow(MeshLinkState.RUNNING)
        override val state: StateFlow<MeshLinkState> = _state.asStateFlow()
        private val _frames = MutableSharedFlow<MeshFrame>()
        override val incomingFrames: SharedFlow<MeshFrame> = _frames.asSharedFlow()
        override suspend fun start() { _state.value = MeshLinkState.RUNNING }
        override suspend fun stop() { _state.value = MeshLinkState.STOPPED }
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
    }

    private class FakeRuntimeSettings(initial: Boolean = true) : MeshRuntimeSettings {
        private val _flow = MutableStateFlow(
            MeshSettingsSnapshot(
                offlineMeshEnabled = initial,
                automaticFallbackEnabled = true,
                warmStandbyEnabled = true
            )
        )
        override val settingsFlow = _flow.asStateFlow()
        override val offlineMeshEnabled get() = _flow.value.offlineMeshEnabled
        override val automaticFallbackEnabled get() = _flow.value.automaticFallbackEnabled
        override val warmStandbyEnabled get() = _flow.value.warmStandbyEnabled
        override fun setOfflineMeshEnabled(v: Boolean) {}
        override fun setAutomaticFallbackEnabled(v: Boolean) {}
        override fun setWarmStandbyEnabled(v: Boolean) {}
    }

    @Before
    fun setUp() {
        FeatureFlags.localOverride = true
        OfflineMeshGate.install(FakeRuntimeSettings(true))
    }

    @After
    fun tearDown() {
        FeatureFlags.localOverride = null
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun startWithFailingAudioTrackFactory_rollsBackStateAndTeardownAllResources() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        val locks = CountingNetworkLocks()
        val link = FakeMeshLink()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "tester-fail")

        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            locks = locks,
            audioTrackFactory = { null }
        )

        val session = OfflineSession.create(now = 1_000L)
        transport.start(VoiceSession.Offline(session))

        // Assert 1: State is FAILED
        assertEquals(VoiceTransportState.FAILED, transport.state.value)

        // Assert 2: audioFailure is PLAYBACK_START_FAILED
        val failure = transport.audioFailure.value
        assertNotNull("audioFailure should not be null", failure)
        assertEquals(EnableAudioResult.Failure.Code.PLAYBACK_START_FAILED, failure?.code)

        // Assert 3: link stopped
        assertEquals(MeshLinkState.STOPPED, link.state.value)

        // Assert 4: arbiter owner released
        assertNotEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        // Assert 5: locks released
        assertEquals("Locks acquire and release must be balanced", locks.acquireCount, locks.releaseCount)

        // Assert 6: participants is empty
        assertTrue("Participants list should be empty", transport.participants.value.isEmpty())

        // Assert 7: idempotent stop
        transport.stop()
        assertEquals(VoiceTransportState.STOPPED, transport.state.value)
        transport.close()
    }

    @Test
    fun enableAudioReturnsOwnershipDenied_tearsDown() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        arbiter.claimLiveKit() // Claim by LiveKit so mesh ownership claim is denied

        val locks = CountingNetworkLocks()
        val link = FakeMeshLink()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "tester-denied")

        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            locks = locks,
            audioTrackFactory = { FakeAudioTrackSink() }
        )

        val session = OfflineSession.create(now = 1_000L)
        transport.start(VoiceSession.Offline(session))

        // Assert 1: State is FAILED
        assertEquals(VoiceTransportState.FAILED, transport.state.value)

        // Assert 2: audioFailure code is OWNERSHIP_DENIED
        val failure = transport.audioFailure.value
        assertNotNull("audioFailure should not be null", failure)
        assertEquals(EnableAudioResult.Failure.Code.OWNERSHIP_DENIED, failure?.code)

        // Assert 3: link stopped
        assertEquals(MeshLinkState.STOPPED, link.state.value)

        // Assert 4: arbiter owner not offline mesh
        assertNotEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        // Assert 5: locks released
        assertEquals("Locks acquire and release must be balanced", locks.acquireCount, locks.releaseCount)

        // Assert 6: participants is empty
        assertTrue("Participants list should be empty", transport.participants.value.isEmpty())

        // Assert 7: idempotent stop
        transport.stop()
        assertEquals(VoiceTransportState.STOPPED, transport.state.value)
        transport.close()
    }

    @Test
    fun enableAudioThrows_isCaught_codeUnexpected() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        val locks = CountingNetworkLocks()
        val link = FakeMeshLink()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "tester-throws")

        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            locks = locks,
            audioTrackFactory = {
                throw IllegalStateException("audio track crashed during init")
            }
        )

        val session = OfflineSession.create(now = 1_000L)
        transport.start(VoiceSession.Offline(session))

        // Assert 1: State is FAILED
        assertEquals(VoiceTransportState.FAILED, transport.state.value)

        // Assert 2: audioFailure is UNEXPECTED with class name
        val failure = transport.audioFailure.value
        assertNotNull("audioFailure should not be null", failure)
        assertEquals(EnableAudioResult.Failure.Code.UNEXPECTED, failure?.code)
        assertEquals("IllegalStateException", failure?.detail)

        // Assert 3: link stopped
        assertEquals(MeshLinkState.STOPPED, link.state.value)

        // Assert 4: arbiter owner released
        assertNotEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        // Assert 5: locks released
        assertEquals("Locks acquire and release must be balanced", locks.acquireCount, locks.releaseCount)

        // Assert 6: participants empty
        assertTrue("Participants list should be empty", transport.participants.value.isEmpty())

        transport.close()
    }

    @Test
    fun cancellationDuringEnableAudio_isRethrown_andResourcesReleased() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        val locks = CountingNetworkLocks()
        val link = FakeMeshLink()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "tester-cancel")

        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            locks = locks,
            audioTrackFactory = {
                throw CancellationException("cancelled during audio setup")
            }
        )

        val session = OfflineSession.create(now = 1_000L)
        try {
            transport.start(VoiceSession.Offline(session))
            fail("CancellationException must be rethrown")
        } catch (e: CancellationException) {
            assertEquals("cancelled during audio setup", e.message)
        }

        // Arbiter owner must be released
        assertNotEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
        assertEquals("Locks must be balanced", locks.acquireCount, locks.releaseCount)
        transport.close()
    }

    @Test
    fun secondStartAfterFailureSucceeds() = runBlocking {
        val arbiter = AudioOwnershipArbiter()
        val locks = CountingNetworkLocks()
        val link = FakeMeshLink()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "tester-retry")

        var shouldFail = true
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            locks = locks,
            audioTrackFactory = {
                if (shouldFail) null else FakeAudioTrackSink()
            }
        )

        val session = OfflineSession.create(now = 1_000L)

        // First start fails
        transport.start(VoiceSession.Offline(session))
        assertEquals(VoiceTransportState.FAILED, transport.state.value)
        assertNotNull(transport.audioFailure.value)
        assertNotEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
        assertEquals(locks.acquireCount, locks.releaseCount)

        // Enable recovery
        shouldFail = false

        // Second start succeeds
        transport.start(VoiceSession.Offline(session))
        assertEquals(VoiceTransportState.CONNECTED, transport.state.value)
        assertNull("audioFailure must be cleared on successful start", transport.audioFailure.value)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
        assertEquals(MeshLinkState.RUNNING, link.state.value)

        // Stop cleanly
        transport.stop()
        assertEquals(VoiceTransportState.STOPPED, transport.state.value)
        assertNotEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)
        assertEquals(locks.acquireCount, locks.releaseCount)

        transport.close()
    }
}
