package com.ridervoice.mesh.graph

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ForwardingViewCacheTest {

    @Test
    fun voiceFramesDoNotRebuildGraph_andTopologyChangeIncrementsVersion() {
        var virtualTime = 10_000L
        val clock = { virtualTime }
        val selfId = UUID.randomUUID().toString()
        val peerId = UUID.randomUUID().toString()
        val repo = MeshGraphRepository(selfId = selfId, selfName = "Self", clock = clock, monotonicClock = clock)

        val ep = MeshEndpoint("192.168.1.10", MeshConfig.MESH_PORT)
        repo.onHello(peerId, "Peer1", hopCount = 0, endpoint = ep, neighborIds = emptyList())

        val initialRebuildCount = repo.graphRebuildCount.get()
        val initialVersion = repo.forwardingView().version

        val session = OfflineSession.create(10_000L)
        val sManager = SessionManager(session, selfId, clock, clock)
        sManager.admit(peerId)

        val dummyLink = object : MeshLink {
            override val linkId = "test"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        }

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Self",
            sessionManager = sManager,
            graphRepository = repo,
            routingTable = RoutingTable(),
            duplicateCache = DuplicatePacketCache(clock = clock),
            ttlPolicy = TtlPolicy(clock = clock, monotonicClock = clock),
            link = dummyLink,
            clock = clock,
            monotonicClock = clock
        )

        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME)
        // Send 1,000 voice frames with unchanged graph
        for (i in 1..1000) {
            virtualTime += 20L
            engine.originateVoice(pcm)
        }

        assertEquals("1,000 voice frames must NOT rebuild graph view", initialRebuildCount, repo.graphRebuildCount.get())
        assertEquals("Version must remain unchanged during voice frames", initialVersion, repo.forwardingView().version)

        // HELLO that changes a neighbor (new neighbor or endpoint change) -> view version + 1
        val peer2Id = UUID.randomUUID().toString()
        val peer2Ep = MeshEndpoint("192.168.1.20", MeshConfig.MESH_PORT)
        val changed = repo.onHello(peer2Id, "Peer2", hopCount = 0, endpoint = peer2Ep, neighborIds = emptyList())
        assertTrue("Structural HELLO must indicate change", changed)
        assertEquals("View version must increment by 1 on structural change", initialVersion + 1, repo.forwardingView().version)
        assertEquals("Rebuild count must increment on structural change", initialRebuildCount + 1, repo.graphRebuildCount.get())

        // Expired neighbor disappears within one sweep
        virtualTime += MeshConfig.NEIGHBOR_TIMEOUT_MS + 1000L
        val expiredChanged = repo.expire()
        assertTrue("Expire sweep must drop timed out neighbors", expiredChanged)
        assertTrue("Expired neighbors must be removed from forwardingView", repo.forwardingView().neighbors.isEmpty())
        assertEquals("View version must increment on neighbor expiry", initialVersion + 2, repo.forwardingView().version)
    }
}

