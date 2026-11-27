package com.ridervoice.mesh.convoy

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.LiveKitPort
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.models.LobbyHost
import com.ridervoice.models.LobbyInviteEntry
import com.ridervoice.models.LobbyRiderInfo
import com.ridervoice.models.LobbyStatus
import com.ridervoice.models.Participant
import com.ridervoice.network.ApiService
import com.ridervoice.network.ConnectionState
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.viewmodels.LobbyViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ConvoyPhase5IntegrationTest {

    @Test
    fun encryptedConvoyStateStore_writeAheadAtomicRoundTripAndFailClosedCorruption() {
        var now = 1_000_000L
        val backend = InMemoryConvoyKeyValueBackend()
        val store = EncryptedConvoyStateStore(backend, nowMs = { now })
        val sid = UUID.randomUUID()

        val snap = ConvoyMembershipSnapshot.create(
            sid,
            listOf(
                ConvoyMember("host-uid", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("rider-2", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("rider-3", MemberRole.NON_MESH_PARTICIPANT)
            )
        )
        assertTrue(store.saveMembership(snap))

        // Write-ahead reservation BEFORE advertising decision
        assertTrue(store.saveGenerationHighWaterWriteAhead(sid, 3L))
        val loadedAfterReserve = store.load(sid)
        assertNotNull(loadedAfterReserve)
        assertEquals(3L, loadedAfterReserve!!.generationHighWater)
        assertFalse(loadedAfterReserve.generationUnknown)
        assertNull(loadedAfterReserve.acceptedDecision)
        assertEquals(snap.meshParticipants, loadedAfterReserve.membership?.meshParticipants)

        // Atomic decision save
        val tag = ConvoyHash.computeProposerTag(sid, "host-uid")
        val decId = ConvoyHash.computeOfflineDecisionId(sid, 3L, tag)
        val decision = TransportDecision(3L, TransportMode.OFFLINE, DecisionPhase.ADOPTED, tag, decisionId = decId)
        assertTrue(store.saveAcceptedDecisionAtomically(sid, decision, 3L))

        val loadedDec = store.load(sid)
        assertNotNull(loadedDec)
        assertEquals(decision, loadedDec!!.acceptedDecision)
        assertArrayEquals(snap.rosterHash, loadedDec.membership!!.rosterHash)

        // Corrupt / torn storage record MUST fail closed to generationUnknown = true
        backend.putStringSync("convoy_state_$sid", "corrupted-torn-record!!!")
        val corruptLoaded = store.load(sid)
        assertNotNull(corruptLoaded)
        assertTrue(corruptLoaded!!.generationUnknown)
        assertNull(corruptLoaded.acceptedDecision)
    }

    @Test
    fun encryptedConvoyStateStore_expiresAfterLifetime() {
        var now = 10_000L
        val backend = InMemoryConvoyKeyValueBackend()
        val store = EncryptedConvoyStateStore(backend, nowMs = { now })
        val sid = UUID.randomUUID()
        assertTrue(store.saveGenerationHighWaterWriteAhead(sid, 2L))
        assertNotNull(store.load(sid))

        // Advance beyond 8h session lifetime
        now += 9 * 60 * 60 * 1000L
        assertNull(store.load(sid))
    }

    @Test
    fun liveKitVoiceTransport_pauseEnablesSilentProbeMode_andResumePromotesAudioWithoutDualOwnership() = runBlocking {
        val probeFlags = ArrayList<Boolean>()
        var promoteCount = 0
        val arbiter = AudioOwnershipArbiter()

        val spyPort = object : LiveKitPort {
            override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.CONNECTED)
            override val activeSpeaker: StateFlow<String?> = MutableStateFlow(null)
            override val participants: StateFlow<List<Participant>> = MutableStateFlow(emptyList())
            override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) {}
            override fun disconnect() {}
            override fun setUserMuted(muted: Boolean) {}
            override fun setDeafened(deafened: Boolean) {}
            override fun setSilentProbeMode(enabled: Boolean) { probeFlags.add(enabled) }
            override fun promoteFromSilentProbeToAudio() { promoteCount++ }
        }

        val lkTransport = LiveKitVoiceTransport(
            liveKitPort = spyPort,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            dispatcher = Dispatchers.Unconfined
        )

        arbiter.claimLiveKit()
        lkTransport.pause()
        assertEquals(listOf(true), probeFlags)
        assertEquals(0, promoteCount)
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // Simulating handoff to offline mesh during fallback, then handback
        arbiter.release(AudioOwner.LIVEKIT)
        assertTrue(arbiter.tryClaimOfflineMesh())
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        arbiter.release(AudioOwner.OFFLINE_MESH)
        assertTrue(arbiter.claimLiveKit())
        lkTransport.resume()
        assertEquals(listOf(true, false), probeFlags)
        assertEquals(1, promoteCount)
        lkTransport.close()
    }

    @Test
    fun lobbyViewModel_buildConvoyMembershipSnapshot_separatesMeshAndNonMeshParticipants() {
        val api = mockk<ApiService>(relaxed = true)
        val repo = ActiveRideRepository(prefs = null, api = api)
        val sessionHolder = OfflineSessionHolder()
        val vm = LobbyViewModel(api, repo, sessionHolder)

        val sid = UUID.randomUUID().toString()
        val status = LobbyStatus(
            roomId = sid,
            convoyName = "RidgeRunners",
            invites = listOf(
                LobbyInviteEntry("inv1", "ACCEPTED", LobbyRiderInfo("u-ready", "r2", "Rider 2", null), offlineConfigStatus = "READY"),
                LobbyInviteEntry("inv2", "ACCEPTED", LobbyRiderInfo("u-opted", "r3", "Rider 3", null), offlineConfigStatus = "OPTED_OUT"),
                LobbyInviteEntry("inv3", "PENDING", LobbyRiderInfo("u-pend", "r4", "Rider 4", null), offlineConfigStatus = "READY"),
                LobbyInviteEntry("inv4", "ACCEPTED", LobbyRiderInfo("u-unavail", "r5", "Rider 5", null), offlineConfigStatus = "UNAVAILABLE_DEVICE")
            ),
            isHost = true,
            host = LobbyHost(id = "u-host", displayName = "Host"),
            offlineMeshEnabled = true
        )

        val snap = vm.buildConvoyMembershipSnapshot(status, localUserId = "u-host", localOptOut = false)
        assertNotNull(snap)
        assertEquals(listOf("u-host", "u-ready"), snap!!.meshParticipants)
        assertEquals(4, snap.members.size) // PENDING invitee excluded
        assertTrue(snap.isGroupFallbackEligible)
        @Suppress("UNUSED_VARIABLE") val s = SavedStateHandle()
    }

    @Test
    fun encryptedConvoyStateStore_restoresMembershipSnapshotUsingStableRoomUuidAfterProcessRestart() {
        val backend = InMemoryConvoyKeyValueBackend()
        val store = EncryptedConvoyStateStore(backend)
        val roomId = "room-non-uuid-restart-test"
        val sid = ConvoyHash.stableSessionUuid(roomId)

        val snap = ConvoyMembershipSnapshot.create(
            sid,
            listOf(
                ConvoyMember("u-1", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-2", MemberRole.MESH_PARTICIPANT)
            )
        )
        assertTrue(store.saveMembership(snap))

        // Simulate cold restart where ActiveRideRepository has roomId but null in-memory snapshot
        val freshStore = EncryptedConvoyStateStore(backend)
        val restored = freshStore.load(ConvoyHash.stableSessionUuid(roomId))
        assertNotNull(restored)
        assertEquals(snap.meshParticipants, restored!!.membership?.meshParticipants)
        assertArrayEquals(snap.rosterHash, restored.membership?.rosterHash)
    }
}
