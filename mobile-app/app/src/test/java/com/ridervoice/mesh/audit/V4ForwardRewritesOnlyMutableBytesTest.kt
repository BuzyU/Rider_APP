package com.ridervoice.mesh.audit

import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.MeshPacketV4
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.ShortIdDirectory
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class V4ForwardRewritesOnlyMutableBytesTest {

    private class RecordingLink : MeshLink {
        override val linkId = "rec"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames = MutableSharedFlow<MeshFrame>()
        val sentFrames = mutableListOf<Pair<MeshEndpoint, ByteArray>>()

        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
            sentFrames.add(endpoint to payload.copyOf())
            return true
        }

        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() {}
    }

    @Test
    fun relayedDatagram_differsOnlyInMutableHopBytes_andTagRemainsValid() {
        val now = 100_000L
        val clock = { now }
        val session = OfflineSession.create(now)
        val nodeA = UUID.randomUUID().toString()
        val nodeB = UUID.randomUUID().toString() // Relay
        val nodeC = UUID.randomUUID().toString() // Destination

        val relayLink = RecordingLink()
        val relayGraph = MeshGraphRepository(nodeB, "RelayB", clock)
        val shortIdDir = ShortIdDirectory()

        val relayEngine = ForwardingEngine(
            selfId = nodeB,
            selfName = "RelayB",
            sessionManager = SessionManager(session, nodeB, clock),
            graphRepository = relayGraph,
            routingTable = RoutingTable(),
            replayWindow = ReplayWindow(),
            shortIdDirectory = shortIdDir,
            ttlPolicy = TtlPolicy(clock = clock),
            link = relayLink,
            clock = clock
        )

        // Setup graph: Node B hears Node A and Node C as direct neighbors
        val helloA = HelloPayload(deviceId = nodeA, name = "NodeA", graphVersion = 1, neighborIds = emptyList())
        relayEngine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.1", 45454), MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.HELLO, sourceId = nodeA, previousHopId = nodeA, sequence = 1, epoch = 10, payload = HelloCodec.encode(helloA)),
            session.payloadKey()
        )))

        val helloC = HelloPayload(deviceId = nodeC, name = "NodeC", graphVersion = 1, neighborIds = emptyList())
        relayEngine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.3", 45454), MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.HELLO, sourceId = nodeC, previousHopId = nodeC, sequence = 1, epoch = 20, payload = HelloCodec.encode(helloC)),
            session.payloadKey()
        )))

        relayLink.sentFrames.clear()

        // Node A sends VOICE to Node B
        val originalVoicePayload = ByteArray(40) { (it * 3).toByte() }
        val originalPacket = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = nodeA,
            previousHopId = nodeA,
            sequence = 42,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 10,
            payload = originalVoicePayload
        )
        val incomingWire = MeshPacketCodec.encode(originalPacket, session.payloadKey())
        val outcome = relayEngine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.1", 45454), incomingWire))

        assertTrue("Relay must accept voice frame", outcome is ReceiveOutcome.Accepted)
        assertEquals("Relay must have forwarded the frame", 1, relayLink.sentFrames.size)

        val relayedWire = relayLink.sentFrames[0].second
        assertEquals("Relayed wire size must match incoming wire size exactly", incomingWire.size, relayedWire.size)

        // Verify byte differences:
        // Byte 0: vtf (identical)
        assertEquals("vtf byte must be identical", incomingWire[0], relayedWire[0])

        // Byte 1: ttlHop (mutable)
        val incomingTtlHop = incomingWire[1].toInt() and 0xFF
        val relayedTtlHop = relayedWire[1].toInt() and 0xFF
        assertEquals(5, (incomingTtlHop ushr 4) and 0x0F)
        assertEquals(0, incomingTtlHop and 0x0F)
        assertEquals("TTL decremented to 4", 4, (relayedTtlHop ushr 4) and 0x0F)
        assertEquals("Hop incremented to 1", 1, relayedTtlHop and 0x0F)

        // Bytes 2..5: srcShort (identical)
        for (i in 2..5) {
            assertEquals("srcShort byte $i must be identical", incomingWire[i], relayedWire[i])
        }

        // Bytes 6..9: prevShort (rewritten to relay nodeB's shortId)
        val bShort = MeshPacketV4.shortId(nodeB)
        val relayedPrevShort = java.nio.ByteBuffer.wrap(relayedWire, 6, 4).int
        assertEquals("prevShort rewritten to relay shortId", bShort, relayedPrevShort)

        // Bytes 10..13: epoch (identical)
        for (i in 10..13) {
            assertEquals("epoch byte $i must be identical", incomingWire[i], relayedWire[i])
        }

        // Bytes 14..16: seq (identical)
        for (i in 14..16) {
            assertEquals("seq byte $i must be identical", incomingWire[i], relayedWire[i])
        }

        // Bytes 17..end: ciphertext and GCM tag MUST BE 100% BIT-FOR-BIT IDENTICAL!
        for (i in 17 until incomingWire.size) {
            assertEquals("Ciphertext and tag byte $i must be 100% bit-identical", incomingWire[i], relayedWire[i])
        }

        // Node C decodes and verifies the relayed packet
        val decodedAtC = MeshPacketV4.decode(relayedWire, session.payloadKey())
        assertTrue("Node C must successfully authenticate and decode the relayed datagram", decodedAtC is DecodeResult.Ok)
        val decodedPacket = (decodedAtC as DecodeResult.Ok).packet
        assertEquals(4, decodedPacket.ttl)
        assertEquals(1, decodedPacket.hopCount)
        assertEquals(bShort, decodedPacket.previousHopShortId)
        org.junit.Assert.assertArrayEquals(originalVoicePayload, decodedPacket.payload)
    }
}

