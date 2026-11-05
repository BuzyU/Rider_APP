package com.ridervoice.mesh.audit

import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.link.NetworkLocks
import com.ridervoice.mesh.link.NoOpWifiNetworkWatcher
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Verifies cryptographic key zeroization upon engine closure and transport teardown (B17).
 */
class KeyZeroizationTest {

    private class FakeLink : MeshLink {
        override val linkId: String = "test-link"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
    }

    @Test
    fun forwardingEngine_closeZeroizesKeyArrays() {
        val now = 1_000L
        val selfId = UUID.randomUUID().toString()
        val session = OfflineSession.create(now)
        val sManager = SessionManager(session, selfId, clock = { now })
        val repo = MeshGraphRepository(selfId, "TestNode", clock = { now })
        val table = RoutingTable()
        val dupCache = DuplicatePacketCache(clock = { now })
        val ttl = TtlPolicy(clock = { now })
        val link = FakeLink()

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "TestNode",
            sessionManager = sManager,
            graphRepository = repo,
            routingTable = table,
            duplicateCache = dupCache,
            ttlPolicy = ttl,
            link = link,
            clock = { now }
        )

        assertFalse("Engine keys must not be zeroized initially", engine.isZeroized())
        engine.close()
        assertTrue("Engine keys must be zeroized after close()", engine.isZeroized())
    }

    @Test
    fun offlineMeshVoiceTransport_stopZeroizesEngineKeys() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val now = 10_000L
        val identity = MeshIdentity(deviceId = UUID.randomUUID().toString(), displayName = "Rider-1")
        val locks = mockk<NetworkLocks>(relaxed = true)
        val arbiter = AudioOwnershipArbiter()
        val diagRepo = MeshDiagnosticsRepository()

        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            linkFactory = { FakeLink() },
            locks = locks,
            wifiWatcher = NoOpWifiNetworkWatcher,
            diagnosticsRepo = diagRepo,
            dispatcher = testDispatcher,
            gateAllowed = { true },
            clock = { now },
            hasMicPermission = { true }
        )

        val session = OfflineSession.create(now)
        transport.startDiscoveryOnly(session)

        val engine = transport.lastForwardingEngineForTest
        assertNotNull("ForwardingEngine must be initialized during startDiscoveryOnly", engine)
        assertFalse("Engine keys must not be zeroized while transport is running", engine!!.isZeroized())

        transport.stop()
        assertTrue("ForwardingEngine keys must be zeroized after transport.stop()", engine.isZeroized())
    }
}
