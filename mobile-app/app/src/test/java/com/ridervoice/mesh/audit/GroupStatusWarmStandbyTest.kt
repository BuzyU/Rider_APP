package com.ridervoice.mesh.audit

import com.ridervoice.models.Participant
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.session.HolderOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.GroupConnectivityStatus
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.switching.computeGroupConnectivity
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.network.LiveKitManager
import com.ridervoice.state.ActiveRideRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class GroupStatusWarmStandbyTest {

    private val session = OfflineSession.create(10_000L)
    private val payloadKey = session.payloadKey()

    @Test
    fun fourRidersOnlineAndVisibleOnMesh_resolvesToOnlineAll_andDeduplicates() {
        val r1 = "user-1"
        val r2 = "user-2"
        val r3 = "user-3"
        val r4 = "user-4"

        val lkParticipants = listOf(
            Participant(identity = r1, displayName = "Rider 1"),
            Participant(identity = r2, displayName = "Rider 2"),
            Participant(identity = r3, displayName = "Rider 3"),
            Participant(identity = r4, displayName = "Rider 4")
        )

        val meshPeers = listOf(
            createMeshPeer("mesh-1", "Rider 1", r1),
            createMeshPeer("mesh-2", "Rider 2", r2),
            createMeshPeer("mesh-3", "Rider 3", r3),
            createMeshPeer("mesh-4", "Rider 4", r4)
        )

        val autoSwitchManager = createTestAutoSwitchManager(lkParticipants, meshPeers)
        val unifiedList = autoSwitchManager.buildUnifiedParticipantList(isLocalOffline = false)

        assertEquals("Deduplicated list must contain exactly 4 peers", 4, unifiedList.size)
        assertEquals(
            "All peers must be from ONLINE_LIVEKIT transport",
            4,
            unifiedList.count { it.transport == PeerTransportKind.ONLINE_LIVEKIT }
        )

        val groupStatus = computeGroupConnectivity(isLocalOffline = false, peers = unifiedList)
        assertEquals("Group connectivity must be ONLINE_ALL", GroupConnectivityStatus.ONLINE_ALL, groupStatus)
    }

    @Test
    fun oneRiderMeshOnly_resolvesToMixed() {
        val r1 = "user-1"
        val r2 = "user-2"
        val r3 = "user-3"
        val r4 = "user-4" // Only on mesh, not in LiveKit

        val lkParticipants = listOf(
            Participant(identity = r1, displayName = "Rider 1"),
            Participant(identity = r2, displayName = "Rider 2"),
            Participant(identity = r3, displayName = "Rider 3")
        )

        val meshPeers = listOf(
            createMeshPeer("mesh-1", "Rider 1", r1),
            createMeshPeer("mesh-2", "Rider 2", r2),
            createMeshPeer("mesh-3", "Rider 3", r3),
            createMeshPeer("mesh-4", "Rider 4", r4) // Mesh-only peer with tag unmatched by any LK participant
        )

        val autoSwitchManager = createTestAutoSwitchManager(lkParticipants, meshPeers)
        val unifiedList = autoSwitchManager.buildUnifiedParticipantList(isLocalOffline = false)

        assertEquals("List must contain 3 online + 1 mesh peer", 4, unifiedList.size)
        assertEquals(3, unifiedList.count { it.transport == PeerTransportKind.ONLINE_LIVEKIT })
        assertEquals(1, unifiedList.count { it.transport == PeerTransportKind.OFFLINE_MESH })

        val groupStatus = computeGroupConnectivity(isLocalOffline = false, peers = unifiedList)
        assertEquals("Group connectivity must be MIXED when a rider is mesh-only", GroupConnectivityStatus.MIXED, groupStatus)
    }

    @Test
    fun localOffline_resolvesToOfflineMeshOnly() {
        val r1 = "user-1"
        val r2 = "user-2"

        val meshPeers = listOf(
            createMeshPeer("mesh-1", "Rider 1", r1),
            createMeshPeer("mesh-2", "Rider 2", r2)
        )

        val autoSwitchManager = createTestAutoSwitchManager(emptyList(), meshPeers)
        val unifiedList = autoSwitchManager.buildUnifiedParticipantList(isLocalOffline = true)

        assertEquals(2, unifiedList.size)
        assertEquals(2, unifiedList.count { it.transport == PeerTransportKind.OFFLINE_MESH })

        val groupStatus = computeGroupConnectivity(isLocalOffline = true, peers = unifiedList)
        assertEquals("Group connectivity must be OFFLINE_MESH_ONLY when local is offline", GroupConnectivityStatus.OFFLINE_MESH_ONLY, groupStatus)
    }

    private fun createMeshPeer(deviceId: String, name: String, onlineIdentity: String): UnifiedVoicePeer {
        val tag = PayloadCrypto.computeOnlineIdTag(payloadKey, onlineIdentity)
        return UnifiedVoicePeer(
            id = deviceId,
            name = name,
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
    }

    private fun createTestAutoSwitchManager(
        lkParticipants: List<Participant>,
        meshPeers: List<UnifiedVoicePeer>
    ): MeshAutoSwitchManager {
        val mockLk = mockk<LiveKitManager>(relaxed = true)
        every { mockLk.participants } returns MutableStateFlow(lkParticipants)
        every { mockLk.activeSpeaker } returns MutableStateFlow(null)

        val mockMesh = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { mockMesh.participants } returns MutableStateFlow(meshPeers)

        val secretProvider = object : com.ridervoice.mesh.session.OfflineSecretProvider {
            override val backupSession: kotlinx.coroutines.flow.StateFlow<OfflineSession?> = MutableStateFlow(session)
            override fun getSessionForOnlineRide(roomId: String): OfflineSession? = session
        }

        val mockRideRepo = mockk<ActiveRideRepository>(relaxed = true)
        every { mockRideRepo.state } returns MutableStateFlow<ActiveRideRepository.ActiveRide?>(null)

        return MeshAutoSwitchManager(
            activeRideRepository = mockRideRepo,
            liveKitManager = mockLk,
            coordinator = mockk(relaxed = true),
            meshTransport = mockMesh,
            healthSignalsProvider = mockk(relaxed = true),
            secretProvider = secretProvider
        )
    }
}

