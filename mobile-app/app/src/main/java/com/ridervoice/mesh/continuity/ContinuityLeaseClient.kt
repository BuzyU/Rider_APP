package com.ridervoice.mesh.continuity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ContinuityLeaseClient {
    val activeLeaseUntil: StateFlow<Long?>
    suspend fun requestLease(rideId: String, deviceId: String): ContinuityLeaseResult
    suspend fun releaseLease(rideId: String, deviceId: String): Boolean
}

class DefaultContinuityLeaseClient(
    private val isFeatureEnabled: () -> Boolean = { ContinuityConfig.BACKEND_CONTINUITY_ENABLED },
    private val leaseRequester: (suspend (ContinuityLeaseRequest) -> ContinuityLeaseResponse)? = null,
    private val leaseReleaser: (suspend (String, String) -> Boolean)? = null
) : ContinuityLeaseClient {

    private val _activeLeaseUntil = MutableStateFlow<Long?>(null)
    override val activeLeaseUntil: StateFlow<Long?> = _activeLeaseUntil.asStateFlow()

    override suspend fun requestLease(rideId: String, deviceId: String): ContinuityLeaseResult {
        if (!isFeatureEnabled()) {
            return ContinuityLeaseResult.FeatureDisabled
        }
        val requester = leaseRequester ?: return ContinuityLeaseResult.Denied("No lease requester configured")
        return try {
            val response = requester(ContinuityLeaseRequest(rideId, deviceId))
            if (response.isGranted) {
                _activeLeaseUntil.value = response.leaseUntilEpochSec
                ContinuityLeaseResult.Granted(response.leaseUntilEpochSec)
            } else {
                ContinuityLeaseResult.Denied(response.message ?: "Lease denied by server")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.ridervoice.mesh.MeshLog.w("ContinuityLease", "Lease request failed: ${e.javaClass.simpleName}")
            ContinuityLeaseResult.NetworkError(e)
        }
    }

    override suspend fun releaseLease(rideId: String, deviceId: String): Boolean {
        if (!isFeatureEnabled()) return false
        _activeLeaseUntil.value = null
        val releaser = leaseReleaser ?: return true
        return try {
            releaser(rideId, deviceId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.ridervoice.mesh.MeshLog.w("ContinuityLease", "Lease release failed: ${e.javaClass.simpleName}")
            false
        }
    }
}

