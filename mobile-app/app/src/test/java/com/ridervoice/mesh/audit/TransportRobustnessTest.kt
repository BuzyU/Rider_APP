package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TransportRobustnessTest {

    private class FailingLink : MeshLink {
        override val linkId = "failing-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.FAILED)
        override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = false
    }

    private class CountingLink : MeshLink {
        override val linkId = "counting-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        var startCount = 0
        override suspend fun start() { startCount++ }
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
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

    @Test
    fun linkFailureCausesTransportToFailAndReleaseAudioOwner() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        val failingLink = FailingLink()
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { failingLink }
        )
        val session = OfflineSession.create(1_000L)

        transport.start(VoiceSession.Offline(session))

        assertNotEquals(
            "Transport must NOT report CONNECTED when link failed",
            VoiceTransportState.CONNECTED,
            transport.state.value
        )
        assertEquals(VoiceTransportState.FAILED, transport.state.value)
        assertEquals("Audio owner should be released on failure", AudioOwner.NONE, arbiter.owner.value)
    }

    @Test
    fun startIsIdempotentAndOpensOnlyOneLink() = runTest {
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "test-rider")
        var linksCreated = 0
        val countingLink = CountingLink()
        val transport = OfflineMeshVoiceTransport(
            identityProvider = { identity },
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = {
                linksCreated++
                countingLink
            }
        )
        val session = OfflineSession.create(1_000L)

        transport.start(VoiceSession.Offline(session))
        assertEquals(VoiceTransportState.CONNECTED, transport.state.value)

        // Second call while CONNECTED must be a no-op
        transport.start(VoiceSession.Offline(session))
        assertEquals(VoiceTransportState.CONNECTED, transport.state.value)
        assertEquals("Should create exactly 1 link", 1, linksCreated)
        assertEquals("Should start link exactly once", 1, countingLink.startCount)

        transport.stop()
        assertEquals(VoiceTransportState.STOPPED, transport.state.value)
    }
}
