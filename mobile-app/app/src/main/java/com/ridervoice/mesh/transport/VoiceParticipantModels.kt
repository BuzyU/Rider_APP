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
    val routeSince: Long = 0L,
    val onlineIdTag: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OfflineMeshDevice) return false
        if (deviceId != other.deviceId) return false
        if (displayName != other.displayName) return false
        if (reachable != other.reachable) return false
        if (direct != other.direct) return false
        if (hopCount != other.hopCount) return false
        if (nextHopDeviceId != other.nextHopDeviceId) return false
        if (routeExpiresAt != other.routeExpiresAt) return false
        if (isSpeaking != other.isSpeaking) return false
        if (routeSince != other.routeSince) return false
        if (onlineIdTag != null) {
            if (other.onlineIdTag == null) return false
            if (!onlineIdTag.contentEquals(other.onlineIdTag)) return false
        } else if (other.onlineIdTag != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + reachable.hashCode()
        result = 31 * result + direct.hashCode()
        result = 31 * result + hopCount
        result = 31 * result + (nextHopDeviceId?.hashCode() ?: 0)
        result = 31 * result + routeExpiresAt.hashCode()
        result = 31 * result + isSpeaking.hashCode()
        result = 31 * result + routeSince.hashCode()
        result = 31 * result + (onlineIdTag?.contentHashCode() ?: 0)
        return result
    }
}

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
    val routeTtlRemainingMs: Long = 0L,
    val onlineIdTag: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnifiedVoicePeer) return false
        if (id != other.id) return false
        if (name != other.name) return false
        if (isOnline != other.isOnline) return false
        if (transport != other.transport) return false
        if (hopCount != other.hopCount) return false
        if (nextHopId != other.nextHopId) return false
        if (isDirect != other.isDirect) return false
        if (isRelayed != other.isRelayed) return false
        if (routeAgeMs != other.routeAgeMs) return false
        if (isSpeaking != other.isSpeaking) return false
        if (routeTtlRemainingMs != other.routeTtlRemainingMs) return false
        if (onlineIdTag != null) {
            if (other.onlineIdTag == null) return false
            if (!onlineIdTag.contentEquals(other.onlineIdTag)) return false
        } else if (other.onlineIdTag != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + isOnline.hashCode()
        result = 31 * result + transport.hashCode()
        result = 31 * result + hopCount
        result = 31 * result + (nextHopId?.hashCode() ?: 0)
        result = 31 * result + isDirect.hashCode()
        result = 31 * result + isRelayed.hashCode()
        result = 31 * result + routeAgeMs.hashCode()
        result = 31 * result + isSpeaking.hashCode()
        result = 31 * result + routeTtlRemainingMs.hashCode()
        result = 31 * result + (onlineIdTag?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        fun fromOnline(p: OnlineParticipant, onlineIdTag: ByteArray? = null): UnifiedVoicePeer = UnifiedVoicePeer(
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
            routeTtlRemainingMs = 0L,
            onlineIdTag = onlineIdTag
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
            routeTtlRemainingMs = (device.routeExpiresAt - now).coerceAtLeast(0L),
            onlineIdTag = device.onlineIdTag
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
                        routeSince = if (validRoute && route != null) route.since else 0L,
                        onlineIdTag = node.onlineIdTag
                    )
                }
        }
    }
}

