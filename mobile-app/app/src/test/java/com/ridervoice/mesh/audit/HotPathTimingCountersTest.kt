package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HotPathTimingCountersTest {

    @Test
    fun forwardingEngine_tracksHotPathTimingEMAs() {
        var now = 100_000L
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()
        val graph = MeshGraphRepository(selfId, "Alpha") { now }
        val routing = RoutingTable()
        val dup = DuplicatePacketCache(clock = { now })
        val ttl = TtlPolicy(clock = { now })
        val sm = SessionManager(session, selfId) { now }

        val dummyLink = object : MeshLink {
            override val linkId = "dummy"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        }

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Alpha",
            sessionManager = sm,
            graphRepository = graph,
            routingTable = routing,
            duplicateCache = dup,
            ttlPolicy = ttl,
            link = dummyLink,
            clock = { now }
        )

        assertEquals(0.0, engine.originateVoiceUsEma, 0.001)
        assertEquals(0.0, engine.onIncomingFrameUsEma, 0.001)

        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x33 }
        val packet = engine.originateVoice(pcm)
        assertTrue(packet != null)
        assertTrue("originateVoiceUsEma must be recorded and positive", engine.originateVoiceUsEma > 0.0)

        // Process an incoming frame (e.g. invalid bytes)
        val frame = MeshFrame(from = MeshEndpoint("192.168.49.2", 45454), data = ByteArray(20) { 0 })
        engine.onIncomingFrame(frame)
        assertTrue("onIncomingFrameUsEma must be recorded and positive", engine.onIncomingFrameUsEma > 0.0)

        val counters = engine.counters()
        assertEquals(engine.originateVoiceUsEma, counters.originateVoiceUsEma, 0.001)
        assertEquals(engine.onIncomingFrameUsEma, counters.onIncomingFrameUsEma, 0.001)
    }
}

