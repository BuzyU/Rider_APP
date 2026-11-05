package com.ridervoice.mesh.link

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class LinkSupervisorTest {

    private class ControllableMeshLink(
        override val linkId: String = "controllable-link"
    ) : MeshLink {
        private val _state = MutableStateFlow(MeshLinkState.RUNNING)
        override val state: StateFlow<MeshLinkState> = _state.asStateFlow()
        override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
        var startCount = 0
        var stopCount = 0
        var failOnStartCount = 0

        override suspend fun start() {
            startCount++
            if (failOnStartCount > 0) {
                failOnStartCount--
                _state.value = MeshLinkState.FAILED
            } else {
                _state.value = MeshLinkState.RUNNING
            }
        }

        override suspend fun stop() {
            stopCount++
            _state.value = MeshLinkState.STOPPED
        }

        fun triggerFailure() {
            _state.value = MeshLinkState.FAILED
        }

        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
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
    fun linkSupervisor_recoversFromTransientLinkFailure_togglesIsLinkAlive() = runBlocking(Dispatchers.Default) {
        val link = ControllableMeshLink()
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "Rider1")

        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            dispatcher = Dispatchers.Default
        )

        val session = OfflineSession.create(1000L)
        transport.startDiscoveryOnly(session)
        assertEquals(VoiceTransportState.CONNECTED, transport.state.value)
        assertTrue(transport.isLinkAlive.value)
        val initialStartCount = link.startCount

        // Simulate link failure: fail twice on restart, then succeed
        link.failOnStartCount = 2
        link.triggerFailure()

        // isLinkAlive should toggle to false
        withTimeout(2000L) {
            transport.isLinkAlive.first { !it }
        }
        assertFalse(transport.isLinkAlive.value)

        // Wait for supervisor to restart it through backoff
        withTimeout(5000L) {
            transport.isLinkAlive.first { it }
        }

        assertTrue("Transport link must be alive again", transport.isLinkAlive.value)
        assertEquals("Transport remains CONNECTED through transient link failure", VoiceTransportState.CONNECTED, transport.state.value)
        assertTrue("Supervisor must have attempted restarts", link.startCount >= initialStartCount + 3)

        transport.stop()
    }

    @Test
    fun linkSupervisor_marksTransportFailed_whenLinkFailsPersistentlyFor30Seconds() = runBlocking(Dispatchers.Default) {
        var virtualTime = 10_000L
        val link = ControllableMeshLink()
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "Rider2")

        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            clock = { virtualTime },
            gateAllowed = { true },
            linkFactory = { link },
            dispatcher = Dispatchers.Default
        )

        val session = OfflineSession.create(1000L)
        transport.startDiscoveryOnly(session)
        assertEquals(VoiceTransportState.CONNECTED, transport.state.value)

        // Make link continuously fail on start
        link.failOnStartCount = 100
        link.triggerFailure()

        // Wait until supervisor attempts at least one restart so failureStartMs is captured
        withTimeout(2000L) {
            while (link.startCount <= 1) {
                delay(20)
            }
        }

        // Advance virtual time by 31 seconds
        virtualTime += 31_000L

        withTimeout(5000L) {
            transport.state.first { it == VoiceTransportState.FAILED }
        }
        assertEquals(VoiceTransportState.FAILED, transport.state.value)
        assertFalse(transport.isLinkAlive.value)

        transport.stop()
    }
}

