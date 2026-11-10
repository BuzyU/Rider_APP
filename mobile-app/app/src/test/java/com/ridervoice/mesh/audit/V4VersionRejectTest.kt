package com.ridervoice.mesh.audit

import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.MeshPacketV4
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class V4VersionRejectTest {

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
    fun v3Frame_rejectedAsBadVersion() {
        val now = 100_000L
        val clock = { now }
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = "Self",
            sessionManager = SessionManager(session, selfId, clock),
            graphRepository = MeshGraphRepository(selfId, "Self", clock),
            routingTable = RoutingTable(),
            ttlPolicy = TtlPolicy(clock = clock),
            link = mockLink(),
            clock = clock
        )

        // Synthesize a legacy v3 packet layout: magic(4)=0x52564D31, ver(1)=3, ...
        val v3Data = ByteArray(100)
        val buf = ByteBuffer.wrap(v3Data).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x52564D31) // MAGIC
        buf.put(3.toByte())    // VERSION 3

        val decodeRes = MeshPacketV4.decode(v3Data, session.payloadKey())
        assertTrue("Codec must return BAD_VERSION", decodeRes is DecodeResult.Invalid)
        assertEquals(DecodeResult.Reason.BAD_VERSION, (decodeRes as DecodeResult.Invalid).reason)

        val outcome = engine.onIncomingFrame(MeshFrame(MeshEndpoint("10.0.0.4", 45454), v3Data))
        assertTrue("Engine must drop frame", outcome is ReceiveOutcome.Dropped)
        assertEquals(ReceiveOutcome.DropReason.BAD_VERSION, (outcome as ReceiveOutcome.Dropped).reason)
        assertEquals(1L, engine.counters().droppedBadVersion)
    }

    @Test
    fun versionMismatch_rejected() {
        // vtf byte with version = 5 (0b101 shl 5 = 0xA0)
        val frameData = ByteArray(35)
        frameData[0] = (5 shl 5).toByte()

        val res = MeshPacketV4.decode(frameData, ByteArray(32))
        assertTrue(res is DecodeResult.Invalid)
        assertEquals(DecodeResult.Reason.BAD_VERSION, (res as DecodeResult.Invalid).reason)
    }
}

