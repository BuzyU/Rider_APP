package com.ridervoice.mesh.audit

import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PayloadCrypto
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

class UnadvertisedFormatRejectedTest {

    private fun uuid(tag: String): String =
        UUID.nameUUIDFromBytes("unadv-$tag".toByteArray()).toString()

    @Test
    fun receivingVoicePacketWithUnadvertisedFormat_dropsBadFormat() {
        val now = 100_000L
        val session = OfflineSession.create(now)
        val idA = uuid("A")
        val idB = uuid("B")

        val graphA = MeshGraphRepository(idA, "Rider-A") { now }
        val sessionManagerA = SessionManager(session, idA) { now }
        val routingTableA = RoutingTable()
        val dupCacheA = ReplayWindow()
        val ttlPolicyA = TtlPolicy(clock = { now })

        val linkA = object : MeshLink {
            override val linkId: String = "link-A"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames = MutableSharedFlow<MeshFrame>()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        }

        val engineA = ForwardingEngine(
            selfId = idA,
            selfName = "Rider-A",
            sessionManager = sessionManagerA,
            graphRepository = graphA,
            routingTable = routingTableA,
            replayWindow = dupCacheA,
            ttlPolicy = ttlPolicyA,
            link = linkA,
            clock = { now },
            monotonicClock = { now }
        )

        // Node A only advertises PCM
        engineA.selfSupportedFormatsProvider = { AudioFormatId.BITMASK_PCM }
        graphA.selfSupportedFormatsProvider = { AudioFormatId.BITMASK_PCM }

        // Admit B into session
        sessionManagerA.admitAndTouch(idB)

        // Node B creates an Opus voice packet (which Node A did not advertise)
        val dummyAuthKey = session.authKey()
        val dummySourceKey = session.payloadKey()
        val (sHi, sLo) = MeshPacketCodec.uuidParts(session.sessionId)
        val (bHi, bLo) = MeshPacketCodec.uuidParts(idB)

        val aad = PayloadCrypto.aad(
            sessionHi = sHi,
            sessionLo = sLo,
            sourceHi = bHi,
            sourceLo = bLo,
            typeCode = MeshPacketType.VOICE.code,
            epoch = 1,
            sequence = 1,
            captureTimestamp = now,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS
        )
        val nonce = PayloadCrypto.nonce(1, 1, MeshPacketType.VOICE.code)
        val plainOpus = ByteArray(40) { 0x42 }
        val sealedOpus = PayloadCrypto.seal(dummySourceKey, nonce, aad, plainOpus)

        val opusPacket = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = session.sessionId,
            sourceId = idB,
            sequence = 1,
            captureTimestamp = now,
            ttl = 5,
            hopCount = 0,
            previousHopId = idB,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 1,
            payload = sealedOpus,
            sessionHi = sHi,
            sessionLo = sLo,
            sourceHi = bHi,
            sourceLo = bLo,
            prevHopHi = bHi,
            prevHopLo = bLo
        )
        val wire = MeshPacketCodec.encode(opusPacket, dummyAuthKey)
        val outcome = engineA.onIncomingFrame(MeshFrame(data = wire, from = MeshEndpoint("192.168.49.2", 45454)))

        assertTrue("Outcome must be Dropped", outcome is ReceiveOutcome.Dropped)
        assertEquals("Drop reason must be BAD_FORMAT", ReceiveOutcome.DropReason.BAD_FORMAT, (outcome as ReceiveOutcome.Dropped).reason)
    }
}
