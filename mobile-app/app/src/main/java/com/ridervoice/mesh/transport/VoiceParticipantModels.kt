package com.ridervoice.mesh.transport

import com.ridervoice.mesh.graph.MeshGraphSnapshot
import com.ridervoice.mesh.routing.RouteEntry

enum class PeerTransportKind {
    ONLINE_LIVEKIT,
    OFFLINE_MESH
}

/**
 * Distinct model for an online LiveKit participant.
 */
data class OnlineParticipant(
    val identity: String,
    val displayName: String,
    val isSpeaking: Boolean,
    val isConnected: Boolean = true
)

/**
 * Distinct model for an offline mesh device. Never conflated with a LiveKit participant.
 */
data class OfflineMeshDevice(
    val deviceId: String,
    val displayName: String,
    val reachable: Boolean,
    val direct: Boolean,
    val hopCount: Int,
    val nextHopDeviceId: String?,
    val routeExpiresAt: Long,
    val isSpeaking: Boolean,
    val routeSince: Long = 0L
)

/**
 * UI projection model combining online/offline peer metadata while preserving transport semantics.
 */
data class UnifiedVoicePeer(
    val id: String,
    val name: String,
    val isOnline: Boolean,
    val transport: PeerTransportKind,
    val hopCount: Int,
    val nextHopId: String?,
    val isDirect: Boolean,
    val isRelayed: Boolean,
    val routeAgeMs: Long,
    val isSpeaking: Boolean,
    val routeTtlRemainingMs: Long = 0L
) {
    companion object {
        fun fromOnline(p: OnlineParticipant): UnifiedVoicePeer = UnifiedVoicePeer(
            id = p.identity,
            name = p.displayName,
            isOnline = p.isConnected,
            transport = PeerTransportKind.ONLINE_LIVEKIT,
            hopCount = 0,
            nextHopId = null,
            isDirect = false,
            isRelayed = false,
            routeAgeMs = 0L,
            isSpeaking = p.isSpeaking,
            routeTtlRemainingMs = 0L
        )

        fun fromOffline(device: OfflineMeshDevice, now: Long): UnifiedVoicePeer = UnifiedVoicePeer(
            id = device.deviceId,
            name = device.displayName,
            isOnline = device.reachable,
            transport = PeerTransportKind.OFFLINE_MESH,
            hopCount = device.hopCount,
            nextHopId = device.nextHopDeviceId,
            isDirect = device.direct,
            isRelayed = device.reachable && !device.direct && device.hopCount > 1,
            routeAgeMs = if (device.reachable && device.routeSince > 0L) (now - device.routeSince).coerceAtLeast(0L) else 0L,
            isSpeaking = device.isSpeaking,
            routeTtlRemainingMs = (device.routeExpiresAt - now).coerceAtLeast(0L)
        )

        fun projectMeshGraph(
            graph: MeshGraphSnapshot,
            routes: Map<String, RouteEntry>,
            speakingIds: Set<String>,
            now: Long
        ): List<OfflineMeshDevice> {
            return graph.nodes.values
                .filter { it.deviceId != graph.selfId }
                .sortedBy { it.deviceId }
                .map { node ->
                    val route = routes[node.deviceId]
                    val validRoute = route != null && route.expiresAt > now
                    OfflineMeshDevice(
                        deviceId = node.deviceId,
                        displayName = node.displayName,
                        reachable = validRoute,
                        direct = validRoute && route?.direct == true,
                        hopCount = if (validRoute && route != null) route.hopCount else 0,
                        nextHopDeviceId = if (validRoute && route != null) route.nextHop else null,
                        routeExpiresAt = if (validRoute && route != null) route.expiresAt else 0L,
                        isSpeaking = node.deviceId in speakingIds,
                        routeSince = if (validRoute && route != null) route.since else 0L
                    )
                }
        }
    }
}

