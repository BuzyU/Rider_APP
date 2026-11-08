package com.ridervoice.mesh.transport

import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.graph.MeshGraphSnapshot
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.SessionManager
import com.ridervoice.mesh.ui.MeshSelfTestInput
import com.ridervoice.mesh.ui.MeshSelfTestMapper

/**
 * Pure projection of mesh graph topology, routes, and speakers into unified peer lists and diagnostics.
 */
class MeshPeerProjector(
    private val identity: MeshIdentity,
    private val diagnosticsRepo: MeshDiagnosticsRepository,
    private val clock: () -> Long
) {
    data class ProjectionResult(
        val hasValidRoute: Boolean,
        val activeSpeakers: Set<String>,
        val participants: List<UnifiedVoicePeer>
    )

    fun project(
        snapshot: MeshGraphSnapshot,
        sessionManager: SessionManager?,
        routingTable: RoutingTable?,
        forwardingEngine: ForwardingEngine?,
        speakers: Set<String>,
        linkState: MeshLinkState = MeshLinkState.RUNNING,
        localIp: String = "",
        broadcastAddress: String = "",
        authenticatedNeighborsLast10s: Int = 0,
        secondsWithZeroNeighbours: Long = 0L,
        selfIdOverride: String? = null
    ): ProjectionResult? {
        val sManager = sessionManager ?: return null
        val rTable = routingTable ?: return null
        val fEngine = forwardingEngine ?: return null
        val now = clock()
        val routes = rTable.routes.value
        val hasRoute = routes.values.any { it.expiresAt > now }

        val offlineDevices = UnifiedVoicePeer.projectMeshGraph(snapshot, routes, speakers, now)
        val peers = offlineDevices.map { UnifiedVoicePeer.fromOffline(it, now) }

        val selfId = selfIdOverride ?: identity.deviceId
        val directAliveCount = snapshot.edges.count {
            (it.from == selfId || it.to == selfId) && it.endpoint != null && it.expiresAt > now
        }
        val effectiveNeighbors10s = maxOf(authenticatedNeighborsLast10s, directAliveCount)
        val effectiveZeroSecs = if (effectiveNeighbors10s > 0) 0L else secondsWithZeroNeighbours

        val selfTestState = MeshSelfTestMapper.map(
            MeshSelfTestInput(
                linkState = linkState,
                localIp = localIp.ifBlank { null },
                broadcastAddress = broadcastAddress.ifBlank { null },
                authenticatedNeighboursHeardLast10s = effectiveNeighbors10s,
                secondsWithZeroNeighbours = effectiveZeroSecs
            )
        )

        val cnt = fEngine.counters()
        val skewPeerId = cnt.suspectedClockSkewPeerId
        val skewPeerName = if (skewPeerId != null) {
            snapshot.nodes[skewPeerId]?.displayName ?: skewPeerId.take(8)
        } else null

        diagnosticsRepo.update(
            selfDeviceId = selfId,
            sessionId = sManager.session.sessionId,
            memberCount = sManager.members().size,
            graph = snapshot,
            routes = routes.filterValues { it.expiresAt > now },
            receivedVoiceFrames = cnt.received,
            forwardedVoiceFrames = cnt.forwarded,
            droppedDuplicates = cnt.dup,
            droppedInvalid = cnt.invalid,
            droppedTtl = cnt.ttl,
            droppedStaleTimestamp = cnt.staleTimestamp,
            droppedBadVersion = cnt.droppedBadVersion,
            suspectedClockSkewMs = cnt.suspectedClockSkewMs,
            suspectedClockSkewPeerName = skewPeerName,
            linkState = selfTestState.linkStateText,
            localIp = localIp,
            broadcastAddress = broadcastAddress,
            authenticatedNeighborsLast10s = effectiveNeighbors10s,
            isolationWarningHint = selfTestState.isolationWarningHint
        )

        return ProjectionResult(
            hasValidRoute = hasRoute,
            activeSpeakers = speakers,
            participants = peers
        )
    }
}
