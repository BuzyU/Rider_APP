package com.ridervoice.mesh.audit

import com.ridervoice.models.Participant
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.session.HolderOfflineSecretProvider
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.network.LiveKitManager
import com.ridervoice.state.ActiveRideRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedListDedupTest {

    private val session = OfflineSession.create(10_000L)
    private val payloadKey = session.payloadKey()

    @Test
    fun matchingTag_droppedWhenLocalOnline() {
        val uid = "user-alice"
        val tag = PayloadCrypto.computeOnlineIdTag(payloadKey, uid)

        val lkParticipant = Participant(identity = uid, displayName = "Alice")
        val meshPeer = createPeer("mesh-alice", "Alice (Radio)", tag)

        val manager = createManager(listOf(lkParticipant), listOf(meshPeer), session)
        val list = manager.buildUnifiedParticipantList(isLocalOffline = false)

        assertEquals("Mesh peer must be dropped when tag matches online participant", 1, list.size)
        assertEquals("Only online participant remains", PeerTransportKind.ONLINE_LIVEKIT, list[0].transport)
        assertEquals(uid, list[0].id)
    }

    @Test
    fun untaggedMeshPeer_retainedWhenLocalOnline() {
        val uid = "user-alice"
        val lkParticipant = Participant(identity = uid, displayName = "Alice")
        val untaggedMeshPeer = createPeer("mesh-bob", "Bob", onlineIdTag = null)

        val manager = createManager(listOf(lkParticipant), listOf(untaggedMeshPeer), session)
        val list = manager.buildUnifiedParticipantList(isLocalOffline = false)

        assertEquals(2, list.size)
        assertTrue("List must contain online Alice", list.any { it.id == uid && it.transport == PeerTransportKind.ONLINE_LIVEKIT })
        assertTrue("List must retain untagged mesh Bob", list.any { it.id == "mesh-bob" && it.transport == PeerTransportKind.OFFLINE_MESH })
    }

    @Test
    fun unmatchedTagMeshPeer_retainedWhenLocalOnline() {
        val aliceUid = "user-alice"
        val charlieUid = "user-charlie"
        val charlieTag = PayloadCrypto.computeOnlineIdTag(payloadKey, charlieUid)

        val lkAlice = Participant(identity = aliceUid, displayName = "Alice")
        val meshCharlie = createPeer("mesh-charlie", "Charlie", charlieTag)

        val manager = createManager(listOf(lkAlice), listOf(meshCharlie), session)
        val list = manager.buildUnifiedParticipantList(isLocalOffline = false)

        assertEquals("Both Alice (online) and Charlie (mesh) must be present", 2, list.size)
        assertTrue(list.any { it.id == aliceUid })
        assertTrue(list.any { it.id == "mesh-charlie" })
    }

    @Test
    fun matchingTag_retainedWhenLocalOffline() {
        val uid = "user-alice"
        val tag = PayloadCrypto.computeOnlineIdTag(payloadKey, uid)

        val lkParticipant = Participant(identity = uid, displayName = "Alice")
        val meshPeer = createPeer("mesh-alice", "Alice (Radio)", tag)

        val manager = createManager(listOf(lkParticipant), listOf(meshPeer), session)
        val list = manager.buildUnifiedParticipantList(isLocalOffline = true)

        assertEquals("When local is offline, mesh peers are returned directly", 1, list.size)
        assertEquals(PeerTransportKind.OFFLINE_MESH, list[0].transport)
        assertEquals("mesh-alice", list[0].id)
    }

    @Test
    fun noSessionSecret_retainsAllPeers() {
        val uid = "user-alice"
        val tag = PayloadCrypto.computeOnlineIdTag(payloadKey, uid)

        val lkParticipant = Participant(identity = uid, displayName = "Alice")
        val meshPeer = createPeer("mesh-alice", "Alice", tag)

        // Without backup session, manager uses NullOfflineSecretProvider
        val manager = createManager(listOf(lkParticipant), listOf(meshPeer), session = null)
        val list = manager.buildUnifiedParticipantList(isLocalOffline = false)

        assertEquals("Without shared secret to compute tags, peers cannot be deduped", 2, list.size)
    }

    private fun createPeer(deviceId: String, name: String, onlineIdTag: ByteArray?): UnifiedVoicePeer =
        UnifiedVoicePeer(
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
            onlineIdTag = onlineIdTag
        )

    private fun createManager(
        lkParticipants: List<Participant>,
        meshPeers: List<UnifiedVoicePeer>,
        session: OfflineSession?
    ): MeshAutoSwitchManager {
        val mockLk = mockk<LiveKitManager>(relaxed = true)
        every { mockLk.participants } returns MutableStateFlow(lkParticipants)
        every { mockLk.activeSpeaker } returns MutableStateFlow(null)

        val mockMesh = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { mockMesh.participants } returns MutableStateFlow(meshPeers)

        val secretProvider = if (session != null) {
            object : com.ridervoice.mesh.session.OfflineSecretProvider {
                override val backupSession: kotlinx.coroutines.flow.StateFlow<OfflineSession?> = MutableStateFlow(session)
                override fun getSessionForOnlineRide(roomId: String): OfflineSession? = session
            }
        } else {
            NullOfflineSecretProvider()
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

