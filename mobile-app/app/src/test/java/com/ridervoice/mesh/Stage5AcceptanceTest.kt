package com.ridervoice.mesh

import com.ridervoice.mesh.continuity.ContinuityConfig
import com.ridervoice.mesh.continuity.ContinuityLeaseRequest
import com.ridervoice.mesh.continuity.ContinuityLeaseResponse
import com.ridervoice.mesh.continuity.ContinuityLeaseResult
import com.ridervoice.mesh.continuity.DefaultContinuityLeaseClient
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import com.ridervoice.mesh.switching.AntiFlappingConfig
import com.ridervoice.mesh.switching.AutoSwitchState
import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
import com.ridervoice.mesh.switching.LocalHealthSignals
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 5 Acceptance Test Suite:
 * - Daisy-Hop multi-node audio relaying and source preservation acceptance
 * - Online LiveKit integration & Audio HAL mutual exclusion acceptance
 * - Automatic failure detection & fallback acceptance
 * - Automatic recovery & stability window acceptance
 * - Rollback drill & feature-gating acceptance
 * - Stage 4 Backend continuity lease isolation acceptance
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Stage5AcceptanceTest {

    // ========================================================================
    // 5.1 Daisy-Hop Acceptance Test (6-node chain: A -> B -> C -> D -> E -> F)
    // ========================================================================

    @Test
    fun `daisyHopAcceptance_multiNodeAudioFlowAndSourceIntegrity`() {
        var clockTime = 500_000L
        val session = OfflineSession.create(clockTime)

        class AcceptanceNode(
            val tag: String,
            val id: String,
            val endpoint: MeshEndpoint,
            val allNodesProvider: () -> List<AcceptanceNode>,
            val canCommunicatePredicate: (String, String) -> Boolean
        ) {
            val receivedAudio = ArrayList<MeshPacket>()
            val sessionManager = SessionManager(session, id) { clockTime }
            val graph = MeshGraphRepository(id, tag) { clockTime }
            val routes = RoutingTable()
            val dupCache = DuplicatePacketCache { clockTime }
            val ttl = TtlPolicy { clockTime }

            val link: MeshLink = object : MeshLink {
                override val linkId = "link-$tag"
                override val state = MutableStateFlow(MeshLinkState.RUNNING)
                override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
                override suspend fun start() {}
                override suspend fun stop() {}
                override fun endpoints(): Set<MeshEndpoint> = emptySet()
                override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
                    val peer = allNodesProvider().firstOrNull { it.endpoint == endpoint } ?: return false
                    if (!canCommunicatePredicate(id, peer.id)) return false
                    peer.engine.onIncomingFrame(MeshFrame(this@AcceptanceNode.endpoint, payload.copyOf()))
                    return true
                }
            }

            val engine: ForwardingEngine = ForwardingEngine(
                selfId = id,
                selfName = tag,
                sessionManager = sessionManager,
                graphRepository = graph,
                routingTable = routes,
                duplicateCache = dupCache,
                ttlPolicy = ttl,
                link = link,
                clock = { clockTime },
                onLocalVoicePacket = { receivedAudio.add(it) }
            )
        }

        val allNodes = ArrayList<AcceptanceNode>()
        val physicalLinks = HashSet<Pair<String, String>>()

        fun canCommunicate(a: String, b: String): Boolean = (a to b) in physicalLinks

        val names = listOf("A", "B", "C", "D", "E", "F")
        for ((idx, name) in names.withIndex()) {
            val uuid = UUID.nameUUIDFromBytes("node-$name".toByteArray()).toString()
            val ep = MeshEndpoint("10.1.1.${idx + 1}", MeshConfig.MESH_PORT)
            allNodes.add(
                AcceptanceNode(
                    tag = name,
                    id = uuid,
                    endpoint = ep,
                    allNodesProvider = { allNodes },
                    canCommunicatePredicate = { a, b -> canCommunicate(a, b) }
                )
            )
        }

        // Linear physical daisy chain: A <-> B <-> C <-> D <-> E <-> F
        for (i in 0 until allNodes.size - 1) {
            val id1 = allNodes[i].id
            val id2 = allNodes[i + 1].id
            physicalLinks.add(id1 to id2)
            physicalLinks.add(id2 to id1)
        }

        // Converge discovery over 8 rounds
        val bcast = MeshEndpoint("255.255.255.255", MeshConfig.MESH_PORT)
        repeat(8) {
            clockTime += 500L
            for (node in allNodes) {
                // simulate direct neighbor hello broadcast
                val directPeers = allNodes.filter { canCommunicate(node.id, it.id) }
                for (peer in directPeers) {
                    node.engine.originateHello(listOf(peer.endpoint))
                }
            }
        }

        // Node A speaks
        val audioBytes = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x42 }
        val sentPacket = allNodes[0].engine.originateVoice(audioBytes)
        assertNotNull(sentPacket)

        // Verify B, C, D, E, F all received and played the audio
        for (i in 1 until allNodes.size) {
            val peer = allNodes[i]
            assertEquals("Peer ${peer.tag} must receive 1 audio frame", 1, peer.receivedAudio.size)
            val rx = peer.receivedAudio.single()
            assertEquals("Original source ID must be A", allNodes[0].id, rx.sourceId)
            assertEquals("Hop count must match distance", i - 1, rx.hopCount)
            assertEquals("TTL must decrement by hop", MeshConfig.DEFAULT_TTL - (i - 1), rx.ttl)
            assertArrayEquals("Payload must match original", audioBytes, rx.payload)
        }

        // Verify duplicate suppression: re-sending the same wire bytes to B is discarded
        val wireFromA = MeshPacketCodec.encode(sentPacket!!, session.authKey())
        val dupOutcome = allNodes[1].engine.onIncomingFrame(MeshFrame(allNodes[0].endpoint, wireFromA))
        assertEquals(ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DUPLICATE), dupOutcome)
    }

    // ========================================================================
    // 5.2 Online Integration & Audio Hardware Mutual Exclusion Acceptance
    // ========================================================================

    @Test
    fun `onlineIntegrationAcceptance_liveKitAndMeshAreStrictlyIsolated`() = runTest {
        val arbiter = AudioOwnershipArbiter()
        assertEquals(AudioOwner.NONE, arbiter.owner.value)

        // Online session claims audio ownership
        assertTrue(arbiter.claimLiveKit())
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // Offline mesh cannot seize audio ownership while LiveKit owns it
        assertFalse(arbiter.tryClaimOfflineMesh())
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)

        // LiveKit hands off to offline mesh
        var liveKitSuspended = false
        assertTrue(arbiter.handoffLiveKitToOfflineMesh { liveKitSuspended = true })
        assertTrue(liveKitSuspended)
        assertEquals(AudioOwner.OFFLINE_MESH, arbiter.owner.value)

        // LiveKit reclaiming requires offline capture to stop first
        var offlineStopped = false
        var liveKitResumed = false
        assertTrue(
            arbiter.handoffOfflineMeshToLiveKit(
                onStopOfflineCapture = { offlineStopped = true },
                onResumeLiveKitCapture = { liveKitResumed = true }
            )
        )
        assertTrue(offlineStopped)
        assertTrue(liveKitResumed)
        assertEquals(AudioOwner.LIVEKIT, arbiter.owner.value)
    }

    // ========================================================================
    // 5.3 Automatic Fallback Acceptance
    // ========================================================================

    @Test
    fun `automaticFallbackAcceptance_failureDelayAndMeshRouteRequired`() = runTest {
        var now = 100_000L
        val config = AntiFlappingConfig(failureConfirmationMs = 10_000L)
        val stateMachine = AutomaticSwitchStateMachine(
            config = config,
            isGateAllowed = { true },
            clock = { now }
        )

        val failingWithMesh = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = true,
            liveKitReconnectAttempts = 2,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = true
        )

        val failingNoMesh = failingWithMesh.copy(hasValidMeshRoute = false)

        // Failure starts: enters SUSPECT
        stateMachine.evaluate(failingWithMesh)
        assertEquals(AutoSwitchState.ONLINE_SUSPECT, stateMachine.state.value)

        // Transient glitch recovering within 5s does not fall back
        now += 5_000L
        stateMachine.evaluate(failingWithMesh.copy(isValidatedInternet = true, isLiveKitConnected = true, isHealthProbeSuccessful = true))
        assertEquals(AutoSwitchState.ONLINE, stateMachine.state.value)

        // Sustained failure without mesh route -> FALLBACK_UNAVAILABLE
        stateMachine.evaluate(failingNoMesh)
        now += 10_001L
        stateMachine.evaluate(failingNoMesh)
        assertEquals(AutoSwitchState.FALLBACK_UNAVAILABLE, stateMachine.state.value)

        // When mesh route becomes available -> OFFLINE_ACTIVE
        stateMachine.evaluate(failingWithMesh)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, stateMachine.state.value)
    }

    // ========================================================================
    // 5.4 Automatic Recovery Acceptance
    // ========================================================================

    @Test
    fun `automaticRecoveryAcceptance_stabilityWindowAndDwellTimeRespected`() = runTest {
        var now = 200_000L
        val config = AntiFlappingConfig(
            failureConfirmationMs = 10_000L,
            recoveryStabilityMs = 8_000L,
            minDwellMs = 20_000L
        )

        var offlineStopped = false
        var onlineResumed = false

        val stateMachine = AutomaticSwitchStateMachine(
            config = config,
            isGateAllowed = { true },
            clock = { now },
            onHandoffToOffline = { true },
            onHandoffToOnline = {
                offlineStopped = true
                onlineResumed = true
                true
            }
        )

        val healthy = LocalHealthSignals(
            isValidatedInternet = true,
            isLiveKitConnected = true,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = true,
            hasValidMeshRoute = true
        )

        val failing = healthy.copy(isValidatedInternet = false, isLiveKitConnected = false, isHealthProbeSuccessful = false)

        // Trigger switch to offline
        stateMachine.evaluate(failing)
        now += 10_001L
        stateMachine.evaluate(failing)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, stateMachine.state.value)

        // Early recovery attempt within 20s dwell window is ignored
        now += 15_000L
        stateMachine.evaluate(healthy)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, stateMachine.state.value)

        // Past dwell window -> enters RECOVERING
        now += 5_001L
        stateMachine.evaluate(healthy)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, stateMachine.state.value)

        // Instability during 8s recovery window aborts back to OFFLINE_ACTIVE
        now += 4_000L
        stateMachine.evaluate(failing)
        assertEquals(AutoSwitchState.OFFLINE_ACTIVE, stateMachine.state.value)

        // Re-enter recovery and satisfy full 8s -> returns to ONLINE
        now += 21_000L
        stateMachine.evaluate(healthy)
        assertEquals(AutoSwitchState.ONLINE_RECOVERING, stateMachine.state.value)

        now += 8_001L
        stateMachine.evaluate(healthy)
        assertEquals(AutoSwitchState.ONLINE, stateMachine.state.value)
        assertTrue(offlineStopped)
        assertTrue(onlineResumed)
    }

    // ========================================================================
    // 5.5 Rollback Acceptance
    // ========================================================================

    @Test
    fun `rollbackAcceptance_flagDisablingImmediatelyHaltsMesh`() {
        // Assert per-build-type value (debug true in test runner, release false)
        assertEquals(
            "Build flag must match per-build-type value",
            com.ridervoice.BuildConfig.OFFLINE_MESH_BUILD_ENABLED,
            FeatureFlags.OFFLINE_MESH_ENABLED
        )

        // Local emergency kill switch forces flag to false even in debug builds
        try {
            FeatureFlags.localKillSwitch = true
            assertFalse("Local emergency kill switch must disable OFFLINE_MESH_ENABLED", FeatureFlags.OFFLINE_MESH_ENABLED)
            assertFalse(OfflineMeshGate.isBuildGateOpen())
            assertFalse(OfflineMeshGate.isOfflineAllowed())
        } finally {
            FeatureFlags.localKillSwitch = false
            FeatureFlags.localOverride = null
        }

        // Offline mesh gate must refuse any execution when flag is false
        assertFalse(OfflineMeshGate.isOfflineAllowed(buildFlag = false, offlineMeshEnabled = true))
        assertFalse(OfflineMeshGate.isAutomaticFallbackAllowed(buildFlag = false, offlineMeshEnabled = true, automaticFallbackEnabled = true))
        assertFalse(OfflineMeshGate.isWarmStandbyAllowed(buildFlag = false, offlineMeshEnabled = true, warmStandbyEnabled = true))
    }

    // ========================================================================
    // 5.6 Stage 4 Backend Continuity Acceptance
    // ========================================================================

    @Test
    fun `backendContinuityAcceptance_featureFlaggedAndDefaultOff`() = runTest {
        // Assert default configuration is false
        assertFalse("Backend continuity must be disabled by default", ContinuityConfig.BACKEND_CONTINUITY_ENABLED)

        val clientDefaultOff = DefaultContinuityLeaseClient(
            isFeatureEnabled = { false },
            leaseRequester = { ContinuityLeaseResponse("ride-1", 1000L, true) }
        )

        // When feature is off, request immediately returns FeatureDisabled without network
        val resultOff = clientDefaultOff.requestLease("ride-1", "device-1")
        assertEquals(ContinuityLeaseResult.FeatureDisabled, resultOff)
        assertEquals(null, clientDefaultOff.activeLeaseUntil.value)

        // When feature is explicitly enabled
        val clientOn = DefaultContinuityLeaseClient(
            isFeatureEnabled = { true },
            leaseRequester = { ContinuityLeaseResponse("ride-1", 999999L, true) },
            leaseReleaser = { _, _ -> true }
        )

        val resultOn = clientOn.requestLease("ride-1", "device-1")
        assertTrue(resultOn is ContinuityLeaseResult.Granted)
        assertEquals(999999L, (resultOn as ContinuityLeaseResult.Granted).leaseUntilEpochSec)
        assertEquals(999999L, clientOn.activeLeaseUntil.value)

        // Release lease
        assertTrue(clientOn.releaseLease("ride-1", "device-1"))
        assertEquals(null, clientOn.activeLeaseUntil.value)
    }
}
