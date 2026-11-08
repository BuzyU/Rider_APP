package com.ridervoice.mesh.ui

import com.ridervoice.mesh.switching.OfflineAvailability

enum class FallbackReason {
    NO_BACKUP_CODE,
    NO_RIDERS_NEARBY,
    HANDOFF_FAILED,
    UNKNOWN
}

fun mapTransitionReasonToFallbackReason(
    lastTransitionReason: String?,
    availability: OfflineAvailability = OfflineAvailability.AVAILABLE
): FallbackReason {
    return when {
        availability == OfflineAvailability.NO_SHARED_SECRET ||
            lastTransitionReason?.contains("NO_SHARED_SECRET", ignoreCase = true) == true ||
            lastTransitionReason?.contains("no backup", ignoreCase = true) == true ->
            FallbackReason.NO_BACKUP_CODE
        lastTransitionReason?.contains("no mesh route", ignoreCase = true) == true ||
            lastTransitionReason?.contains("no riders", ignoreCase = true) == true ->
            FallbackReason.NO_RIDERS_NEARBY
        lastTransitionReason?.contains("failed", ignoreCase = true) == true ||
            lastTransitionReason?.contains("aborted", ignoreCase = true) == true ->
            FallbackReason.HANDOFF_FAILED
        else -> FallbackReason.UNKNOWN
    }
}

