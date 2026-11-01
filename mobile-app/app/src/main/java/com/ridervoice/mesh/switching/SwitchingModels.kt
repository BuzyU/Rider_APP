package com.ridervoice.mesh.switching

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

/**
 * Health signals monitored exclusively on the LOCAL device.
 * Never switches because a remote device went offline.
 */
data class LocalHealthSignals(
    val isValidatedInternet: Boolean,
    val isLiveKitConnected: Boolean,
    val isLiveKitReconnecting: Boolean,
    val liveKitReconnectAttempts: Int,
    val isHealthProbeSuccessful: Boolean,
    val hasValidMeshRoute: Boolean
) {
    /** True if local online transport is failing. */
    fun isOnlineFailing(): Boolean =
        !isValidatedInternet && !isLiveKitConnected && !isHealthProbeSuccessful

    /** True if local online transport is confirmed healthy. */
    fun isOnlineHealthy(): Boolean =
        isValidatedInternet && isLiveKitConnected && isHealthProbeSuccessful

    /**
     * True if local online transport can be recovered. LiveKit may be disconnected while on mesh,
     * so recovery depends on internet connectivity and the active health probe, not LiveKit connection state.
     */
    fun isOnlineRecoverable(): Boolean =
        isValidatedInternet && isHealthProbeSuccessful
}

/**
 * Anti-flapping configuration parameters.
 */
data class AntiFlappingConfig(
    val failureConfirmationMs: Long = 10_000L,
    val recoveryStabilityMs: Long = 8_000L,
    val minDwellMs: Long = 20_000L,
    val maxTransitionsPerWindow: Int = 3,
    val windowMs: Long = 120_000L
)

