package com.ridervoice.mesh.offline

import com.ridervoice.BuildConfig

data class LobbyRiderStatus(
    val riderId: String,
    val status: String?, // Wire status string e.g. "READY", "CONFIGURING", "CHECKING", etc.
    val lastActivityMs: Long = 0L,
    val lastPresenceMs: Long = 0L,
    val isPresent: Boolean = true
)

data class StartRideGateResult(
    val isOpen: Boolean,
    val blockingRidersCount: Int,
    val readyRidersCount: Int,
    val totalRidersCount: Int,
    val reason: String
)

object StartRideGate {

    private val TERMINAL_STATUSES = setOf(
        "READY",
        "OPTED_OUT",
        "UNAVAILABLE",
        "UNAVAILABLE_DEVICE",
        "FAILED",
        "TIMED_OUT"
    )

    fun evaluate(
        hostState: OfflineConfigState,
        roomOfflineMeshEnabled: Boolean,
        acceptedRiders: List<LobbyRiderStatus>,
        allowSolo: Boolean,
        featureEnabled: Boolean = BuildConfig.PER_ROOM_OFFLINE_CONFIG_ENABLED,
        serverNowMs: Long = System.currentTimeMillis()
    ): StartRideGateResult {
        val otherRiders = acceptedRiders
        val totalRiders = otherRiders.size + 1 // including host

        // D14: Kill switch active (feature disabled) -> offline participation disabled, online only, gate is open
        if (!featureEnabled) {
            return StartRideGateResult(
                isOpen = true,
                blockingRidersCount = 0,
                readyRidersCount = totalRiders,
                totalRidersCount = totalRiders,
                reason = "KILL_SWITCH_ACTIVE"
            )
        }

        // Solo ride allowed
        if (allowSolo && otherRiders.isEmpty()) {
            return StartRideGateResult(
                isOpen = true,
                blockingRidersCount = 0,
                readyRidersCount = 1,
                totalRidersCount = 1,
                reason = "SOLO_ALLOWED"
            )
        }

        // Room-level switch turned off by host (D8 / A3) -> unblock start immediately
        if (!roomOfflineMeshEnabled) {
            return StartRideGateResult(
                isOpen = true,
                blockingRidersCount = 0,
                readyRidersCount = totalRiders,
                totalRidersCount = totalRiders,
                reason = "OFFLINE_MESH_DISABLED_BY_HOST"
            )
        }

        // Check host state if host is participating
        val hostBlocking = if (hostState.optOut) {
            false
        } else {
            !hostState.status.isTerminal()
        }

        // Count rider readiness
        var blockingCount = 0
        var readyCount = if (hostState.status == OfflineConfigStatus.READY || hostState.optOut) 1 else 0

        for (rider in otherRiders) {
            val rawStatus = rider.status ?: "CONFIGURING"

            // Staleness check per A2: if inactive for > 90s, treated as TIMED_OUT (terminal, does not block)
            val isTimedOut = (serverNowMs - rider.lastActivityMs) > 90_000L && rider.lastActivityMs > 0L
            val isVanished = !rider.isPresent || (rider.lastPresenceMs > 0L && (serverNowMs - rider.lastPresenceMs) > 30_000L)

            val effectiveTerminal = TERMINAL_STATUSES.contains(rawStatus) || isTimedOut || isVanished

            if (effectiveTerminal) {
                readyCount++
            } else {
                blockingCount++
            }
        }

        if (hostBlocking) {
            blockingCount++
        }

        val isOpen = blockingCount == 0

        val reason = when {
            isOpen -> "ALL_RIDERS_READY"
            hostBlocking && otherRiders.isEmpty() -> "HOST_CONFIGURING"
            else -> "WAITING_FOR_RIDERS"
        }

        return StartRideGateResult(
            isOpen = isOpen,
            blockingRidersCount = blockingCount,
            readyRidersCount = readyCount,
            totalRidersCount = totalRiders,
            reason = reason
        )
    }
}
