package com.ridervoice.mesh.continuity

/** NOT IMPLEMENTED - client stubs only, no backend, unreferenced in production (Stage 4 / Q9). */
data class ContinuityLeaseRequest(
    val rideId: String,
    val deviceId: String,
    val durationSec: Long = ContinuityConfig.DEFAULT_LEASE_DURATION_SEC
)

data class ContinuityLeaseResponse(
    val rideId: String,
    val leaseUntilEpochSec: Long,
    val isGranted: Boolean,
    val message: String? = null
)

sealed class ContinuityLeaseResult {
    data class Granted(val leaseUntilEpochSec: Long) : ContinuityLeaseResult()
    data class Denied(val reason: String) : ContinuityLeaseResult()
    data object FeatureDisabled : ContinuityLeaseResult()
    data class NetworkError(val error: Throwable) : ContinuityLeaseResult()
}

