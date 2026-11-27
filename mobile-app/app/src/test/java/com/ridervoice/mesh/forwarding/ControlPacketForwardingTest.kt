package com.ridervoice.mesh.forwarding

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.convoy.ConvoyHash
import com.ridervoice.mesh.convoy.ConvoyMember
import com.ridervoice.mesh.convoy.ConvoyMembershipSnapshot
import com.ridervoice.mesh.convoy.ConvoyReadinessWireRecord
import com.ridervoice.mesh.convoy.ConvoyStateCodec
import com.ridervoice.mesh.convoy.ConvoyStateDecodeResult
import com.ridervoice.mesh.convoy.ConvoyStateWirePayload
import com.ridervoice.mesh.convoy.DecisionPhase
import com.ridervoice.mesh.convoy.LocalModeWire
import com.ridervoice.mesh.convoy.MemberRole
import com.ridervoice.mesh.convoy.ReadinessSource
import com.ridervoice.mesh.convoy.TransportDecision
import com.ridervoice.mesh.convoy.TransportMode
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
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
import com.ridervoice.mesh.protocol.ReplayResult
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.ShortIdDirectory
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ControlPacketForwardingTest {

    private class RecordingLink(override val linkId: String) : MeshLink {
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames = MutableSharedFlow<MeshFrame>()
        val sent = ArrayList<Pair<MeshEndpoint, ByteArray>>()
        var fixedEndpoints: Set<MeshEndpoint> = emptySet()

        override suspend fun start() {}
        override suspend fun stop() {}
        override fun endpoints(): Set<MeshEndpoint> = fixedEndpoints
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
            sent.add(endpoint to payload)
            return true
        }
    }

    @Test
    fun controlPacket_v4RoundTripAndTamperRejection() {
        val key = ByteArray(32) { (it * 3 + 5).toByte() }
        val src = UUID.randomUUID().toString()
        val payload = ByteArray(120) { it.toByte() }
        val pkt = MeshPacket(
            type = MeshPacketType.CONTROL,
            sourceId = src,
            previousHopId = src,
            sequence = 42,
            ttl = MeshConfig.DEFAULT_TTL,
            hopCount = 0,
            audioFormat = AudioFormatId.NONE,
            epoch = 999,
            payload = payload
        )
        val wire = MeshPacketV4.encode(pkt, key)
        val decoded = MeshPacketV4.decode(wire, key)
        assertTrue(decoded is DecodeResult.Ok)
        val ok = (decoded as DecodeResult.Ok).packet
        assertEquals(MeshPacketType.CONTROL, ok.type)
        assertEquals(42, ok.sequence)
        assertEquals(999, ok.epoch)
        assertArrayEquals(payload, ok.payload)

        val tampered = wire.copyOf()
        tampered[wire.size - 1] = (tampered[wire.size - 1].toInt() xor 0xFF).toByte()
        assertTrue(MeshPacketV4.decode(tampered, key) is DecodeResult.Invalid)
    }

    @Test
    fun originateControl_relaysAcrossTwoHops_andDeliversConvoyPayload() {
        val now = 100_000L
        val clock = { now }
        val session = OfflineSession.create(now)

        val idA = UUID.randomUUID().toString()
        val idB = UUID.randomUUID().toString()
        val idC = UUID.randomUUID().toString()

        val epA = MeshEndpoint("10.0.0.1", 45454)
        val epB = MeshEndpoint("10.0.0.2", 45454)
        val epC = MeshEndpoint("10.0.0.3", 45454)

        val linkA = RecordingLink("A").apply { fixedEndpoints = setOf(epB) }
        val linkB = RecordingLink("B")
        val linkC = RecordingLink("C")

        val deliveredB = ArrayList<MeshPacket>()
        val deliveredC = ArrayList<MeshPacket>()

        val engA = ForwardingEngine(idA, "A", SessionManager(session, idA, clock), MeshGraphRepository(idA, "A", clock), RoutingTable(), TtlPolicy(clock = clock), linkA, clock, localEpoch = 101)
        val engB = ForwardingEngine(idB, "B", SessionManager(session, idB, clock), MeshGraphRepository(idB, "B", clock), RoutingTable(), TtlPolicy(clock = clock), linkB, clock, localEpoch = 202, onLocalControlPacket = { deliveredB.add(it) })
        val engC = ForwardingEngine(idC, "C", SessionManager(session, idC, clock), MeshGraphRepository(idC, "C", clock), RoutingTable(), TtlPolicy(clock = clock), linkC, clock, localEpoch = 303, onLocalControlPacket = { deliveredC.add(it) })

        // Exchange direct HELLOs so B knows A & C, and C knows B
        val helloA = MeshPacketCodec.encode(MeshPacket(type = MeshPacketType.HELLO, sourceId = idA, previousHopId = idA, sequence = 1, epoch = 101, payload = HelloCodec.encode(HelloPayload(idA, "A", 1, listOf(idB)))), session.payloadKey())
        val helloC = MeshPacketCodec.encode(MeshPacket(type = MeshPacketType.HELLO, sourceId = idC, previousHopId = idC, sequence = 1, epoch = 303, payload = HelloCodec.encode(HelloPayload(idC, "C", 1, listOf(idB)))), session.payloadKey())
        val helloB = MeshPacketCodec.encode(MeshPacket(type = MeshPacketType.HELLO, sourceId = idB, previousHopId = idB, sequence = 1, epoch = 202, payload = HelloCodec.encode(HelloPayload(idB, "B", 1, listOf(idA, idC)))), session.payloadKey())

        assertTrue(engB.onIncomingFrame(MeshFrame(epA, helloA)) is ReceiveOutcome.Accepted)
        assertTrue(engB.onIncomingFrame(MeshFrame(epC, helloC)) is ReceiveOutcome.Accepted)
        assertTrue(engC.onIncomingFrame(MeshFrame(epB, helloB)) is ReceiveOutcome.Accepted)
        // Relay helloA via B to C so C learns shortId(A) as real forwarding does
        engB.onIncomingFrame(MeshFrame(epA, helloA))
        linkB.sent.clear()
        val helloAByB = MeshPacketV4.rewriteMutableFieldsForForward(helloA, 4, 1, MeshPacketV4.shortId(idB))
        assertTrue(engC.onIncomingFrame(MeshFrame(epB, helloAByB)) is ReceiveOutcome.Accepted)
        linkB.sent.clear()

        // Build 6-member max-size ConvoyStateWirePayload
        val sid = UUID.randomUUID()
        val members = (1..6).map { ConvoyMember("rider-$it", MemberRole.MESH_PARTICIPANT) }
        val snap = ConvoyMembershipSnapshot.create(sid, members)
        val propTag = ConvoyHash.computeProposerTag(sid, "rider-1")
        val decId = ConvoyHash.computeOfflineDecisionId(sid, 1L, propTag)
        val wireBytes = ConvoyStateCodec.encode(
            ConvoyStateWirePayload(
                decision = TransportDecision(1L, TransportMode.OFFLINE, DecisionPhase.ADOPTED, propTag, decisionId = decId),
                records = (0..5).map { idx ->
                    ConvoyReadinessWireRecord(idx, 100L + idx, idx + 1, true, true, true, false, LocalModeWire.OFFLINE_ACTIVE, ReadinessSource.AUTO_PROBE, 1L, ByteArray(8), 5, 0)
                }
            )
        )
        assertTrue(wireBytes.size <= MeshConfig.MAX_CONTROL_PAYLOAD_BYTES)

        engA.originateControl(wireBytes, listOf(epB))
        val frameAtB = linkA.sent.single { it.first == epB }.second
        val resB = engB.onIncomingFrame(MeshFrame(epA, frameAtB))
        assertTrue(resB is ReceiveOutcome.Accepted)
        assertEquals(1, deliveredB.size)
        assertEquals(listOf(idC), (resB as ReceiveOutcome.Accepted).forwardedTo)

        val relayedToC = linkB.sent.single { it.first == epC }.second
        val resC = engC.onIncomingFrame(MeshFrame(epB, relayedToC))
        assertTrue(resC is ReceiveOutcome.Accepted)
        assertEquals(1, deliveredC.size)
        assertEquals(1, deliveredC[0].hopCount)
        assertEquals(MeshConfig.DEFAULT_TTL - 1, deliveredC[0].ttl)
        assertEquals(idA, deliveredC[0].sourceId)
        val decodedPayload = ConvoyStateCodec.decode(deliveredC[0].payload, snap.meshParticipants.size)
        assertTrue(decodedPayload is ConvoyStateDecodeResult.Ok)
        assertEquals(6, (decodedPayload as ConvoyStateDecodeResult.Ok).payload.records.size)
    }

    @Test
    fun retiredEpoch_lateOldHelloDoesNotUnpinCurrentEpoch() {
        val now = 100_000L
        val clock = { now }
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()
        val peerId = UUID.randomUUID().toString()
        val link = RecordingLink("self")
        val receivedControl = ArrayList<MeshPacket>()
        val engine = ForwardingEngine(
            selfId, "Self", SessionManager(session, selfId, clock),
            MeshGraphRepository(selfId, "Self", clock), RoutingTable(), TtlPolicy(clock = clock),
            link, clock, onLocalControlPacket = { receivedControl.add(it) }
        )

        val hello55 = MeshPacketCodec.encode(MeshPacket(type = MeshPacketType.HELLO, sourceId = peerId, previousHopId = peerId, sequence = 1, epoch = 55, payload = HelloCodec.encode(HelloPayload(peerId, "P", 1, emptyList()))), session.payloadKey())
        val hello77 = MeshPacketCodec.encode(MeshPacket(type = MeshPacketType.HELLO, sourceId = peerId, previousHopId = peerId, sequence = 1, epoch = 77, payload = HelloCodec.encode(HelloPayload(peerId, "P", 2, emptyList()))), session.payloadKey())
        val lateHello55 = MeshPacketCodec.encode(MeshPacket(type = MeshPacketType.HELLO, sourceId = peerId, previousHopId = peerId, sequence = 2, epoch = 55, payload = HelloCodec.encode(HelloPayload(peerId, "P", 1, emptyList()))), session.payloadKey())
        val ctrl77 = MeshPacketCodec.encode(MeshPacket(type = MeshPacketType.CONTROL, sourceId = peerId, previousHopId = peerId, sequence = 1, epoch = 77, payload = byteArrayOf(1, 2, 3)), session.payloadKey())

        assertTrue(engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), hello55)) is ReceiveOutcome.Accepted)
        assertTrue(engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), hello77)) is ReceiveOutcome.Accepted)

        val lateRes = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), lateHello55))
        assertTrue(lateRes is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.STALE_EPOCH, (lateRes as ReceiveOutcome.Dropped).reason)

        assertTrue(engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), ctrl77)) is ReceiveOutcome.Accepted)
        assertEquals(1, receivedControl.size)
    }

    @Test
    fun replayWindowDefaultCapacity_holdsSixDevicesTimesFiveKindsWithoutEviction() {
        val rw = ReplayWindow()
        for (dev in 1..6) {
            for (kind in 0..4) {
                assertEquals(ReplayResult.NEW, rw.accept(dev, 1, kind, 10))
            }
        }
        // None of the 30 keys should have been evicted
        for (dev in 1..6) {
            for (kind in 0..4) {
                assertEquals(ReplayResult.DUPLICATE, rw.accept(dev, 1, kind, 10))
            }
        }
    }
}
