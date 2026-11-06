package com.ridervoice.mesh.switching

import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer

enum class AutoSwitchState {
    ONLINE,
    ONLINE_SUSPECT,
    STARTING_OFFLINE_MESH,
    OFFLINE_ACTIVE,
    ONLINE_RECOVERING,
    HANDING_BACK_TO_LIVEKIT,
    FALLBACK_UNAVAILABLE,
    SESSION_ENDED
}

enum class OfflineAvailability {
    AVAILABLE,
    NO_SHARED_SECRET,
    DISABLED
}

/** Group connectivity status required by Stage 3.5. */
enum class GroupConnectivityStatus {
    ONLINE_ALL,
    MIXED,
    OFFLINE_MESH_ONLY,
    ALONE;

    companion object {
        val ENTIRE_GROUP_ONLINE get() = ONLINE_ALL
        val LOCAL_ONLINE_SOME_PEERS_OFFLINE get() = MIXED
        val LOCAL_OFFLINE_MESH_ACTIVE get() = OFFLINE_MESH_ONLY
    }
}


/**
 * Pure function computing group connectivity status from unified participant list.
 */
fun computeGroupConnectivity(
    isLocalOffline: Boolean,
    peers: List<UnifiedVoicePeer>
): GroupConnectivityStatus {
    // Ghost / left participants (!it.isOnline) are ignored for connectivity calculation
    val activePeers = peers.filter { it.isOnline }
    if (activePeers.isEmpty()) {
        return GroupConnectivityStatus.ALONE
    }
    val onlineCount = activePeers.count { it.transport == PeerTransportKind.ONLINE_LIVEKIT }
    val meshCount = activePeers.count { it.transport == PeerTransportKind.OFFLINE_MESH }

    return when {
        isLocalOffline -> {
            if (onlineCount > 0) {
                GroupConnectivityStatus.MIXED
            } else if (meshCount > 0) {
                GroupConnectivityStatus.OFFLINE_MESH_ONLY
            } else {
                GroupConnectivityStatus.ALONE
            }
        }
        else -> {
            if (meshCount > 0) {
                GroupConnectivityStatus.MIXED
            } else {
                GroupConnectivityStatus.ONLINE_ALL
            }
        }
    }
}

/**
 * Health signals monitored exclusively on the LOCAL device.
 * Never switches because a remote device went offline.
 * W-DEGRADED: High packet loss or high RTT alone must NEVER trigger online failing / suspect.
 */
data class LocalHealthSignals(
    val isValidatedInternet: Boolean,
    val isLiveKitConnected: Boolean,
    val isLiveKitReconnecting: Boolean,
    val liveKitReconnectAttempts: Int,
    val isHealthProbeSuccessful: Boolean,
    val hasValidMeshRoute: Boolean,
    val isMeshTransportAlive: Boolean,
    val offlineAvailable: Boolean = true,
    val lastInterfaceChangeMs: Long = 0L
) {
    /**
     * W-DEGRADED: True ONLY if confirmed total loss of online connectivity.
     * Requires ALL of:
     * - OS network not validated OR no route (!isValidatedInternet)
     * - /health probe failing (!isHealthProbeSuccessful)
     * - LiveKit not connected (!isLiveKitConnected)
     *
     * High loss or high RTT alone => stay online (false).
     */
    fun isTotalLoss(): Boolean =
        !isValidatedInternet && !isLiveKitConnected && !isHealthProbeSuccessful

    /** True if local online transport is failing and eligible for offline fallback. */
    fun isOnlineFailing(): Boolean = isTotalLoss() && offlineAvailable

    /** True if local online transport is confirmed healthy. */
    fun isOnlineHealthy(): Boolean =
        isValidatedInternet && isLiveKitConnected && isHealthProbeSuccessful

    /**
     * True if local online transport can be recovered. LiveKit may be disconnected while on mesh,
     * so recovery depends on internet connectivity and the active health probe, not LiveKit connection state.
     */
    fun isOnlineRecoverable(): Boolean =
        isValidatedInternet && isHealthProbeSuccessful

    fun shouldTriggerBackgroundReconnect(): Boolean =
        isValidatedInternet && isHealthProbeSuccessful && !isLiveKitConnected && !isLiveKitReconnecting
}

/**
 * Anti-flapping configuration parameters.
 */
data class AntiFlappingConfig(
    val failureConfirmationMs: Long = 10_000L,
    val recoveryStabilityMs: Long = 8_000L,
    val minDwellMs: Long = 20_000L,
    val maxTransitionsPerWindow: Int = 3,
    val windowMs: Long = 120_000L,
    val offlineRetryBaseMs: Long = failureConfirmationMs
)
