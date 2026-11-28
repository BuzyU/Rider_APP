package com.ridervoice.mesh.link.aware

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.convoy.ConvoyHash
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.ReceiveOutcome
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.ShortIdDirectory
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import com.ridervoice.mesh.topology.PositionFix
import com.ridervoice.mesh.topology.TopologyEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AwareChainLinkPlannerTest {

    private val sessionUuid = ConvoyHash.stableSessionUuid("ride-aware-test")
    private fun tag(id: String): ByteArray = ConvoyHash.computeProposerTag(sessionUuid, id)

    private fun disc(
        id: String,
        idx: Int,
        distM: Double?,
        rootId: String = "r1",
        activeNdps: Int = 0,
        maxNdps: Int = 3,
        nowMs: Long = 10_000L,
        rejectedUntil: Long = 0L
    ) = AwareChainLinkPlanner.DiscoveredAwarePeer(
        memberId = id,
        rosterIdx = idx,
        componentRootTag = tag(rootId),
        activeNdpCount = activeNdps,
        maxNdpSessions = maxNdps,
        distanceM = distM,
        discoveredAtMs = 1_000L,
        lastBeaconMs = nowMs,
        rejectedUntilMs = rejectedUntil
    )

    private fun activeLink(
        peerId: String,
        establishedAtMs: Long = 1_000L,
        lastUdpAliveMs: Long = 9_500L,
        state: AwareChainLinkPlanner.AwareLinkState = AwareChainLinkPlanner.AwareLinkState.ACTIVE,
        isInitiator: Boolean = true
    ) = AwareChainLinkPlanner.ActiveAwareLink(
        peerId = peerId,
        state = state,
        establishedAtMs = establishedAtMs,
        lastUdpAliveMs = lastUdpAliveMs,
        isInitiator = isInitiator
    )

    @Test
    fun scenario1_outOfOrderDiscovery_rider3ConnectedTo4FirstNowConnectsTo2() {
        val chain = listOf("r1", "r2", "r3", "r4", "r5")
        val adj = mapOf(
            "r1" to setOf("r2"),
            "r2" to setOf("r1"),
            "r3" to setOf("r4"),
            "r4" to setOf("r3", "r5"),
            "r5" to setOf("r4")
        )
        val input = AwareChainLinkPlanner.PlannerInput(
            localMemberId = "r3",
            localRosterIdx = 2,
            localComponentRootTag = tag("r3"),
            maxNdpSessions = 2,
            plannedChain = chain,
            discoveredPeers = mapOf(
                "r2" to disc("r2", 1, distM = 40.0, rootId = "r1"),
                "r4" to disc("r4", 3, distM = 42.0, rootId = "r3")
            ),
            activeLinks = mapOf("r4" to activeLink("r4", isInitiator = true)),
            graphAdjacency = adj,
            reachableMemberIds = setOf("r3", "r4", "r5"),
            nowMs = 10_000L
        )
        val decision = AwareChainLinkPlanner.planLinks(input)
        // r2 < r3 -> r3 responds (nudges r2 instead of initiating duplicate from r3 side)
        assertTrue(decision.peersToEvict.isEmpty())
        assertEquals(listOf("r2"), decision.peersToNudge)
    }

    @Test
    fun scenario2_wrongPeerUpgrade_makeBeforeBreakWith3NdpsThenCleanupRedundant() {
        val chain = listOf("r1", "r2", "r3", "r4", "r5")
        // r3 connected wrongly to r1 and r5; both r2 and r4 are now in range
        val adjBefore = mapOf(
            "r1" to setOf("r3"),
            "r3" to setOf("r1", "r5"),
            "r5" to setOf("r3")
        )
        val inputAddThird = AwareChainLinkPlanner.PlannerInput(
            localMemberId = "r3",
            localRosterIdx = 2,
            localComponentRootTag = tag("r1"),
            maxNdpSessions = 3,
            plannedChain = chain,
            discoveredPeers = mapOf(
                "r1" to disc("r1", 0, distM = 95.0, rootId = "r1"),
                "r2" to disc("r2", 1, distM = 40.0, rootId = "r1"),
                "r4" to disc("r4", 3, distM = 42.0, rootId = "r1"),
                "r5" to disc("r5", 4, distM = 92.0, rootId = "r1")
            ),
            activeLinks = mapOf(
                "r1" to activeLink("r1", establishedAtMs = 1_000L, lastUdpAliveMs = 9_500L),
                "r5" to activeLink("r5", establishedAtMs = 1_000L, lastUdpAliveMs = 9_500L)
            ),
            graphAdjacency = adjBefore,
            reachableMemberIds = setOf("r1", "r3", "r5"),
            nowMs = 12_000L
        )
        val step1 = AwareChainLinkPlanner.planLinks(inputAddThird)
        // Must NEVER drop cut-edges r1/r5 while adding ideal neighbor; opens 3rd make-before-break slot
        assertTrue(step1.peersToEvict.isEmpty())
        assertTrue("r2" in step1.peersToNudge || "r4" in step1.peersToInitiate)

        // Once 1-2-3 cycle is formed, r1 is redundant AND ideal link r2 is proven healthy -> r1 evicted cleanly
        val adjCycle = mapOf(
            "r1" to setOf("r2", "r3"),
            "r2" to setOf("r1", "r3"),
            "r3" to setOf("r1", "r2", "r5"),
            "r5" to setOf("r3")
        )
        val inputCleanup = inputAddThird.copy(
            activeLinks = mapOf(
                "r1" to activeLink("r1", establishedAtMs = 1_000L, lastUdpAliveMs = 19_500L),
                "r2" to activeLink("r2", establishedAtMs = 12_000L, lastUdpAliveMs = 19_800L),
                "r5" to activeLink("r5", establishedAtMs = 1_000L, lastUdpAliveMs = 19_500L)
            ),
            graphAdjacency = adjCycle,
            reachableMemberIds = setOf("r1", "r2", "r3", "r5"),
            nowMs = 20_000L
        )
        val step2 = AwareChainLinkPlanner.planLinks(inputCleanup)
        assertEquals(listOf("r1"), step2.peersToEvict)
    }

    @Test
    fun scenario3_partitionHealing_bridgesTwoDisconnectedIslands() {
        val chain = listOf("r1", "r2", "r3", "r4", "r5")
        // Island A = {r1, r2}, Island B = {r3, r4, r5}; leaf r2 has 1/2 NDPs and sees r3 in different component
        val adjLeaf2 = mapOf("r1" to setOf("r2"), "r2" to setOf("r1"))
        val inputLeaf2 = AwareChainLinkPlanner.PlannerInput(
            localMemberId = "r2",
            localRosterIdx = 1,
            localComponentRootTag = tag("r1"),
            maxNdpSessions = 2,
            plannedChain = chain,
            discoveredPeers = mapOf(
                "r1" to disc("r1", 0, distM = 35.0, rootId = "r1", activeNdps = 1),
                "r3" to disc("r3", 2, distM = 45.0, rootId = "r3", activeNdps = 1)
            ),
            activeLinks = mapOf("r1" to activeLink("r1")),
            graphAdjacency = adjLeaf2,
            reachableMemberIds = setOf("r1", "r2"),
            nowMs = 15_000L
        )
        val d2 = AwareChainLinkPlanner.planLinks(inputLeaf2)
        assertEquals(listOf("r3"), d2.peersToInitiate)

        // Responder r3 with 2 NDPs in a triangle {r3, r4, r5} accepts incoming ConnectReq from unreachable r2 by evicting redundant r5!
        val adjIslandB = mapOf(
            "r3" to setOf("r4", "r5"),
            "r4" to setOf("r3", "r5"),
            "r5" to setOf("r3", "r4")
        )
        val inputR3 = AwareChainLinkPlanner.PlannerInput(
            localMemberId = "r3",
            localRosterIdx = 2,
            localComponentRootTag = tag("r3"),
            maxNdpSessions = 2,
            plannedChain = chain,
            discoveredPeers = mapOf(
                "r2" to disc("r2", 1, distM = 45.0, rootId = "r1"),
                "r4" to disc("r4", 3, distM = 40.0, rootId = "r3"),
                "r5" to disc("r5", 4, distM = 80.0, rootId = "r3")
            ),
            activeLinks = mapOf(
                "r4" to activeLink("r4"),
                "r5" to activeLink("r5")
            ),
            graphAdjacency = adjIslandB,
            reachableMemberIds = setOf("r3", "r4", "r5"),
            nowMs = 15_000L
        )
        val incoming = AwareChainLinkPlanner.evaluateIncoming("r2", inputR3)
        assertEquals(AwareChainLinkPlanner.IncomingDecision.AcceptAfterEvict("r5"), incoming)
    }

    @Test
    fun scenario4_cutEdgeProtection_neverEvictsCriticalBridgeWhenFull() {
        val adj = mapOf(
            "r1" to setOf("r2"),
            "r2" to setOf("r1", "r3"),
            "r3" to setOf("r2", "r4"),
            "r4" to setOf("r3")
        )
        assertTrue(AwareChainLinkPlanner.isCutEdge("r3", "r2", adj))
        assertTrue(AwareChainLinkPlanner.isCutEdge("r3", "r4", adj))

        val input = AwareChainLinkPlanner.PlannerInput(
            localMemberId = "r3",
            localRosterIdx = 2,
            localComponentRootTag = tag("r1"),
            maxNdpSessions = 2,
            plannedChain = listOf("r1", "r3", "r2", "r4"), // r3 ideal is r1 & r2, NOT r4
            discoveredPeers = mapOf(
                "r1" to disc("r1", 0, distM = 30.0, rootId = "r1"),
                "r2" to disc("r2", 1, distM = 40.0, rootId = "r1"),
                "r4" to disc("r4", 3, distM = 45.0, rootId = "r1")
            ),
            activeLinks = mapOf(
                "r2" to activeLink("r2", establishedAtMs = 1_000L, lastUdpAliveMs = 19_500L),
                "r4" to activeLink("r4", establishedAtMs = 1_000L, lastUdpAliveMs = 19_500L)
            ),
            graphAdjacency = adj,
            reachableMemberIds = setOf("r1", "r2", "r3", "r4"),
            nowMs = 20_000L
        )
        val decision = AwareChainLinkPlanner.planLinks(input)
        // r4 is a critical cut-edge (nothing else reaches r4), so r3 MUST NOT evict r4!
        assertTrue(decision.peersToEvict.isEmpty())
    }

    @Test
    fun scenario5_noGpsTunnelFallback_connectsClosestByRosterIndexWithoutIsolation() {
        val input = AwareChainLinkPlanner.PlannerInput(
            localMemberId = "r3",
            localRosterIdx = 2,
            localComponentRootTag = tag("r3"),
            maxNdpSessions = 2,
            plannedChain = emptyList(), // Tunnel: no GPS fixes
            discoveredPeers = mapOf(
                "r1" to disc("r1", 0, distM = null, rootId = "r1"),
                "r2" to disc("r2", 1, distM = null, rootId = "r2"),
                "r4" to disc("r4", 3, distM = null, rootId = "r4")
            ),
            activeLinks = emptyMap(),
            graphAdjacency = emptyMap(),
            reachableMemberIds = setOf("r3"),
            nowMs = 5_000L
        )
        val decision = AwareChainLinkPlanner.planLinks(input)
        // Picks r2 and r4 (|rosterIdx - 2| == 1) instead of r1 (diff == 2)
        val chosen = (decision.peersToInitiate + decision.peersToNudge).toSet()
        assertEquals(setOf("r2", "r4"), chosen)
    }

    @Test
    fun scenario6_sixRiderChainWithMax2NdpsPerDevice_convergesAndNeverExceedsBudget() {
        val chain = listOf("r1", "r2", "r3", "r4", "r5", "r6")
        for ((idx, id) in chain.withIndex()) {
            val ideal = AwareChainLinkPlanner.idealNeighborsFor(id, chain)
            val isLeaf = AwareChainLinkPlanner.isChainLeaf(id, chain)
            assertEquals(if (isLeaf) 1 else 2, ideal.size)

            val discPeers = chain.mapIndexedNotNull { pIdx, pId ->
                if (kotlin.math.abs(pIdx - idx) == 1) {
                    pId to disc(pId, pIdx, distM = 40.0, rootId = pId, maxNdps = 2)
                } else null
            }.toMap()
            val d = AwareChainLinkPlanner.planLinks(
                AwareChainLinkPlanner.PlannerInput(
                    localMemberId = id,
                    localRosterIdx = idx,
                    localComponentRootTag = tag(id),
                    maxNdpSessions = 2,
                    plannedChain = chain,
                    discoveredPeers = discPeers,
                    activeLinks = emptyMap(),
                    graphAdjacency = emptyMap(),
                    reachableMemberIds = setOf(id),
                    nowMs = 5_000L
                )
            )
            assertEquals(ideal, (d.peersToInitiate + d.peersToNudge).toSet())
        }
    }

    @Test
    fun scenario7_zombieAndStuckConnectEvictionAndRejectCooldown() {
        val chain = listOf("r1", "r2", "r3")
        val input = AwareChainLinkPlanner.PlannerInput(
            localMemberId = "r1",
            localRosterIdx = 0,
            localComponentRootTag = tag("r1"),
            maxNdpSessions = 2,
            plannedChain = chain,
            discoveredPeers = mapOf(
                "r2" to disc("r2", 1, distM = 30.0, rootId = "r2"),
                "r3" to disc("r3", 2, distM = 70.0, rootId = "r3", rejectedUntil = 15_000L)
            ),
            activeLinks = mapOf(
                "r2" to activeLink(
                    "r2",
                    establishedAtMs = 1_000L,
                    lastUdpAliveMs = 1_500L, // dead > 5s past 6s grace
                    state = AwareChainLinkPlanner.AwareLinkState.ACTIVE
                ),
                "r3" to activeLink(
                    "r3",
                    establishedAtMs = 1_000L,
                    lastUdpAliveMs = 0L,
                    state = AwareChainLinkPlanner.AwareLinkState.CONNECTING // stuck >= 8s
                )
            ),
            graphAdjacency = emptyMap(),
            reachableMemberIds = setOf("r1"),
            nowMs = 10_000L,
            directTimeoutMs = 5_000L
        )
        val decision = AwareChainLinkPlanner.planLinks(input)
        assertEquals(setOf("r2", "r3"), decision.peersToEvict.toSet())
        // r3 is in reject cooldown until 15_000L so must NOT be re-initiated immediately
        assertFalse("r3" in decision.peersToInitiate)
    }

    @Test
    fun scenario8_5NodeChainConvergenceAnd4HopEncryptedVoiceDeliveryFromRider1ToRider5() {
        val participants = listOf("r1", "r2", "r3", "r4", "r5")
        val positions = participants.mapIndexed { i, id ->
            id to PositionFix(id, 100, 18_520_000 + i * 500, 73_850_000, 50)
        }.toMap()
        val plan = TopologyEngine.computePlan(
            meshParticipants = participants,
            positions = positions,
            dualP2pCapableIds = participants.toSet(),
            currentHostId = "r1",
            preferChainMode = true
        )
        assertTrue(plan.isChainMode)
        assertEquals(participants, plan.orderedChain)

        // Simulate realistic out-of-order radio visibility across 5 riders along highway (~55m per hop; adjacent + occasional 2-hop bleed)
        val radioRangePairs = setOf(
            "r1" to "r2", "r2" to "r1",
            "r2" to "r3", "r3" to "r2",
            "r3" to "r4", "r4" to "r3",
            "r3" to "r5", "r5" to "r3", // bleed: r3 initially sees r5
            "r4" to "r5", "r5" to "r4"
        )
        val links = HashMap<String, MutableSet<String>>().apply {
            participants.forEach { put(it, LinkedHashSet()) }
        }
        // Start in a tricky state: r3 connected ONLY to r5 initially (wrong peer!) before r2/r4 arrive
        links["r3"]!!.add("r5")
        links["r5"]!!.add("r3")

        // Converge via deterministic planner rounds
        var now = 20_000L
        for (round in 1..6) {
            now += 1_500L
            for (u in participants) {
                val adjCopy = links.mapValues { it.value.toSet() }
                // BFS reachable from u in current links
                val reach = LinkedHashSet<String>().apply { add(u) }
                val q = java.util.ArrayDeque<String>().apply { add(u) }
                while (q.isNotEmpty()) {
                    val c = q.removeFirst()
                    for (nx in adjCopy[c].orEmpty()) if (reach.add(nx)) q.add(nx)
                }
                val rootId = reach.minByOrNull { it } ?: u
                val discMap = participants.mapIndexedNotNull { idx, v ->
                    if ((u to v) in radioRangePairs) {
                        val vIdx = participants.indexOf(v)
                        val uIdx = participants.indexOf(u)
                        val d = kotlin.math.abs(vIdx - uIdx) * 55.0
                        v to disc(v, idx, d, rootId = rootId, activeNdps = links[v]!!.size, maxNdps = 3, nowMs = now)
                    } else null
                }.toMap()
                val activeMap = links[u]!!.associateWith { v ->
                    activeLink(v, establishedAtMs = 1_000L, lastUdpAliveMs = now - 500L, isInitiator = WifiAwareCredentials.isInitiator(u, v))
                }
                val input = AwareChainLinkPlanner.PlannerInput(
                    localMemberId = u,
                    localRosterIdx = participants.indexOf(u),
                    localComponentRootTag = tag(rootId),
                    maxNdpSessions = 3,
                    plannedChain = plan.orderedChain,
                    discoveredPeers = discMap,
                    activeLinks = activeMap,
                    graphAdjacency = adjCopy,
                    reachableMemberIds = reach,
                    nowMs = now
                )
                val decision = AwareChainLinkPlanner.planLinks(input)
                for (ev in decision.peersToEvict) {
                    links[u]!!.remove(ev)
                    links[ev]!!.remove(u)
                }
                for (target in decision.peersToInitiate) {
                    val targetAdj = links.mapValues { it.value.toSet() }
                    val targetReach = LinkedHashSet<String>().apply { add(target) }
                    val tq = java.util.ArrayDeque<String>().apply { add(target) }
                    while (tq.isNotEmpty()) {
                        val c = tq.removeFirst()
                        for (nx in targetAdj[c].orEmpty()) if (targetReach.add(nx)) tq.add(nx)
                    }
                    val targetRoot = targetReach.minByOrNull { it } ?: target
                    val targetDisc = participants.mapIndexedNotNull { idx, v ->
                        if ((target to v) in radioRangePairs) {
                            val d = kotlin.math.abs(participants.indexOf(v) - participants.indexOf(target)) * 55.0
                            v to disc(v, idx, d, rootId = targetRoot, activeNdps = links[v]!!.size, maxNdps = 3, nowMs = now)
                        } else null
                    }.toMap()
                    val targetActive = links[target]!!.associateWith { v ->
                        activeLink(v, establishedAtMs = 1_000L, lastUdpAliveMs = now - 500L, isInitiator = WifiAwareCredentials.isInitiator(target, v))
                    }
                    val inc = AwareChainLinkPlanner.evaluateIncoming(
                        u,
                        AwareChainLinkPlanner.PlannerInput(
                            localMemberId = target,
                            localRosterIdx = participants.indexOf(target),
                            localComponentRootTag = tag(targetRoot),
                            maxNdpSessions = 3,
                            plannedChain = plan.orderedChain,
                            discoveredPeers = targetDisc,
                            activeLinks = targetActive,
                            graphAdjacency = targetAdj,
                            reachableMemberIds = targetReach,
                            nowMs = now
                        )
                    )
                    when (inc) {
                        is AwareChainLinkPlanner.IncomingDecision.Accept -> {
                            links[u]!!.add(target)
                            links[target]!!.add(u)
                        }
                        is AwareChainLinkPlanner.IncomingDecision.AcceptAfterEvict -> {
                            links[target]!!.remove(inc.evictPeerId)
                            links[inc.evictPeerId]!!.remove(target)
                            links[u]!!.add(target)
                            links[target]!!.add(u)
                        }
                        else -> Unit
                    }
                }
            }
        }

        // Verify all 5 riders converged into clean 1-2-3-4-5 chain links
        assertEquals(setOf("r2"), links["r1"])
        assertEquals(setOf("r1", "r3"), links["r2"])
        assertEquals(setOf("r2", "r4"), links["r3"])
        assertEquals(setOf("r3", "r5"), links["r4"])
        assertEquals(setOf("r4"), links["r5"])

        // Wire 5 real ForwardingEngine + MeshGraphRepository instances over simulated IPv6 NDPs & verify 4-hop encrypted voice delivery!
        val session = OfflineSession.fromCode("K7QXM2PA9TDE4WZH", 1000L, iterations = 1)!!
        val rxFrames = ArrayDeque<Pair<String, MeshFrame>>()
        class SimLink(private val ownerId: String) : MeshLink {
            override val linkId: String = "aware-$ownerId"
            private val _state = MutableStateFlow(MeshLinkState.RUNNING)
            override val state: StateFlow<MeshLinkState> = _state.asStateFlow()
            private val _in = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
            override val incomingFrames: Flow<MeshFrame> = _in.asSharedFlow()
            val endpointToOwner = HashMap<MeshEndpoint, String>()
            val endpointsByNeighbor = HashMap<String, MeshEndpoint>()

            override suspend fun start() {}
            override suspend fun stop() {}
            override fun endpoints(): Set<MeshEndpoint> = endpointsByNeighbor.values.toSet()
            override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
                val targetId = endpointToOwner[endpoint] ?: return false
                val srcEpForTarget = MeshEndpoint("fe80::${ownerId}", 45454)
                rxFrames.addLast(targetId to MeshFrame(srcEpForTarget, payload))
                return true
            }
        }

        val simLinks = participants.associateWith { SimLink(it) }
        for (u in participants) {
            for (v in links[u]!!) {
                val targetEp = MeshEndpoint("fe80::$v", 45454)
                simLinks[u]!!.endpointToOwner[targetEp] = v
                simLinks[u]!!.endpointsByNeighbor[v] = targetEp
            }
        }

        val deliveredVoiceToR5 = ArrayList<MeshPacket>()
        val engines = HashMap<String, ForwardingEngine>()
        val graphs = HashMap<String, MeshGraphRepository>()
        val tables = HashMap<String, RoutingTable>()

        for (u in participants) {
            val uuid = UUID.nameUUIDFromBytes(u.toByteArray()).toString()
            val gRepo = MeshGraphRepository(uuid, u, { now })
            val rTable = RoutingTable()
            val fEngine = ForwardingEngine(
                selfId = uuid,
                selfName = u,
                sessionManager = SessionManager(session, uuid, { now }),
                graphRepository = gRepo,
                routingTable = rTable,
                replayWindow = ReplayWindow(),
                shortIdDirectory = ShortIdDirectory(),
                ttlPolicy = TtlPolicy(clock = { now }),
                link = simLinks[u]!!,
                clock = { now },
                onLocalVoicePacket = { pkt ->
                    if (u == "r5") deliveredVoiceToR5.add(pkt)
                }
            )
            engines[u] = fEngine
            graphs[u] = gRepo
            tables[u] = rTable
        }

        fun drainNetwork() {
            while (rxFrames.isNotEmpty()) {
                val (target, frame) = rxFrames.removeFirst()
                engines[target]!!.onIncomingFrame(frame)
            }
        }

        // Warm HELLOs so routing table builds full 4-hop chain r1 -> r2 -> r3 -> r4 -> r5
        repeat(4) {
            for (u in participants) {
                engines[u]!!.originateHello()
                drainNetwork()
            }
        }

        val r5Uuid = UUID.nameUUIDFromBytes("r5".toByteArray()).toString()
        val routeR1ToR5 = tables["r1"]!!.routeTo(r5Uuid)
        assertNotNull("r1 must have multi-hop route to r5", routeR1ToR5)
        assertEquals(4, routeR1ToR5!!.hopCount)

        // Rider 1 sends non-silent PCM voice frame -> decrypted at Rider 5 across 4 links (3 intermediate relays)!
        val pcmSpeech = ByteArray(MeshConfig.BYTES_PER_FRAME) { idx -> ((idx * 37) % 120).toByte() }
        val sentVoice = engines["r1"]!!.originateVoice(pcmSpeech)
        assertNotNull(sentVoice)
        drainNetwork()

        assertEquals(1, deliveredVoiceToR5.size)
        assertEquals(3, deliveredVoiceToR5.first().hopCount)
    }

    @Test
    fun scenario9_tier2SwapReachability_evictsRedundantNonIdealLinkOnMaxNdp2AndLobbyPreservesNdps() {
        // (a) Tier 2 redundant swap when maxNdpSessions = 2 and redundant non-ideal edge exists after settle window
        val chain = listOf("r1", "r2", "r3", "r4", "r5")
        val adjWithCycle = mapOf(
            "r1" to setOf("r2", "r3"),
            "r2" to setOf("r1", "r4"),
            "r3" to setOf("r1", "r5"),
            "r4" to setOf("r2", "r5"),
            "r5" to setOf("r3", "r4")
        )
        val inputSwap = AwareChainLinkPlanner.PlannerInput(
            localMemberId = "r3",
            localRosterIdx = 2,
            localComponentRootTag = tag("r1"),
            maxNdpSessions = 2,
            plannedChain = chain,
            discoveredPeers = mapOf(
                "r1" to disc("r1", 0, distM = 90.0, rootId = "r1"),
                "r2" to disc("r2", 1, distM = 35.0, rootId = "r1"),
                "r4" to disc("r4", 3, distM = 35.0, rootId = "r1"),
                "r5" to disc("r5", 4, distM = 90.0, rootId = "r1")
            ),
            activeLinks = mapOf(
                "r1" to activeLink("r1", establishedAtMs = 1_000L, lastUdpAliveMs = 19_500L),
                "r5" to activeLink("r5", establishedAtMs = 1_000L, lastUdpAliveMs = 19_500L)
            ),
            graphAdjacency = adjWithCycle,
            reachableMemberIds = setOf("r1", "r2", "r3", "r4", "r5"),
            nowMs = 20_000L
        )
        val swapDecision = AwareChainLinkPlanner.planLinks(inputSwap)
        assertEquals(1, swapDecision.peersToEvict.size)

        // (b) Lobby (expectUdpTraffic = false) never evicts healthy active NDPs after 6s with zero UDP
        val lobbyInput = AwareChainLinkPlanner.PlannerInput(
            localMemberId = "r2",
            localRosterIdx = 1,
            localComponentRootTag = tag("r1"),
            maxNdpSessions = 2,
            plannedChain = chain,
            discoveredPeers = mapOf(
                "r1" to disc("r1", 0, distM = 30.0, rootId = "r1", nowMs = 30_000L),
                "r3" to disc("r3", 2, distM = 30.0, rootId = "r1", nowMs = 30_000L)
            ),
            activeLinks = mapOf(
                "r1" to activeLink("r1", establishedAtMs = 1_000L, lastUdpAliveMs = 1_000L),
                "r3" to activeLink("r3", establishedAtMs = 1_000L, lastUdpAliveMs = 1_000L)
            ),
            graphAdjacency = mapOf("r1" to setOf("r2"), "r2" to setOf("r1", "r3"), "r3" to setOf("r2")),
            reachableMemberIds = setOf("r1", "r2", "r3"),
            nowMs = 30_000L,
            directTimeoutMs = 5_000L,
            expectUdpTraffic = false
        )
        val lobbyDecision = AwareChainLinkPlanner.planLinks(lobbyInput)
        assertTrue("Lobby must never churn active NDPs without UDP traffic", lobbyDecision.peersToEvict.isEmpty())
    }
}
