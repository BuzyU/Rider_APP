package com.ridervoice.mesh.audit

import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.session.HolderOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.models.Participant
import com.ridervoice.network.LiveKitManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostDoesNotSuppressMeshPeerTest {

    @Test
    fun ghostParticipant_doesNotSuppressMeshPeer_evenWithMatchingTag() {
        val session = OfflineSession.create(10_000L)
        val payloadKey = session.payloadKey()
        val uid = "user-bob"
        val tag = PayloadCrypto.computeOnlineIdTag(payloadKey, uid)

        // Bob is present in LiveKit list, but marked as isGhost = true (disconnected/reconnecting ghost)
        val ghostBob = Participant(identity = uid, displayName = "Bob (Online Ghost)", isGhost = true)
        val meshBob = UnifiedVoicePeer(
            id = "mesh-bob-device",
            name = "Bob (Radio)",
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

        val lkManager = mockk<LiveKitManager>(relaxed = true)
        every { lkManager.participants } returns MutableStateFlow(listOf(ghostBob))

        val meshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { meshTransport.participants } returns MutableStateFlow(listOf(meshBob))

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

        val unifiedList = manager.buildUnifiedParticipantList(isLocalOffline = false)

        // A ghost participant must not suppress the mesh peer; the mesh peer must remain visible
        assertTrue("Unified list must retain mesh Bob when online Bob is a ghost",
            unifiedList.any { it.id == "mesh-bob-device" && it.transport == PeerTransportKind.OFFLINE_MESH })
    }
}

