package com.ridervoice.mesh.audit

import com.ridervoice.mesh.audio.AudioTrackSink
import com.ridervoice.mesh.audio.FakeAudioManagerPort
import com.ridervoice.mesh.audio.HardwarePttPort
import com.ridervoice.mesh.audio.MeshAudioRouteController
import com.ridervoice.mesh.audio.MeshVoxBridge
import com.ridervoice.mesh.audio.MultiSourceJitterBufferManager
import com.ridervoice.mesh.audio.OfflineAudioPlayback
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.BackupSessionStore
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.session.SessionManager
import com.ridervoice.mesh.session.StoredBackupMeta
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
import com.ridervoice.mesh.transport.LiveKitPort
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.MeshPeerProjector
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OnlineParticipant
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import com.ridervoice.services.MeshForegroundService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MeshSubsystemInvariantsTest {

    @Test
    fun onlinePeer_hasHopCountZero_andIsNotDirect() {
        val peer = UnifiedVoicePeer.fromOnline(
            OnlineParticipant(
                identity = "rider-1",
                displayName = "Alpha",
                isSpeaking = true,
                isConnected = true
            )
        )
        assertEquals(0, peer.hopCount)
        assertFalse("Online peer must not report isDirect mesh hop", peer.isDirect)
        assertFalse(peer.isRelayed)
        assertTrue(peer.isSpeaking)
    }

    @Test
    fun liveKitTransport_doesNotUnmuteOnlineRiderOnReconnectWhenNotPaused() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val mutedSetCalls = mutableListOf<Boolean>()
        val connState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val userMuted = MutableStateFlow(false)

        val port = object : LiveKitPort {
            override val connectionState: StateFlow<ConnectionState> = connState
            override val activeSpeaker: StateFlow<String?> = MutableStateFlow(null)
            override val participants: StateFlow<List<Participant>> = MutableStateFlow(emptyList())
            override val isUserMuted: StateFlow<Boolean> = userMuted
            override val deafened: StateFlow<Boolean> = MutableStateFlow(false)
            override val isVoxRunning: Boolean = false
            override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) {}
            override fun disconnect() {}
            override fun setUserMuted(muted: Boolean) {
                mutedSetCalls += muted
                userMuted.value = muted
            }
            override fun setDeafened(deafened: Boolean) {}
            override fun onVoiceGate(open: Boolean) {}
        }

        val transport = LiveKitVoiceTransport(
            liveKitPort = port,
            audioOwnershipArbiter = AudioOwnershipArbiter(),
            dispatcher = testDispatcher,
            gateAllowed = { true }
        )
        testScheduler.runCurrent()

        connState.value = ConnectionState.CONNECTING
        testScheduler.runCurrent()
        connState.value = ConnectionState.CONNECTED
        testScheduler.runCurrent()

        assertTrue("Online connect when not paused by coordinator must not force setUserMuted", mutedSetCalls.isEmpty())
        transport.close()
    }

    @Test
    fun liveKitTransport_syncsOnlineUserMuteState_andPreservesExplicitMicOverrideDuringPause() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val connState = MutableStateFlow(ConnectionState.CONNECTED)
        val userMuted = MutableStateFlow(true)

        val port = object : LiveKitPort {
            override val connectionState: StateFlow<ConnectionState> = connState
            override val activeSpeaker: StateFlow<String?> = MutableStateFlow(null)
            override val participants: StateFlow<List<Participant>> = MutableStateFlow(emptyList())
            override val isUserMuted: StateFlow<Boolean> = userMuted
            override val deafened: StateFlow<Boolean> = MutableStateFlow(false)
            override val isVoxRunning: Boolean = false
            override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) {}
            override fun disconnect() {}
            override fun setUserMuted(muted: Boolean) {
                userMuted.value = muted
            }
            override fun setDeafened(deafened: Boolean) {}
            override fun onVoiceGate(open: Boolean) {}
        }

        val transport = LiveKitVoiceTransport(
            liveKitPort = port,
            audioOwnershipArbiter = AudioOwnershipArbiter(),
            dispatcher = testDispatcher,
            gateAllowed = { true }
        )
        testScheduler.runCurrent()

        // Online rider un-mutes via RoomScreen
        userMuted.value = false
        testScheduler.runCurrent()
        assertEquals(MicState.ON, transport.micState.value)

        // Pause by coordinator, then explicitly set mic override while paused
        transport.pause()
        transport.setMicrophoneEnabled(false)
        transport.resume()
        assertTrue("Explicit mic override while paused must be respected on resume", userMuted.value)
        transport.close()
    }

    @Test
    fun backupSession_persistsAndRestoresFromBackupSessionStore_andExpiresCleanly() {
        var savedMeta: StoredBackupMeta? = null
        val fakeStore = object : BackupSessionStore {
            override fun saveBackup(pairingCode: String, createdAtMs: Long, expiresAtMs: Long) {
                savedMeta = StoredBackupMeta(pairingCode, createdAtMs, expiresAtMs)
            }
            override fun loadBackup(): StoredBackupMeta? = savedMeta
            override fun clearBackup() {
                savedMeta = null
            }
        }

        val now = 1_000_000L
        val session = OfflineSession.create(now)
        val holder1 = OfflineSessionHolder(backupStore = fakeStore, clock = { now })
        holder1.setBackup(session)
        assertNotNull("Backup metadata must be saved to store", savedMeta)

        // Simulate app restart with unexpired backup
        val holder2 = OfflineSessionHolder(backupStore = fakeStore, clock = { now + 60_000L })
        holder2.restoreFromStoreIfValid()
        val restored = holder2.getBackup()
        assertNotNull("Unexpired backup session must be restored", restored)
        assertEquals(session.sessionId, restored?.sessionId)

        // Simulate app restart after expiry
        val holder3 = OfflineSessionHolder(backupStore = fakeStore, clock = { session.expiresAt + 1_000L })
        holder3.restoreFromStoreIfValid()
        assertNull("Expired backup session must not be restored", holder3.getBackup())
        assertNull("Expired backup metadata must be cleared from store", savedMeta)
    }

    @Test
    fun sessionManager_canAdmitAll_rejectsWhenAdmittingSourceAndPreviousHopExceedsCap() {
        val now = 1000L
        val session = OfflineSession.create(now)
        val selfId = UUID.randomUUID().toString()
        val manager = SessionManager(session = session, selfId = selfId, clock = { now })

        // Admit 4 peers (total = 1 self + 4 peers = 5 devices)
        repeat(4) {
            val res = manager.admit(UUID.randomUUID().toString())
            assertEquals(SessionManager.Admission.ADMITTED, res)
        }
        assertEquals(5, manager.members().size)

        // Now a forwarded packet arrives with BOTH a new sourceId and a new previousHopId (would require 5 + 2 = 7 > 6)
        val newSource = UUID.randomUUID().toString()
        val newRelay = UUID.randomUUID().toString()
        val jointRes = manager.canAdmitAll(listOf(newSource, newRelay))
        assertEquals(SessionManager.Admission.REJECTED_FULL, jointRes)
        assertEquals("Neither peer may be partially admitted when joint admission fails", 5, manager.members().size)
    }

    @Test
    fun meshAudioRouteController_doesNotReleaseBluetoothScoWhenLiveKitOwnsAudio() = runTest {
        val fakePort = FakeAudioManagerPort()
        fakePort.isBluetoothScoAvailableOffCall = true
        val arbiter = AudioOwnershipArbiter()
        val controller = MeshAudioRouteController(port = fakePort, arbiter = arbiter)

        arbiter.tryClaimOfflineMesh()
        controller.acquireCommunicationMode()
        assertTrue(fakePort.isBluetoothScoOn)

        // Handback claims LiveKit before releaseCommunicationMode is called
        arbiter.claimLiveKit()
        controller.releaseCommunicationMode()

        assertTrue("Bluetooth SCO must remain active when LiveKit already owns audio", fakePort.isBluetoothScoOn)
    }

    @Test
    fun meshVoxBridge_doesNotDeactivateMediaSessionWhenRoomOwnsAudio() = runTest {
        var active = true
        var deactivateCount = 0
        val port = object : HardwarePttPort {
            override var onMicToggleRequest: ((Boolean) -> Unit)? = null
            override val isSessionActive: Boolean get() = active
            override fun activateSession() { active = true }
            override fun deactivateSession() {
                active = false
                deactivateCount++
            }
        }
        val dummyTransport = object : VoiceTransport {
            override val state = MutableStateFlow(VoiceTransportState.STOPPED)
            override val participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
            override val activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
            override val audioOwner = MutableStateFlow(AudioOwner.NONE)
            override val micState = MutableStateFlow(MicState.OFF)
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {}
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: com.ridervoice.mesh.transport.AudioRoute) {}
            override suspend fun pause() {}
            override suspend fun resume() {}
        }

        val bridge = MeshVoxBridge(transport = dummyTransport, hardwarePttPort = port)
        bridge.attach(roomOwnsAudio = true)
        bridge.detach()

        assertEquals("MeshVoxBridge must not deactivate MediaSession when online room owns it", 0, deactivateCount)
        assertTrue(active)
        bridge.close()
    }

    @Test
    fun offlineAudioPlayback_monotonicTickPacing_doesNotCountIdleSilenceAsUnderrun() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var writes = 0
        val sink = object : AudioTrackSink {
            override val state: Int = android.media.AudioTrack.STATE_INITIALIZED
            override fun play() {}
            override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                writes++
                return sizeInBytes
            }
            override fun stop() {}
            override fun release() {}
        }

        val jitterManager = MultiSourceJitterBufferManager(clock = { testScheduler.currentTime })
        val playback = OfflineAudioPlayback(
            jitterManager = jitterManager,
            gateAllowed = { true },
            dispatcher = testDispatcher,
            audioTrackFactory = { sink },
            nanoClock = { (testScheduler.currentTime + 1_000L) * 1_000_000L }
        )

        assertTrue(playback.start())
        testScheduler.advanceTimeBy(100L)
        playback.stop()

        assertTrue("Playback must have written frames", writes > 0)
        assertEquals("Idle silence frames must not increment underrunCount", 0L, playback.underrunCount)
    }

    @Test
    fun meshGraphRepository_onHello_refreshesTimestampsEvenWhenTopologyVersionUnchanged() {
        var now = 10_000L
        val selfId = UUID.randomUUID().toString()
        val peerB = UUID.randomUUID().toString()
        val repo = MeshGraphRepository(selfId = selfId, selfName = "Alpha", clock = { now })

        val changed1 = repo.onHello(
            sourceId = peerB,
            name = "Bravo",
            hopCount = 0,
            endpoint = MeshEndpoint("192.168.43.2", 45454),
            neighborIds = listOf(selfId),
            sequence = 1,
            epoch = 1
        )
        assertTrue(changed1)
        val v1 = repo.snapshots.value.version
        val firstSeen = repo.snapshots.value.edges.first().lastSeenAt

        now = 12_500L
        val changed2 = repo.onHello(
            sourceId = peerB,
            name = "Bravo",
            hopCount = 0,
            endpoint = MeshEndpoint("192.168.43.2", 45454),
            neighborIds = listOf(selfId),
            sequence = 2,
            epoch = 1
        )
        assertFalse("Steady-state HELLO with identical neighbor set must not bump topology version", changed2)
        assertEquals(v1, repo.snapshots.value.version)
        val updatedSeen = repo.snapshots.value.edges.first().lastSeenAt
        assertEquals("Snapshot edge lastSeenAt must still refresh on steady-state HELLO", 12_500L, updatedSeen)
        assertTrue(updatedSeen > firstSeen)
    }

    @Test
    fun meshPeerProjector_hasValidRoute_checksExpiresAt_andProjectsDiagnostics() {
        val now = 50_000L
        val selfId = UUID.randomUUID().toString()
        val peerB = UUID.randomUUID().toString()
        val repo = MeshGraphRepository(selfId = selfId, selfName = "Alpha", clock = { now })
        repo.onHello(
            sourceId = peerB,
            name = "Bravo",
            hopCount = 0,
            endpoint = MeshEndpoint("192.168.43.2", 45454),
            neighborIds = listOf(selfId),
            sequence = 1,
            epoch = 1
        )

        val session = OfflineSession.create(now)
        val sManager = SessionManager(session = session, selfId = selfId, clock = { now })
        sManager.admit(peerB)

        val rTable = RoutingTable()
        rTable.recompute(repo.snapshots.value, now)

        val fakeLink = object : MeshLink {
            override val linkId: String = "fake"
            override val state: StateFlow<MeshLinkState> = MutableStateFlow(MeshLinkState.RUNNING)
            override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
            override fun endpoints(): Set<MeshEndpoint> = emptySet()
        }

        val fEngine = ForwardingEngine(
            selfId = selfId,
            selfName = "Alpha",
            sessionManager = sManager,
            graphRepository = repo,
            routingTable = rTable,
            duplicateCache = DuplicatePacketCache(clock = { now }),
            ttlPolicy = TtlPolicy(),
            link = fakeLink,
            clock = { now }
        )

        val diagRepo = MeshDiagnosticsRepository()
        val projector = MeshPeerProjector(
            identity = MeshIdentity(selfId, "Alpha"),
            diagnosticsRepo = diagRepo,
            clock = { now }
        )

        val resValid = projector.project(
            snapshot = repo.snapshots.value,
            sessionManager = sManager,
            routingTable = rTable,
            forwardingEngine = fEngine,
            speakers = emptySet(),
            linkState = MeshLinkState.RUNNING,
            localIp = "192.168.43.1",
            broadcastAddress = "192.168.43.255",
            authenticatedNeighborsLast10s = 1,
            secondsWithZeroNeighbours = 0L
        )
        assertNotNull(resValid)
        assertTrue(resValid!!.hasValidRoute)
        assertTrue(diagRepo.snapshot.value.linkState.isNotBlank())
        assertEquals("192.168.43.1", diagRepo.snapshot.value.localIp)
    }

    @Test
    fun voiceSessionCoordinator_onOnlineRideEnded_stopsMeshAndReleasesOwnership() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val arbiter = AudioOwnershipArbiter()
        arbiter.tryClaimOfflineMesh()

        var meshStopped = false
        val fakeMesh = object : VoiceTransport, MeshControl {
            override val state = MutableStateFlow(VoiceTransportState.CONNECTED)
            override val participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
            override val activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
            override val audioOwner = arbiter.owner
            override val micState = MutableStateFlow(MicState.OFF)
            override val hasValidRoute = MutableStateFlow(true)
            override suspend fun startDiscoveryOnly(session: OfflineSession) {}
            override suspend fun enableAudio(): EnableAudioResult = EnableAudioResult.Success
            override suspend fun disableAudio() {}
            override suspend fun pauseCaptureOnly() {}
            override suspend fun resumeCaptureOnly() {}
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {
                meshStopped = true
                state.value = VoiceTransportState.STOPPED
            }
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: com.ridervoice.mesh.transport.AudioRoute) {}
            override suspend fun pause() {}
            override suspend fun resume() {}
        }

        val fakeLk = object : VoiceTransport {
            override val state = MutableStateFlow(VoiceTransportState.STOPPED)
            override val participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
            override val activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
            override val audioOwner = arbiter.owner
            override val micState = MutableStateFlow(MicState.OFF)
            override suspend fun start(session: VoiceSession) {}
            override suspend fun stop() {}
            override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
            override suspend fun setDeafened(deafened: Boolean) {}
            override suspend fun setAudioRoute(route: com.ridervoice.mesh.transport.AudioRoute) {}
            override suspend fun pause() {}
            override suspend fun resume() {}
        }

        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = fakeLk,
            meshTransport = fakeMesh,
            audioOwnershipArbiter = arbiter,
            dispatcher = testDispatcher
        )

        coordinator.onOnlineRideEnded(keepStandaloneMesh = false)
        assertTrue("Ending online ride without standalone mesh must stop mesh transport", meshStopped)
        assertEquals(AudioOwner.NONE, arbiter.owner.value)
        assertEquals(PeerTransportKind.ONLINE_LIVEKIT, coordinator.activeKind.value)
    }

    @Test
    fun meshForegroundService_actionDisconnect_returnsStopDecision() {
        val decision = MeshForegroundService.decideForeground(MeshForegroundService.ACTION_DISCONNECT)
        assertEquals(MeshForegroundService.FgsDecision.Stop, decision)
    }
}
