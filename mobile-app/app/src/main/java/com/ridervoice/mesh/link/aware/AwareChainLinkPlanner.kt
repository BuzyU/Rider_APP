package com.ridervoice.mesh.link.aware

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.convoy.ConvoyHash
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Pure-Kotlin deterministic planner for Wi-Fi Aware Bus/Chain links.
 *
 * Solves all real-world edge cases cleanly:
 * - Out-of-order discovery (e.g., Rider 3 connects to 4 first, then connects to 2 when discovered)
 * - Wrong-peer upgrades (e.g., 3 connected to 1 & 5 when 2 & 4 later come into range)
 * - Partition healing (two disconnected islands `{1,2}` and `{3,4,5}` bridged immediately)
 * - Cut-edge (bridge) safety: never drops a critical single bridge link while healthy
 * - Make-before-break when hardware supports >= 3 NDPs
 * - Zombie NDP and stuck-connecting eviction
 */
object AwareChainLinkPlanner {

    enum class AwareLinkState {
        CONNECTING,
        ACTIVE
    }

    data class DiscoveredAwarePeer(
        val memberId: String,
        val rosterIdx: Int,
        val componentRootTag: ByteArray,
        val activeNdpCount: Int,
        val maxNdpSessions: Int,
        val distanceM: Double?,
        val discoveredAtMs: Long,
        val lastBeaconMs: Long,
        val rejectedUntilMs: Long = 0L
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DiscoveredAwarePeer) return false
            return memberId == other.memberId &&
                rosterIdx == other.rosterIdx &&
                componentRootTag.contentEquals(other.componentRootTag) &&
                activeNdpCount == other.activeNdpCount &&
                maxNdpSessions == other.maxNdpSessions &&
                distanceM == other.distanceM &&
                discoveredAtMs == other.discoveredAtMs &&
                lastBeaconMs == other.lastBeaconMs &&
                rejectedUntilMs == other.rejectedUntilMs
        }

        override fun hashCode(): Int {
            var r = memberId.hashCode()
            r = 31 * r + rosterIdx
            r = 31 * r + componentRootTag.contentHashCode()
            r = 31 * r + activeNdpCount
            r = 31 * r + maxNdpSessions
            r = 31 * r + (distanceM?.hashCode() ?: 0)
            r = 31 * r + discoveredAtMs.hashCode()
            r = 31 * r + lastBeaconMs.hashCode()
            r = 31 * r + rejectedUntilMs.hashCode()
            return r
        }
    }

    data class ActiveAwareLink(
        val peerId: String,
        val state: AwareLinkState,
        val establishedAtMs: Long,
        val lastUdpAliveMs: Long,
        val isInitiator: Boolean
    )

    data class PlannerInput(
        val localMemberId: String,
        val localRosterIdx: Int,
        val localComponentRootTag: ByteArray,
        val maxNdpSessions: Int,
        val plannedChain: List<String>,
        val discoveredPeers: Map<String, DiscoveredAwarePeer>,
        val activeLinks: Map<String, ActiveAwareLink>,
        val graphAdjacency: Map<String, Set<String>>,
        val reachableMemberIds: Set<String>,
        val nowMs: Long,
        val directTimeoutMs: Long = MeshConfig.ACTIVE_NEIGHBOR_TIMEOUT_MS,
        val isLiveOfflineVoice: Boolean = false,
        val expectUdpTraffic: Boolean = true
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PlannerInput) return false
            return localMemberId == other.localMemberId &&
                localRosterIdx == other.localRosterIdx &&
                localComponentRootTag.contentEquals(other.localComponentRootTag) &&
                maxNdpSessions == other.maxNdpSessions &&
                plannedChain == other.plannedChain &&
                discoveredPeers == other.discoveredPeers &&
                activeLinks == other.activeLinks &&
                graphAdjacency == other.graphAdjacency &&
                reachableMemberIds == other.reachableMemberIds &&
                nowMs == other.nowMs &&
                directTimeoutMs == other.directTimeoutMs &&
                isLiveOfflineVoice == other.isLiveOfflineVoice &&
                expectUdpTraffic == other.expectUdpTraffic
        }

        override fun hashCode(): Int {
            var r = localMemberId.hashCode()
            r = 31 * r + localRosterIdx
            r = 31 * r + localComponentRootTag.contentHashCode()
            r = 31 * r + maxNdpSessions
            r = 31 * r + plannedChain.hashCode()
            r = 31 * r + discoveredPeers.hashCode()
            r = 31 * r + activeLinks.hashCode()
            r = 31 * r + graphAdjacency.hashCode()
            r = 31 * r + reachableMemberIds.hashCode()
            r = 31 * r + nowMs.hashCode()
            r = 31 * r + directTimeoutMs.hashCode()
            r = 31 * r + isLiveOfflineVoice.hashCode()
            r = 31 * r + expectUdpTraffic.hashCode()
            return r
        }
    }

    data class PlannerDecision(
        val peersToInitiate: List<String>,
        val peersToNudge: List<String>,
        val peersToEvict: List<String>,
        val desiredNeighbors: List<String>
    )

    sealed class IncomingDecision {
        data object Accept : IncomingDecision()
        data class AcceptAfterEvict(val evictPeerId: String) : IncomingDecision()
        data object RejectFull : IncomingDecision()
        data object RejectNotNeeded : IncomingDecision()
    }

    fun idealNeighborsFor(localMemberId: String, plannedChain: List<String>): Set<String> {
        val idx = plannedChain.indexOf(localMemberId)
        if (idx < 0) return emptySet()
        val out = LinkedHashSet<String>(2)
        if (idx > 0) out.add(plannedChain[idx - 1])
        if (idx < plannedChain.lastIndex) out.add(plannedChain[idx + 1])
        return out
    }

    fun isChainLeaf(localMemberId: String, plannedChain: List<String>): Boolean {
        val idx = plannedChain.indexOf(localMemberId)
        return idx == 0 || (plannedChain.size >= 2 && idx == plannedChain.lastIndex)
    }

    /**
     * Returns true when removing undirected edge `(selfId, peerId)` leaves `peerId`
     * unreachable from `selfId` in [graphAdjacency] (meaning `(selfId, peerId)` is a critical bridge).
     */
    fun isCutEdge(
        selfId: String,
        peerId: String,
        graphAdjacency: Map<String, Set<String>>
    ): Boolean {
        if (selfId == peerId) return false
        val visited = HashSet<String>()
        val queue = ArrayDeque<String>()
        visited.add(selfId)
        queue.add(selfId)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val neighbors = graphAdjacency[cur].orEmpty()
            for (nxt in neighbors) {
                val isRemovedEdge = (cur == selfId && nxt == peerId) || (cur == peerId && nxt == selfId)
                if (isRemovedEdge) continue
                if (nxt == peerId) return false
                if (visited.add(nxt)) {
                    queue.add(nxt)
                }
            }
        }
        return true
    }

    private fun isPeerDifferentComponentOrUnreachable(
        peer: DiscoveredAwarePeer,
        input: PlannerInput
    ): Boolean {
        if (peer.memberId !in input.reachableMemberIds && peer.memberId !in input.activeLinks.keys) {
            return true
        }
        if (peer.componentRootTag.size == 8 && input.localComponentRootTag.size == 8 &&
            !peer.componentRootTag.contentEquals(input.localComponentRootTag)
        ) {
            return true
        }
        return false
    }

    private fun candidateTier(
        peer: DiscoveredAwarePeer,
        idealNeighbors: Set<String>,
        input: PlannerInput
    ): Int {
        val diffComp = isPeerDifferentComponentOrUnreachable(peer, input)
        val isIdeal = peer.memberId in idealNeighbors
        return when {
            diffComp && isIdeal -> 0
            diffComp -> 1
            isIdeal -> 2
            else -> 3
        }
    }

    private fun compareCandidateOrder(
        a: DiscoveredAwarePeer,
        b: DiscoveredAwarePeer,
        idealNeighbors: Set<String>,
        input: PlannerInput
    ): Int {
        val tierA = candidateTier(a, idealNeighbors, input)
        val tierB = candidateTier(b, idealNeighbors, input)
        if (tierA != tierB) return tierA - tierB

        // Prefer unsaturated peers that still have room for another NDP session
        val satA = if (a.activeNdpCount >= a.maxNdpSessions) 1 else 0
        val satB = if (b.activeNdpCount >= b.maxNdpSessions) 1 else 0
        if (satA != satB) return satA - satB

        // Distance (when GPS available)
        val distA = a.distanceM ?: Double.POSITIVE_INFINITY
        val distB = b.distanceM ?: Double.POSITIVE_INFINITY
        if (distA != distB) return distA.compareTo(distB)

        // Roster proximity fallback when GPS is absent (e.g., tunnel)
        val rDiffA = abs(a.rosterIdx - input.localRosterIdx)
        val rDiffB = abs(b.rosterIdx - input.localRosterIdx)
        if (rDiffA != rDiffB) return rDiffA - rDiffB

        return ConvoyHash.compareUtf8Bytes(
            a.memberId.toByteArray(Charsets.UTF_8),
            b.memberId.toByteArray(Charsets.UTF_8)
        )
    }

    private fun findEvictableRedundantNonIdealLink(
        links: Map<String, ActiveAwareLink>,
        idealNeighbors: Set<String>,
        discoveredPeers: Map<String, DiscoveredAwarePeer>,
        input: PlannerInput
    ): String? {
        return links.values
            .filter { link ->
                link.peerId !in idealNeighbors &&
                    link.state == ActiveAwareLinkStateHelper.ACTIVE &&
                    !isCutEdge(input.localMemberId, link.peerId, input.graphAdjacency)
            }
            .maxWithOrNull { a, b ->
                val distA = discoveredPeers[a.peerId]?.distanceM ?: -1.0
                val distB = discoveredPeers[b.peerId]?.distanceM ?: -1.0
                if (distA != distB) distA.compareTo(distB) else a.peerId.compareTo(b.peerId)
            }
            ?.peerId
    }

    private object ActiveAwareLinkStateHelper {
        val ACTIVE = AwareLinkState.ACTIVE
    }

    fun planLinks(input: PlannerInput): PlannerDecision {
        val effectiveMaxNdps = input.maxNdpSessions.coerceIn(0, 3)
        val idealNeighbors = idealNeighborsFor(input.localMemberId, input.plannedChain)
        val isLeaf = isChainLeaf(input.localMemberId, input.plannedChain)
        val steadyStateBudget = when {
            effectiveMaxNdps == 0 -> 0
            isLeaf -> 1
            else -> minOf(2, effectiveMaxNdps)
        }

        val evictSet = LinkedHashSet<String>()
        val survivingLinks = LinkedHashMap<String, ActiveAwareLink>()

        // 1. Evict stuck CONNECTING links and zombie ACTIVE links (only when UDP traffic is expected)
        for ((peerId, link) in input.activeLinks) {
            val isStuckConnect = link.state == AwareLinkState.CONNECTING &&
                (input.nowMs - link.establishedAtMs) >= MeshConfig.AWARE_CONNECT_TIMEOUT_MS
            val isZombieActive = input.expectUdpTraffic &&
                link.state == AwareLinkState.ACTIVE &&
                (input.nowMs - link.establishedAtMs) >= MeshConfig.AWARE_ZOMBIE_GRACE_MS &&
                (input.nowMs - maxOf(link.establishedAtMs, link.lastUdpAliveMs)) > input.directTimeoutMs
            if (isStuckConnect || isZombieActive) {
                evictSet.add(peerId)
            } else {
                survivingLinks[peerId] = link
            }
        }

        // 2. Post-make-before-break cleanup: if holding more links than steadyStateBudget,
        // drop a confirmed redundant non-ideal link once ideal active links are healthy.
        if (survivingLinks.size > steadyStateBudget) {
            val allPresentIdealLinksProvenHealthy = survivingLinks.values
                .filter { it.peerId in idealNeighbors }
                .all {
                    it.state == AwareLinkState.ACTIVE &&
                        (!input.expectUdpTraffic ||
                            (it.lastUdpAliveMs > 0L && (input.nowMs - it.establishedAtMs) >= MeshConfig.AWARE_ZOMBIE_GRACE_MS))
                }
            if (allPresentIdealLinksProvenHealthy) {
                val redundant = findEvictableRedundantNonIdealLink(
                    links = survivingLinks,
                    idealNeighbors = idealNeighbors,
                    discoveredPeers = input.discoveredPeers,
                    input = input
                )
                if (redundant != null) {
                    evictSet.add(redundant)
                    survivingLinks.remove(redundant)
                }
            }
        }

        // 3. Rank missing discovered peers (fresh beacons, not currently connected, not in reject cooldown)
        val candidates = input.discoveredPeers.values
            .filter { peer ->
                peer.memberId != input.localMemberId &&
                    peer.memberId !in input.activeLinks.keys &&
                    (input.nowMs - peer.lastBeaconMs) <= MeshConfig.AWARE_BEACON_TTL_MS &&
                    input.nowMs >= peer.rejectedUntilMs
            }
            .sortedWith { a, b -> compareCandidateOrder(a, b, idealNeighbors, input) }

        val settledForSwap = !input.isLiveOfflineVoice &&
            survivingLinks.values.all {
                it.state == AwareLinkState.ACTIVE &&
                    (input.nowMs - it.establishedAtMs) >= MeshConfig.TOPOLOGY_SETTLE_MS
            }
        val toInitiate = ArrayList<String>()
        val toNudge = ArrayList<String>()
        var simulatedLinkCount = survivingLinks.size

        for (candidate in candidates) {
            val tier = candidateTier(candidate, idealNeighbors, input)
            val freeSlotAvailable = simulatedLinkCount < effectiveMaxNdps && when (tier) {
                0, 1 -> true
                2 -> simulatedLinkCount < steadyStateBudget || effectiveMaxNdps >= 3
                3 -> simulatedLinkCount == 0
                else -> false
            }
            val redundantSwap = if (!freeSlotAvailable && (tier <= 1 || (tier == 2 && settledForSwap))) {
                findEvictableRedundantNonIdealLink(
                    links = survivingLinks,
                    idealNeighbors = idealNeighbors,
                    discoveredPeers = input.discoveredPeers,
                    input = input
                )
            } else {
                null
            }

            if (freeSlotAvailable) {
                if (WifiAwareCredentials.isInitiator(input.localMemberId, candidate.memberId)) {
                    toInitiate.add(candidate.memberId)
                } else {
                    toNudge.add(candidate.memberId)
                }
                simulatedLinkCount++
                continue
            }

            if (redundantSwap != null && redundantSwap !in evictSet) {
                evictSet.add(redundantSwap)
                survivingLinks.remove(redundantSwap)
                // Wait 1 tick for local HAL to teardown evicted NDP before opening replacement
                break
            }
        }

        val desired = (idealNeighbors.toList() + toInitiate + toNudge)
            .distinct()

        return PlannerDecision(
            peersToInitiate = toInitiate,
            peersToNudge = toNudge,
            peersToEvict = evictSet.toList(),
            desiredNeighbors = desired
        )
    }

    fun evaluateIncoming(
        fromPeerId: String,
        input: PlannerInput
    ): IncomingDecision {
        val effectiveMaxNdps = input.maxNdpSessions.coerceIn(0, 3)
        if (fromPeerId in input.activeLinks.keys) {
            return IncomingDecision.Accept
        }
        if (effectiveMaxNdps == 0) {
            return IncomingDecision.RejectFull
        }

        val idealNeighbors = idealNeighborsFor(input.localMemberId, input.plannedChain)
        val isLeaf = isChainLeaf(input.localMemberId, input.plannedChain)
        val steadyStateBudget = if (isLeaf) 1 else minOf(2, effectiveMaxNdps)

        val peer = input.discoveredPeers[fromPeerId]
        val isUnreachableOrDiffComp = peer?.let { isPeerDifferentComponentOrUnreachable(it, input) }
            ?: (fromPeerId !in input.reachableMemberIds)
        val isIdeal = fromPeerId in idealNeighbors

        return if (isUnreachableOrDiffComp || isIdeal) {
            if (input.activeLinks.size < effectiveMaxNdps) {
                IncomingDecision.Accept
            } else {
                val evict = findEvictableRedundantNonIdealLink(
                    links = input.activeLinks,
                    idealNeighbors = idealNeighbors,
                    discoveredPeers = input.discoveredPeers,
                    input = input
                )
                if (evict != null) {
                    IncomingDecision.AcceptAfterEvict(evict)
                } else {
                    IncomingDecision.RejectFull
                }
            }
        } else {
            // Peer is already reachable over another path AND is not an ideal neighbor
            if (input.activeLinks.size < steadyStateBudget && !isLeaf && input.plannedChain.isEmpty()) {
                // Before GPS chain settles, accept up to budget so initial connectivity forms fast
                IncomingDecision.Accept
            } else {
                IncomingDecision.RejectNotNeeded
            }
        }
    }
}
