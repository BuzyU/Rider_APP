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

class ShortIdCollisionTest {

    private fun mockLink(): MeshLink = object : MeshLink {
        override val linkId = "mock"
        override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
        override val incomingFrames = MutableSharedFlow<MeshFrame>()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override fun send(endpoint: MeshEndpoint, payload: ByteArray) = true
    }

    @Test
    fun collidingShortIdOnHello_ignoresLaterDeviceAndIncrementsCollisionCount() {
        val now = 100_000L
        val clock = { now }
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()

        // Two distinct full UUIDs sharing the same first 4 bytes (0x11223344)
        val devA = "11223344-0000-4000-8000-000000000001"
        val devB = "11223344-ffff-4fff-8fff-ffffffffffff"
        assertEquals(MeshPacketV4.shortId(devA), MeshPacketV4.shortId(devB))

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Self",
            sessionManager = SessionManager(session, selfId, clock),
            graphRepository = MeshGraphRepository(selfId, "Self", clock),
            routingTable = RoutingTable(),
            replayWindow = ReplayWindow(),
            shortIdDirectory = ShortIdDirectory(),
            ttlPolicy = TtlPolicy(clock = clock),
            link = mockLink(),
            clock = clock
        )

        // 1. First device registers via HELLO successfully
        val helloA = HelloPayload(deviceId = devA, name = "DeviceA", graphVersion = 1, neighborIds = emptyList())
        val wireA = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.HELLO, sourceId = devA, previousHopId = devA, sequence = 1, epoch = 10, payload = HelloCodec.encode(helloA)),
            session.payloadKey()
        )
        val resA = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.2", 45454), wireA))
        assertTrue("First device must be accepted", resA is ReceiveOutcome.Accepted)

        // 2. Second colliding device arrives via HELLO
        val helloB = HelloPayload(deviceId = devB, name = "DeviceB", graphVersion = 1, neighborIds = emptyList())
        val wireB = MeshPacketCodec.encode(
            MeshPacket(type = MeshPacketType.HELLO, sourceId = devB, previousHopId = devB, sequence = 1, epoch = 20, payload = HelloCodec.encode(helloB)),
            session.payloadKey()
        )
        val resB = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.3", 45454), wireB))

        assertTrue("Colliding device must be dropped: $resB", resB is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.ID_COLLISION, (resB as ReceiveOutcome.Dropped).reason)
        assertEquals(1L, engine.counters().idCollision)
    }
}

