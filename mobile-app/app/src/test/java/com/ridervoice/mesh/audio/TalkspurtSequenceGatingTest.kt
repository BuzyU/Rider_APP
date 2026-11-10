package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TalkspurtSequenceGatingTest {

    private fun createTestEngine(id: String, clock: () -> Long): ForwardingEngine {
        val session = OfflineSession.create(clock())
        val graph = MeshGraphRepository(id, "Rider-$id", clock)
        val sessionManager = SessionManager(session, id, clock)
        val routingTable = RoutingTable()
        val dupCache = ReplayWindow()
        val ttlPolicy = TtlPolicy(clock = clock)

        val link = object : MeshLink {
            override val linkId: String = "link-$id"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames = MutableSharedFlow<MeshFrame>()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        }

        return ForwardingEngine(
            selfId = id,
            selfName = "Rider-$id",
            sessionManager = sessionManager,
            graphRepository = graph,
            routingTable = routingTable,
            replayWindow = dupCache,
            ttlPolicy = ttlPolicy,
            link = link,
            clock = clock,
            monotonicClock = clock
        )
    }

    @Test
    fun seqAdvancesOnGatedFramesTest_sequenceIncrementsContinuouslyAcrossGatedFrames() {
        var now = 100_000L
        val id = UUID.randomUUID().toString()
        val engine = createTestEngine(id) { now }

        val voicePcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x55 }

        // Send 3 active voice frames
        val p1 = engine.originateVoice(voicePcm)
        assertNotNull(p1)
        assertEquals(1, p1?.sequence)

        val p2 = engine.originateVoice(voicePcm)
        assertNotNull(p2)
        assertEquals(2, p2?.sequence)

        val p3 = engine.originateVoice(voicePcm)
        assertNotNull(p3)
        assertEquals(3, p3?.sequence)

        // Gate closes for frame 4 -> sends 1-byte end-of-talkspurt marker at seq 4
        val p4Marker = engine.originateVoice(voicePcm, isGated = true)
        assertNotNull("Gate close must emit end-of-talkspurt marker", p4Marker)
        assertEquals(4, p4Marker?.sequence)
        // Decrypted payload of marker is 1 byte, so encrypted payload is 1 + 16 = 17 bytes
        assertEquals(1, p4Marker?.payload?.size)

        // Gate remains closed for frames 5 and 6 -> suppressed, but seq must advance!
        val p5Gated = engine.originateVoice(voicePcm, isGated = true)
        assertNull("Subsequent gated frame 5 must be suppressed", p5Gated)

        val p6Gated = engine.originateVoice(voicePcm, isGated = true)
        assertNull("Subsequent gated frame 6 must be suppressed", p6Gated)

        // Gate re-opens at frame 7 -> transmitted frame must have sequence 7!
        val p7Voice = engine.originateVoice(voicePcm, isGated = false)
        assertNotNull("Reopened voice frame 7 must originate successfully", p7Voice)
        assertEquals("Sequence must have advanced across all gated frames to 7", 7, p7Voice?.sequence)
    }

    @Test
    fun endOfTalkspurtMarkerTest_gateClosingSendsExactlyOneOneByteMarker() {
        var now = 100_000L
        val id = UUID.randomUUID().toString()
        val engine = createTestEngine(id) { now }

        val voicePcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x55 }

        // Start talking
        val v1 = engine.originateVoice(voicePcm)
        assertNotNull(v1)

        // First gated frame -> 1-byte marker packet emitted
        val marker = engine.originateVoice(voicePcm, isGated = true)
        assertNotNull("First gated frame must emit marker", marker)
        assertEquals("Marker packet payload must be 1 byte", 1, marker?.payload?.size)

        // Second and third gated frames -> no more markers emitted
        val secondGated = engine.originateVoice(voicePcm, isGated = true)
        assertNull("Second gated frame must not emit marker", secondGated)

        val thirdGated = engine.originateVoice(voicePcm, isGated = true)
        assertNull("Third gated frame must not emit marker", thirdGated)
    }
}
