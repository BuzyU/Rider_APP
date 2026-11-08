package com.ridervoice.mesh.audit

import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.models.Participant
import com.ridervoice.network.LiveKitManager
import com.ridervoice.state.ActiveRideRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineIdTagLifecycleTest {

    private val endpoint = MeshEndpoint("192.168.1.100", 45454)

    @Test
    fun directHello_tagPresent_thenHelloWithoutTag_clearsTagInSnapshot() {
        val repo = MeshGraphRepository("self-node", "Self", clock = { 1000L })
        val peerId = "00000000-0000-0000-0000-000000000001"
        val tag = "0123456789abcdef"

        // 1. Initial HELLO with tag
        val changed1 = repo.onHello(
            sourceId = peerId,
            name = "Rider-1",
            hopCount = 0,
            endpoint = endpoint,
            neighborIds = emptyList(),
            sequence = 1,
            epoch = 1,
            onlineIdTag = tag
        )
        assertTrue(changed1)
        val snap1 = repo.currentSnapshot()
        assertEquals(tag, snap1.nodes[peerId]?.onlineIdTag)

        // 2. Subsequent HELLO from same peer without tag (null)
        val changed2 = repo.onHello(
            sourceId = peerId,
            name = "Rider-1",
            hopCount = 0,
            endpoint = endpoint,
            neighborIds = emptyList(),
            sequence = 2,
            epoch = 1,
            onlineIdTag = null
        )
        assertTrue("Clearing onlineIdTag must trigger changed = true", changed2)
        val snap2 = repo.currentSnapshot()
        assertNull("onlineIdTag must be cleared to null in snapshot", snap2.nodes[peerId]?.onlineIdTag)
    }

    @Test
    fun remoteReport_tagPresent_thenReportWithoutTag_clearsTagInSnapshot() {
        val repo = MeshGraphRepository("self-node", "Self", clock = { 1000L })
        val remoteId = "00000000-0000-0000-0000-000000000002"
        val tag = "fedcba9876543210"

        // 1. Remote HELLO (hopCount > 0) with tag
        val changed1 = repo.onHello(
            sourceId = remoteId,
            name = "Remote-Rider",
            hopCount = 1,
            endpoint = endpoint,
            neighborIds = emptyList(),
            sequence = 1,
            epoch = 1,
            onlineIdTag = tag
        )
        assertTrue(changed1)
        assertEquals(tag, repo.currentSnapshot().nodes[remoteId]?.onlineIdTag)

        // 2. Remote HELLO without tag
        val changed2 = repo.onHello(
            sourceId = remoteId,
            name = "Remote-Rider",
            hopCount = 1,
            endpoint = endpoint,
            neighborIds = emptyList(),
            sequence = 2,
            epoch = 1,
            onlineIdTag = null
        )
        assertTrue("Clearing tag on remote report must trigger changed = true", changed2)
        assertNull("onlineIdTag must be cleared to null on remote node", repo.currentSnapshot().nodes[remoteId]?.onlineIdTag)
    }

    @Test
    fun meshPeer_dedupedWithMatchingOnlineTag_andUnbridgedWhenOnlineLeaves() {
        val session = OfflineSession.create(10_000L)
        val payloadKey = session.payloadKey()
        val uid = "user-alice"
        val tag = com.ridervoice.mesh.protocol.PayloadCrypto.computeOnlineIdTag(payloadKey, uid)

        val lkParticipantsFlow = MutableStateFlow<List<Participant>>(listOf(Participant(identity = uid, displayName = "Alice", isGhost = false)))
        val meshPeer = UnifiedVoicePeer(
            id = "mesh-alice",
            name = "Alice (Mesh)",
            isOnline = true,
            transport = PeerTransportKind.OFFLINE_MESH,
            hopCount = 1,
            nextHopId = null,
            isDirect = true,
            isRelayed = false,
            routeAgeMs = 0L,
            isSpeaking = false,
            onlineIdTag = tag
        )
        val meshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { meshTransport.participants } returns MutableStateFlow(listOf(meshPeer))

        val lkManager = mockk<LiveKitManager>(relaxed = true)
        every { lkManager.participants } returns lkParticipantsFlow

        val secretProvider = object : com.ridervoice.mesh.session.OfflineSecretProvider {
            override val backupSession: kotlinx.coroutines.flow.StateFlow<OfflineSession?> = MutableStateFlow(session)
            override fun getSessionForOnlineRide(roomId: String): OfflineSession? = session
        }

        val manager = MeshAutoSwitchManager(
            activeRideRepository = mockk(relaxed = true),
            liveKitManager = lkManager,
            coordinator = mockk(relaxed = true),
            meshTransport = meshTransport,
            healthSignalsProvider = mockk(relaxed = true),
            secretProvider = secretProvider
        )

        // When online Alice is present, mesh peer is deduped
        val list1 = manager.buildUnifiedParticipantList(isLocalOffline = false)
        assertEquals(1, list1.size)
        assertEquals(PeerTransportKind.ONLINE_LIVEKIT, list1[0].transport)

        // When online Alice leaves, mesh peer un-bridges
        lkParticipantsFlow.value = emptyList()
        val list2 = manager.buildUnifiedParticipantList(isLocalOffline = false)
        assertEquals(1, list2.size)
        assertEquals(PeerTransportKind.OFFLINE_MESH, list2[0].transport)
        assertEquals("mesh-alice", list2[0].id)
    }
}

