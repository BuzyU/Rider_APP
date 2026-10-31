package com.ridervoice.mesh.continuity

/**
 * Feature flag for optional backend ride continuity reservation.
 * Default is FALSE: completely decoupled from standalone offline mesh operations.
 */
object ContinuityConfig {
    const val BACKEND_CONTINUITY_ENABLED: Boolean = false
    const val DEFAULT_LEASE_DURATION_SEC: Long = 1800L // 30 minutes
    const val REFRESH_INTERVAL_SEC: Long = 600L        // 10 minutes
}

