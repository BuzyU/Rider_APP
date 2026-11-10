package com.ridervoice.mesh.forwarding

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.ShortIdDirectory
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PayloadCrypto
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

class RelayForwardsBeforeDeliveryTest {

    @Test
    fun incomingVoiceFrame_forwardsBeforeLocalDelivery() {
        val eventLog = mutableListOf<String>()

        val testLink = object : MeshLink {
            override val linkId = "test"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames: SharedFlow<MeshFrame> = MutableSharedFlow()
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
                eventLog.add("forward")
                return true
            }
        }

        val session = OfflineSession.create(10_000L)
        val sessId = session.sessionId
        val selfId = UUID.randomUUID().toString()
        val senderId = UUID.randomUUID().toString()
        val neighborId = UUID.randomUUID().toString()

        val clock = { 10_000L }
        val sessionManager = SessionManager(session, selfId, clock)
        sessionManager.admit(selfId)
        sessionManager.admit(senderId)
        sessionManager.admit(neighborId)

        val graphRepo = MeshGraphRepository(selfId = selfId, selfName = selfId, clock = clock)
        val neighborEp = MeshEndpoint("192.168.1.100", MeshConfig.MESH_PORT)
        graphRepo.onHello(sourceId = neighborId, name = "neighbor", hopCount = 0, endpoint = neighborEp, neighborIds = listOf(selfId))

        val routingTable = RoutingTable()
        routingTable.recompute(graphRepo.currentSnapshot(), 10_000L)

        val shortIdDir = ShortIdDirectory().apply {
            register(selfId)
            register(senderId)
            register(neighborId)
        }

        val engine = ForwardingEngine(
            selfId = selfId,
            selfName = selfId,
            sessionManager = sessionManager,
            graphRepository = graphRepo,
            routingTable = routingTable,
            replayWindow = ReplayWindow(),
            shortIdDirectory = shortIdDir,
            ttlPolicy = TtlPolicy(clock = clock),
            link = testLink,
            clock = clock,
            onLocalVoicePacket = {
                eventLog.add("localVoice")
            },
            suppressRedundantRelays = false
        )

        // Construct incoming VOICE packet from senderId
        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x33 }
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = sessId,
            sourceId = senderId,
            sequence = 10,
            ttl = 5,
            hopCount = 0,
            previousHopId = senderId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 1,
            payload = pcm
        )
        val wire = MeshPacketCodec.encode(packet, session.payloadKey())

        val outcome = engine.onIncomingFrame(MeshFrame(MeshEndpoint("192.168.1.50", MeshConfig.MESH_PORT), wire))
        assertTrue("Outcome must be accepted: $outcome", outcome is ReceiveOutcome.Accepted)

        // Assert forwarding happened before local delivery
        assertEquals("Must log forward followed by localVoice: $eventLog", listOf("forward", "localVoice"), eventLog)
    }
}
