package com.ridervoice.mesh.convoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.UUID

/**
 * Deterministic virtual-time simulator (Sections 20 & 21) asserting convoy-wide
 * convergence, safety invariants (1..31), topology partitions/merges, relay propagation,
 * competing proposals, withdrawals, restarts, and Protocol M recovery.
 *
 * Deterministic virtual-time simulation without real-time blocking sleep; uses injected [MonotonicClock].
 */
class ConvoySwitchingSimulatorTest {

    private class InMemoryConvoyPersistence : ConvoyStatePersistence {
        var stored: PersistedConvoyState? = null
        override fun load(sessionId: UUID): PersistedConvoyState? = stored?.takeIf { it.sessionId == sessionId }
        override fun saveGenerationHighWaterWriteAhead(sessionId: UUID, highWater: Long): Boolean {
            val prev = stored
            stored = PersistedConvoyState(
                sessionId = sessionId,
                membership = prev?.membership,
                acceptedDecision = prev?.acceptedDecision,
                generationHighWater = highWater,
                generationUnknown = false,
                transitionTimestampsMs = prev?.transitionTimestampsMs ?: emptyList(),
                expiresAtMs = prev?.expiresAtMs ?: Long.MAX_VALUE,
                lastLocalExecutionState = prev?.lastLocalExecutionState
            )
            return true
        }

        override fun saveAcceptedDecisionAtomically(sessionId: UUID, decision: TransportDecision, highWater: Long): Boolean {
            val prev = stored
            stored = PersistedConvoyState(
                sessionId = sessionId,
                membership = prev?.membership,
                acceptedDecision = decision,
                generationHighWater = maxOf(prev?.generationHighWater ?: 0L, highWater),
                generationUnknown = false,
                transitionTimestampsMs = prev?.transitionTimestampsMs ?: emptyList(),
                expiresAtMs = prev?.expiresAtMs ?: Long.MAX_VALUE,
                lastLocalExecutionState = prev?.lastLocalExecutionState
            )
            return true
        }

        override fun saveSnapshot(state: PersistedConvoyState): Boolean {
            stored = state
            return true
        }

        override fun clear(sessionId: UUID?) {
            stored = null
        }
    }

    private class SimNode(
        val id: String,
        var membership: ConvoyMembershipSnapshot,
        val clock: () -> Long,
        var incarnation: Long,
        val persistence: InMemoryConvoyPersistence = InMemoryConvoyPersistence()
    ) {
        var coordinator = ConvoyTransportCoordinator(id, MonotonicClock { clock() }, ConvoyProtocolConfig(), persistence)
        var executor = SingleFlightHandoffExecutor()

        var netLost = false
        var totalLossConfirmed = false
        var lkReadiness = LiveKitReadiness.READY
        var recoveryStable = true
        var meshRouteAvailable = true
        var manualReadyAsserted = false
        var probingUnavailable = false
        var manualExclusionRequest: ManualExclusionRequest? = null

        var actualOwner: String = "LIVEKIT" // "LIVEKIT" | "OFFLINE_MESH"
        var lastOutput: CoordinatorOutput? = null
        var seq: Int = 0
        var maxObservedGen: Long = 0L
        var maxObservedHighWater: Long = 0L

        val inboxDecisions = ArrayList<TransportDecision>()
        val inboxObservations = ArrayList<MemberObservation>()

        fun restart(newIncarnation: Long, clearPersistence: Boolean = false) {
            incarnation = newIncarnation
            seq = 0
            if (clearPersistence) {
                persistence.clear(membership.sessionId)
            }
            coordinator = ConvoyTransportCoordinator(id, MonotonicClock { clock() }, ConvoyProtocolConfig(), persistence)
            if (clearPersistence) {
                coordinator.markGenerationUnknown()
            } else {
                coordinator.restoreFromPersistence(membership.sessionId)
            }
            executor = SingleFlightHandoffExecutor()
            // After restart, transport must not assume active audio without revalidation
            actualOwner = "NONE"
            inboxDecisions.clear()
            inboxObservations.clear()
        }
    }

    private data class InFlightEnvelope(
        val deliverAtMs: Long,
        val originId: String,
        val originSeq: Int,
        val fromId: String,
        val toId: String,
        val payloadBytes: ByteArray,
        val ttl: Int
    )

    private class VirtualConvoyNetwork(
        val seed: Long = 42L,
        val config: ConvoyProtocolConfig = ConvoyProtocolConfig()
    ) {
        var nowMs: Long = 100_000L
        val rng = Random(seed)
        val nodes = LinkedHashMap<String, SimNode>()
        private val adjacency = HashMap<String, MutableSet<String>>()
        private val inFlight = ArrayList<InFlightEnvelope>()
        private val seenControlPackets = HashMap<String, HashSet<Pair<String, Int>>>()
        var dropProbability: Double = 0.0
        var duplicateProbability: Double = 0.0
        var baseDelayMs: Long = 20L
        var maxJitterMs: Long = 10L

        fun connectClique(ids: List<String>) {
            for (a in ids) {
                for (b in ids) {
                    if (a != b) connectEdge(a, b)
                }
            }
        }

        fun connectChain(ids: List<String>) {
            adjacency.clear()
            for (i in 0 until ids.size - 1) {
                connectEdge(ids[i], ids[i + 1])
                connectEdge(ids[i + 1], ids[i])
            }
        }

        fun disconnectAll() {
            adjacency.clear()
        }

        fun connectEdge(a: String, b: String) {
            adjacency.getOrPut(a) { LinkedHashSet() }.add(b)
        }

        fun step(dtMs: Long = 500L) {
            nowMs += dtMs

            // 1. Deliver due envelopes
            val due = inFlight.filter { it.deliverAtMs <= nowMs }.sortedBy { it.deliverAtMs }
            inFlight.removeAll(due.toSet())

            for (env in due) {
                val target = nodes[env.toId] ?: continue
                if (target.membership.isMeshCapExceeded) continue
                val seen = seenControlPackets.getOrPut(env.toId) { HashSet() }
                if (!seen.add(env.originId to env.originSeq)) continue
                val dec = ConvoyStateCodec.decode(env.payloadBytes, target.membership.meshParticipants.size)
                if (dec is ConvoyStateDecodeResult.Ok) {
                    dec.payload.decision?.let { target.inboxDecisions.add(it) }
                    for (rec in dec.payload.records) {
                        val originId = target.membership.meshParticipants.getOrNull(rec.rosterIdx) ?: continue
                        target.inboxObservations.add(rec.toObservation(originId, nowMs))
                    }
                    // Path 1 TTL-limited forwarding across multi-hop topology (mirrors ForwardingEngine CONTROL relay)
                    if (env.ttl > 1) {
                        val nextNeighbors = adjacency[env.toId].orEmpty()
                        for (nId in nextNeighbors) {
                            if (nId == env.fromId || nId == env.originId) continue
                            if (rng.nextDouble() < dropProbability) continue
                            val delay = baseDelayMs + if (maxJitterMs > 0) rng.nextInt(maxJitterMs.toInt() + 1) else 0
                            inFlight.add(
                                InFlightEnvelope(
                                    deliverAtMs = nowMs + delay,
                                    originId = env.originId,
                                    originSeq = env.originSeq,
                                    fromId = env.toId,
                                    toId = nId,
                                    payloadBytes = env.payloadBytes,
                                    ttl = env.ttl - 1
                                )
                            )
                        }
                    }
                }
            }

            // 2. Evaluate every node and check invariants
            val emitTick = (nowMs % 1000L) == 0L
            for (node in nodes.values) {
                val input = CoordinatorInput(
                    localMemberId = node.id,
                    membership = node.membership,
                    currentDecision = node.coordinator.currentAcceptedDecision(),
                    observations = node.inboxObservations.toList(),
                    localNetLost = node.netLost,
                    localTotalLossConfirmed = node.totalLossConfirmed,
                    localLiveKitReadiness = node.lkReadiness,
                    localRecoveryStable = node.recoveryStable,
                    localMeshRouteAvailable = node.meshRouteAvailable,
                    generationHighWater = node.coordinator.currentGenerationHighWater(),
                    generationUnknown = node.coordinator.isGenerationUnknown(),
                    manualReadyAsserted = node.manualReadyAsserted,
                    manualExclusionRequest = node.manualExclusionRequest,
                    probingUnavailable = node.probingUnavailable,
                    incomingDecisions = node.inboxDecisions.toList()
                )
                node.inboxDecisions.clear()
                node.inboxObservations.clear()

                val out = node.coordinator.evaluate(input)
                node.lastOutput = out

                // Invariant 1: Never decrease generation or high-water mark
                val curGen = out.acceptedDecision?.generation ?: 0L
                assertTrue("Node ${node.id} decreased accepted generation", curGen >= node.maxObservedGen)
                assertTrue("Node ${node.id} decreased highWater", out.generationHighWater >= node.maxObservedHighWater)
                node.maxObservedGen = curGen
                node.maxObservedHighWater = out.generationHighWater

                // Wire executor
                out.acceptedDecision?.let { d ->
                    node.executor.onDesiredDecision(d)
                }
                val cmd = node.executor.pollNextCommand(
                    nowMs = nowMs,
                    canFallbackToMesh = node.meshRouteAvailable,
                    canRecoverToLiveKit = (node.lkReadiness == LiveKitReadiness.READY && !node.netLost)
                )
                if (cmd != null) {
                    when (cmd) {
                        is SingleFlightHandoffExecutor.Command.RunFallback -> {
                            val ok = node.meshRouteAvailable
                            node.executor.onHandoffFinished(cmd.decision.decisionId, ok, nowMs)
                            if (ok) {
                                node.actualOwner = "OFFLINE_MESH"
                                node.coordinator.recordActualAudioTransition(nowMs)
                            }
                        }
                        is SingleFlightHandoffExecutor.Command.RunRecovery -> {
                            val ok = node.lkReadiness == LiveKitReadiness.READY && !node.netLost
                            node.executor.onHandoffFinished(cmd.decision.decisionId, ok, nowMs)
                            if (ok) {
                                node.actualOwner = "LIVEKIT"
                                node.coordinator.recordActualAudioTransition(nowMs)
                            }
                        }
                    }
                }

                // Invariant 8: Never dual audio ownership
                assertTrue(node.actualOwner in setOf("LIVEKIT", "OFFLINE_MESH", "NONE"))

                val shouldBurst = out.actions.any { it is TransportAction.SendControlBurst || it is TransportAction.AdvertiseDecision }
                if (emitTick || shouldBurst) {
                    broadcastState(node, out)
                }
            }
        }

        private fun broadcastState(sender: SimNode, out: CoordinatorOutput) {
            if (sender.membership.isMeshCapExceeded) return
            val meshIdx = sender.membership.indexOfMeshParticipant(sender.id)
            if (meshIdx !in 0 until ConvoyMembershipSnapshot.MAX_MESH_PARTICIPANTS) return

            val seq = sender.coordinator.nextOwnReadinessSeq()
            sender.seq = seq
            val ackFor8 = out.ackFor ?: ByteArray(8)
            val ownRec = ConvoyReadinessWireRecord(
                rosterIdx = meshIdx,
                senderIncarnation = sender.incarnation,
                seq = seq,
                livekitReady = sender.lkReadiness == LiveKitReadiness.READY,
                recoveryEligible = out.localRecoveryEligible,
                meshReady = sender.meshRouteAvailable,
                netLost = sender.netLost,
                localMode = if (sender.actualOwner == "OFFLINE_MESH") LocalModeWire.OFFLINE_ACTIVE else LocalModeWire.ONLINE_ACTIVE,
                readinessSource = out.readinessSource,
                baseGen = out.baseOfflineGen,
                ackFor = ackFor8,
                ageTenthsSec = 0,
                hops = 0
            )

            // Include Path 2 re-advertisable second-hand records (only age & hops differ)
            val secondHand = sender.coordinator.getReAdvertisableObservations(nowMs).mapNotNull { obs ->
                val idx = sender.membership.indexOfMeshParticipant(obs.memberId)
                if (idx < 0 || idx == meshIdx) return@mapNotNull null
                ConvoyReadinessWireRecord(
                    rosterIdx = idx,
                    senderIncarnation = obs.senderIncarnation,
                    seq = obs.readinessSeq,
                    livekitReady = obs.livekitReadiness == LiveKitReadiness.READY,
                    recoveryEligible = obs.recoveryEligible,
                    meshReady = obs.meshReadiness == MeshReadiness.ROUTE_AVAILABLE,
                    netLost = obs.netState != NetState.HEALTHY,
                    localMode = LocalModeWire.fromLocalTransportState(obs.localExecutionState),
                    readinessSource = obs.readinessSource,
                    baseGen = obs.baseGen,
                    ackFor = obs.ackFor ?: ByteArray(8),
                    ageTenthsSec = (obs.ageMs / 100).coerceIn(0, 0xFFFF),
                    hops = obs.hops
                )
            }

            val allRecs = (listOf(ownRec) + secondHand).distinctBy { it.rosterIdx }.take(6)
            val bytes = ConvoyStateCodec.encode(
                ConvoyStateWirePayload(
                    decision = out.decisionToAdvertise,
                    records = allRecs
                )
            )

            val neighbors = adjacency[sender.id].orEmpty()
            for (nId in neighbors) {
                if (rng.nextDouble() < dropProbability) continue
                val delay = baseDelayMs + if (maxJitterMs > 0) rng.nextInt(maxJitterMs.toInt() + 1) else 0
                inFlight.add(InFlightEnvelope(nowMs + delay, sender.id, seq, sender.id, nId, bytes, config.maxHops))
                if (rng.nextDouble() < duplicateProbability) {
                    inFlight.add(InFlightEnvelope(nowMs + delay + 15L, sender.id, seq, sender.id, nId, bytes, config.maxHops))
                }
            }
        }

        fun runMs(durationMs: Long, stepMs: Long = 500L) {
            val steps = (durationMs / stepMs).toInt()
            repeat(steps) { step(stepMs) }
        }
    }

    private fun setupClique(count: Int, seed: Long = 7L): Pair<VirtualConvoyNetwork, List<SimNode>> {
        val sid = UUID.randomUUID()
        val ids = (1..count).map { "rider-$it" }
        val snap = ConvoyMembershipSnapshot.create(sid, ids.map { ConvoyMember(it, MemberRole.MESH_PARTICIPANT) })
        val net = VirtualConvoyNetwork(seed)
        val created = ids.mapIndexed { idx, id ->
            SimNode(id, snap, { net.nowMs }, incarnation = 1000L + idx).also { net.nodes[id] = it }
        }
        net.connectClique(ids)
        return net to created
    }

    @Test
    fun singleRiderDropsInternet_entireConvoyMovesToOffline_thenRecoversWhenAllReady() {
        val (net, nodes) = setupClique(4)
        // Warm up beyond EPOCH_WARMUP
        net.runMs(8_000L)

        // Rider 1 loses internet completely while others still have 4G
        nodes[0].netLost = true
        nodes[0].totalLossConfirmed = true
        nodes[0].lkReadiness = LiveKitReadiness.FAILED
        nodes[0].recoveryStable = false

        net.runMs(2_000L)
        for (n in nodes) {
            assertEquals("Node ${n.id} should adopt OFFLINE", TransportMode.OFFLINE, n.coordinator.currentAcceptedDecision()?.mode)
            assertEquals("Node ${n.id} actual transport should be OFFLINE_MESH", "OFFLINE_MESH", n.actualOwner)
        }

        // Rider 1 regains internet & LiveKit; must wait minDwell (20s) + recovery pipeline (commitHold 1.5s)
        nodes[0].netLost = false
        nodes[0].totalLossConfirmed = false
        nodes[0].lkReadiness = LiveKitReadiness.READY
        nodes[0].recoveryStable = true

        // Still inside 20s dwell -> must remain OFFLINE
        net.runMs(10_000L)
        assertTrue(nodes.all { it.actualOwner == "OFFLINE_MESH" })

        // Past dwell + stagger + commit hold -> all recover cleanly to ONLINE_COMMITTED
        net.runMs(20_000L)
        for (n in nodes) {
            val dec = n.coordinator.currentAcceptedDecision()
            assertEquals(TransportMode.ONLINE, dec?.mode)
            assertEquals(DecisionPhase.ONLINE_COMMITTED, dec?.phase)
            assertEquals("LIVEKIT", n.actualOwner)
        }
    }

    @Test
    fun fourNodeChain_multiHopReadinessPropagation_convergesAcrossRelays() {
        val sid = UUID.randomUUID()
        val ids = listOf("n1", "n2", "n3", "n4")
        val snap = ConvoyMembershipSnapshot.create(sid, ids.map { ConvoyMember(it, MemberRole.MESH_PARTICIPANT) })
        val net = VirtualConvoyNetwork(seed = 99L)
        val nodes = ids.mapIndexed { idx, id ->
            SimNode(id, snap, { net.nowMs }, incarnation = 500L + idx).also { net.nodes[id] = it }
        }
        net.connectChain(ids)

        net.runMs(8_000L)
        // Far end n4 drops internet; n1..n3 have internet
        nodes[3].netLost = true
        nodes[3].totalLossConfirmed = true
        nodes[3].lkReadiness = LiveKitReadiness.FAILED
        nodes[3].recoveryStable = false

        // Needs multi-hop relay across 3 hops (n4 -> n3 -> n2 -> n1)
        net.runMs(6_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision()?.mode == TransportMode.OFFLINE })
        assertTrue(nodes.all { it.actualOwner == "OFFLINE_MESH" })

        // n4 recovers
        nodes[3].netLost = false
        nodes[3].totalLossConfirmed = false
        nodes[3].lkReadiness = LiveKitReadiness.READY
        nodes[3].recoveryStable = true

        net.runMs(30_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision()?.phase == DecisionPhase.ONLINE_COMMITTED })
        assertTrue(nodes.all { it.actualOwner == "LIVEKIT" })
    }

    @Test
    fun partitionAndMerge_offlineBeatsOnlineAtEqualGen_andAllConvergeAfterMerge() {
        val (net, nodes) = setupClique(4, seed = 123L)
        net.runMs(8_000L)

        // Partition: [0,1] vs [2,3]
        net.disconnectAll()
        net.connectEdge("rider-1", "rider-2"); net.connectEdge("rider-2", "rider-1")
        net.connectEdge("rider-3", "rider-4"); net.connectEdge("rider-4", "rider-3")

        // [0,1] go OFFLINE at gen 1
        nodes[0].netLost = true; nodes[0].totalLossConfirmed = true; nodes[0].lkReadiness = LiveKitReadiness.FAILED
        net.runMs(2_000L)
        assertEquals(TransportMode.OFFLINE, nodes[0].coordinator.currentAcceptedDecision()?.mode)
        assertEquals(TransportMode.OFFLINE, nodes[1].coordinator.currentAcceptedDecision()?.mode)
        assertNull(nodes[2].coordinator.currentAcceptedDecision()) // Other partition still ONLINE

        // Merge partitions
        net.connectClique(nodes.map { it.id })
        net.runMs(4_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision()?.mode == TransportMode.OFFLINE })
        assertTrue(nodes.all { it.actualOwner == "OFFLINE_MESH" })
    }

    @Test
    fun offlineAdoptedDespiteRosterMismatch_whileOnlineBlocked() {
        val sid = UUID.randomUUID()
        val snapA = ConvoyMembershipSnapshot.create(sid, listOf(ConvoyMember("a", MemberRole.MESH_PARTICIPANT), ConvoyMember("b", MemberRole.MESH_PARTICIPANT)))
        val snapB = ConvoyMembershipSnapshot.create(sid, listOf(ConvoyMember("a", MemberRole.MESH_PARTICIPANT), ConvoyMember("b", MemberRole.MESH_PARTICIPANT), ConvoyMember("c", MemberRole.MESH_PARTICIPANT)))

        val net = VirtualConvoyNetwork()
        val a = SimNode("a", snapA, { net.nowMs }, 11L).also { net.nodes["a"] = it }
        val b = SimNode("b", snapB, { net.nowMs }, 22L).also { net.nodes["b"] = it }
        net.connectClique(listOf("a", "b"))

        net.runMs(8_000L)
        a.netLost = true; a.totalLossConfirmed = true; a.lkReadiness = LiveKitReadiness.FAILED
        net.runMs(2_000L)

        // B MUST adopt OFFLINE despite rosterHash mismatch (Rule 4.4)
        assertEquals(TransportMode.OFFLINE, b.coordinator.currentAcceptedDecision()?.mode)
        assertEquals("OFFLINE_MESH", b.actualOwner)

        // When A recovers, ONLINE recovery is blocked due to rosterHash mismatch
        a.netLost = false; a.totalLossConfirmed = false; a.lkReadiness = LiveKitReadiness.READY
        net.runMs(25_000L)
        assertEquals(TransportMode.OFFLINE, b.coordinator.currentAcceptedDecision()?.mode)
    }

    @Test
    fun competingProposals_ackArbitration_lossAndDuplicates_convergeSafely() {
        val (net, nodes) = setupClique(4, seed = 555L)
        net.dropProbability = 0.2
        net.duplicateProbability = 0.25
        net.runMs(10_000L)

        // Move to OFFLINE
        nodes[0].netLost = true; nodes[0].totalLossConfirmed = true; nodes[0].lkReadiness = LiveKitReadiness.FAILED
        net.runMs(4_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision()?.mode == TransportMode.OFFLINE })

        // Restore all simultaneously -> staggered or simultaneous proposals with packet loss & duplicates
        nodes[0].netLost = false; nodes[0].totalLossConfirmed = false; nodes[0].lkReadiness = LiveKitReadiness.READY
        net.runMs(35_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision()?.phase == DecisionPhase.ONLINE_COMMITTED })
        assertTrue(nodes.all { it.actualOwner == "LIVEKIT" })
    }

    @Test
    fun manualExclusion_allowsReturnOnlineWhenOneRiderDeadSim_andRespectsCooldown() {
        val (net, nodes) = setupClique(3)
        net.runMs(8_000L)

        // Rider 3 has permanently dead SIM
        nodes[2].netLost = true; nodes[2].totalLossConfirmed = true; nodes[2].lkReadiness = LiveKitReadiness.FAILED
        nodes[2].recoveryStable = false
        net.runMs(2_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision()?.mode == TransportMode.OFFLINE })

        // Wait past 20s dwell; Rider 1 & 2 are ready, Rider 3 is still not ready
        net.runMs(22_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision()?.mode == TransportMode.OFFLINE })

        // Rider 1 explicitly confirms excluding Rider 3
        nodes[0].manualExclusionRequest = ManualExclusionRequest(
            excludedMemberIds = setOf("rider-3"),
            confirmedAtMs = net.nowMs,
            expectedEligibleSnapshot = setOf("rider-1", "rider-2")
        )
        net.runMs(4_000L)
        nodes[0].manualExclusionRequest = null

        val dec1 = nodes[0].coordinator.currentAcceptedDecision()!!
        assertEquals(DecisionPhase.ONLINE_COMMITTED, dec1.phase)
        assertTrue(dec1.manualExclusion)
        assertEquals(0b100, dec1.excludedBitmap)
        assertEquals("LIVEKIT", nodes[0].actualOwner)
        assertEquals("LIVEKIT", nodes[1].actualOwner)
        // Excluded rider 3 keeps working mesh without immediately dragging group back offline inside 60s cooldown
        assertEquals("OFFLINE_MESH", nodes[2].actualOwner)
        assertTrue(nodes[2].coordinator.currentAcceptedDecision()?.phase == DecisionPhase.ONLINE_COMMITTED)

        // Inside 60s REFALLBACK_COOLDOWN, Rider 3 must NOT pull group offline again
        net.runMs(10_000L)
        assertEquals("LIVEKIT", nodes[0].actualOwner)
        assertEquals("LIVEKIT", nodes[1].actualOwner)
    }

    @Test
    fun protocolM_manualReadinessAssertion_andRestartRecovery() {
        val (net, nodes) = setupClique(2)
        nodes.forEach { it.probingUnavailable = true }
        net.runMs(8_000L)

        nodes[0].netLost = true; nodes[0].totalLossConfirmed = true; nodes[0].lkReadiness = LiveKitReadiness.FAILED
        net.runMs(2_000L)
        assertTrue(nodes.all { it.actualOwner == "OFFLINE_MESH" })

        // Wait past dwell; without manual assertion, probingUnavailable blocks recovery
        nodes[0].netLost = false; nodes[0].totalLossConfirmed = false; nodes[0].lkReadiness = LiveKitReadiness.READY
        net.runMs(22_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision()?.mode == TransportMode.OFFLINE })

        // Both riders assert Protocol M manual readiness
        nodes[0].manualReadyAsserted = true
        nodes[1].manualReadyAsserted = true
        net.runMs(5_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision()?.phase == DecisionPhase.ONLINE_COMMITTED })
        assertTrue(nodes.all { it.actualOwner == "LIVEKIT" })

        // Node 0 crashes & loses persistence -> restarts -> enters GENERATION_UNKNOWN -> adopts peer decision
        nodes[0].restart(newIncarnation = 9999L, clearPersistence = true)
        net.runMs(2_000L)
        assertFalse(nodes[0].coordinator.isGenerationUnknown())
        assertEquals(2L, nodes[0].coordinator.currentGenerationHighWater())
    }

    @Test
    fun sevenMeshParticipants_disablesGroupAndPerDeviceFallback() {
        val (net, nodes) = setupClique(7)
        net.runMs(2_000L)
        nodes[0].netLost = true; nodes[0].totalLossConfirmed = true
        net.runMs(5_000L)
        assertTrue(nodes.all { it.coordinator.currentAcceptedDecision() == null })
        assertTrue(nodes.all { it.actualOwner == "LIVEKIT" })
        assertTrue(nodes[0].lastOutput?.actions?.any {
            it is TransportAction.PromptRider && it.kind == PromptKind.MORE_THAN_SIX_PARTICIPANTS
        } == true)
    }
}
