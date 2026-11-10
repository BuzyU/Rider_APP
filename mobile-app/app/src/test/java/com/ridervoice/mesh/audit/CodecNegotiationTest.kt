package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.audio.MultiSourceJitterBufferManager
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class CodecNegotiationTest {

    private class TestNode(
        val name: String,
        val id: String,
        val supportedMask: Int,
        val session: OfflineSession,
        val clockProvider: () -> Long
    ) {
        val graph = MeshGraphRepository(id, name, clockProvider)
        val sessionManager = SessionManager(session, id, clockProvider)
        val routingTable = RoutingTable()
        val dupCache = DuplicatePacketCache(clock = clockProvider)
        val ttlPolicy = TtlPolicy(clock = clockProvider)
        val jitterBuffer = MultiSourceJitterBufferManager(clock = clockProvider)

        val link = object : MeshLink {
            override val linkId: String = "link-$name"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames = MutableSharedFlow<MeshFrame>()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        }

        val engine = ForwardingEngine(
            selfId = id,
            selfName = name,
            sessionManager = sessionManager,
            graphRepository = graph,
            routingTable = routingTable,
            duplicateCache = dupCache,
            ttlPolicy = ttlPolicy,
            link = link,
            clock = clockProvider,
            monotonicClock = clockProvider
        )

        init {
            graph.selfSupportedFormatsProvider = { supportedMask }
            engine.selfSupportedFormatsProvider = { supportedMask }
            engine.onFormatChangedListener = { jitterBuffer.resetAll() }
        }
    }

    private fun uuid(tag: String): String =
        UUID.nameUUIDFromBytes("nego-$tag".toByteArray()).toString()

    @Test
    fun allOpus_negotiatesOpus() {
        var now = 100_000L
        val session = OfflineSession.create(now)
        val nodeA = TestNode("A", uuid("A"), AudioFormatId.BITMASK_ALL, session) { now }
        val nodeB = TestNode("B", uuid("B"), AudioFormatId.BITMASK_ALL, session) { now }

        // Exchange HELLO between A and B
        nodeA.graph.onHello(
            sourceId = nodeB.id,
            name = nodeB.name,
            hopCount = 0,
            endpoint = MeshEndpoint("192.168.49.2", 45454),
            neighborIds = listOf(nodeA.id),
            supportedFormats = nodeB.supportedMask
        )

        val negotiatedA = nodeA.engine.currentNegotiatedFormat()
        assertEquals("When all members support Opus, format must be Opus", AudioFormatId.OPUS_16K_MONO_20MS, negotiatedA)
    }

    @Test
    fun onePcmMember_negotiatesPcm() {
        var now = 100_000L
        val session = OfflineSession.create(now)
        val nodeA = TestNode("A", uuid("A"), AudioFormatId.BITMASK_ALL, session) { now }
        val nodeB = TestNode("B", uuid("B"), AudioFormatId.BITMASK_PCM, session) { now } // PCM only

        nodeA.graph.onHello(
            sourceId = nodeB.id,
            name = nodeB.name,
            hopCount = 0,
            endpoint = MeshEndpoint("192.168.49.2", 45454),
            neighborIds = listOf(nodeA.id),
            supportedFormats = nodeB.supportedMask
        )

        val negotiatedA = nodeA.engine.currentNegotiatedFormat()
        assertEquals("When one member is PCM-only, negotiated format must be PCM", AudioFormatId.PCM16_16K_MONO, negotiatedA)
    }

    @Test
    fun lateJoiningPcmMember_dropsToPcmAndResetsJitterBuffers() {
        var now = 100_000L
        val session = OfflineSession.create(now)
        val nodeA = TestNode("A", uuid("A"), AudioFormatId.BITMASK_ALL, session) { now }
        val nodeB = TestNode("B", uuid("B"), AudioFormatId.BITMASK_ALL, session) { now }
        val nodeC = TestNode("C", uuid("C"), AudioFormatId.BITMASK_PCM, session) { now } // Late PCM member

        // A and B connect initially
        nodeA.graph.onHello(
            sourceId = nodeB.id,
            name = nodeB.name,
            hopCount = 0,
            endpoint = MeshEndpoint("192.168.49.2", 45454),
            neighborIds = listOf(nodeA.id),
            supportedFormats = nodeB.supportedMask
        )
        assertEquals(AudioFormatId.OPUS_16K_MONO_20MS, nodeA.engine.currentNegotiatedFormat())

        // Feed some frames to A's jitter buffer
        nodeA.jitterBuffer.onVoicePacket(nodeB.id, 1, ByteArray(40), 1, AudioFormatId.OPUS_16K_MONO_20MS)
        assertEquals(1, nodeA.jitterBuffer.stats().activeSources)

        // C joins late with PCM only
        nodeA.graph.onHello(
            sourceId = nodeC.id,
            name = nodeC.name,
            hopCount = 0,
            endpoint = MeshEndpoint("192.168.49.3", 45454),
            neighborIds = listOf(nodeA.id),
            supportedFormats = nodeC.supportedMask
        )

        // Format must drop immediately to PCM
        val newFormat = nodeA.engine.currentNegotiatedFormat()
        assertEquals("Format must drop to PCM when PCM member joins", AudioFormatId.PCM16_16K_MONO, newFormat)

        // Jitter buffers must have been reset cleanly
        assertEquals("Jitter buffers must be cleanly reset on format change", 0, nodeA.jitterBuffer.stats().activeSources)
    }

    @Test
    fun pcmMemberLeaves_risesBackToOpusAfterTwoSeconds() {
        var now = 100_000L
        val session = OfflineSession.create(now)
        val nodeA = TestNode("A", uuid("A"), AudioFormatId.BITMASK_ALL, session) { now }
        val nodeB = TestNode("B", uuid("B"), AudioFormatId.BITMASK_ALL, session) { now }
        val nodeC = TestNode("C", uuid("C"), AudioFormatId.BITMASK_PCM, session) { now } // PCM member

        // A, B, C connect
        nodeA.graph.onHello(nodeB.id, nodeB.name, 0, MeshEndpoint("192.168.49.2", 45454), listOf(nodeA.id), supportedFormats = nodeB.supportedMask)
        nodeA.graph.onHello(nodeC.id, nodeC.name, 0, MeshEndpoint("192.168.49.3", 45454), listOf(nodeA.id), supportedFormats = nodeC.supportedMask)

        assertEquals(AudioFormatId.PCM16_16K_MONO, nodeA.engine.currentNegotiatedFormat())

        // C leaves
        nodeA.graph.onBye(nodeC.id)

        // Immediately after C leaves: must remain PCM during the 2s stability window
        assertEquals("Must remain PCM before 2s of stable membership", AudioFormatId.PCM16_16K_MONO, nodeA.engine.currentNegotiatedFormat())

        // Advance 1.5 seconds: still within 2s window
        now += 1500L
        assertEquals("Must remain PCM at 1.5s", AudioFormatId.PCM16_16K_MONO, nodeA.engine.currentNegotiatedFormat())

        // Advance past 2 seconds (e.g. 2100 ms total): must rise back to Opus
        now += 600L
        assertEquals("Must rise back to Opus after 2s of stable membership", AudioFormatId.OPUS_16K_MONO_20MS, nodeA.engine.currentNegotiatedFormat())
    }

    @Test
    fun unknownMemberFormat_fallsBackToPcmAfterThreeHelloIntervals() {
        var now = 100_000L
        val session = OfflineSession.create(now)
        val nodeA = TestNode("A", uuid("A"), AudioFormatId.BITMASK_ALL, session) { now }
        val nodeB = TestNode("B", uuid("B"), AudioFormatId.BITMASK_ALL, session) { now }

        // B connects but does NOT advertise supported formats (supportedFormats == null)
        nodeA.graph.onHello(
            sourceId = nodeB.id,
            name = nodeB.name,
            hopCount = 0,
            endpoint = MeshEndpoint("192.168.49.2", 45454),
            neighborIds = listOf(nodeA.id),
            supportedFormats = null
        )

        // For first 2 intervals (< 3 * 1500ms = 4500ms): must wait (format is null)
        assertNull("Must wait when member format mask is unknown", nodeA.engine.currentNegotiatedFormat())

        now += 3000L // 2 intervals elapsed
        assertNull("Must still wait after 2 intervals", nodeA.engine.currentNegotiatedFormat())

        // After 3 intervals (>= 4500ms): must fall back to PCM
        now += 1600L // 4600ms total
        assertEquals("Must fall back to PCM when member format unknown after 3 intervals", AudioFormatId.PCM16_16K_MONO, nodeA.engine.currentNegotiatedFormat())
    }
}
