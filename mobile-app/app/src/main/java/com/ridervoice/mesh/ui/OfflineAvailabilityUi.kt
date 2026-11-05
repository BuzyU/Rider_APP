package com.ridervoice.mesh.ui

import com.ridervoice.mesh.switching.OfflineAvailability

enum class AvailabilityUiState {
    BUILD_DISABLED,
    MESH_DISABLED,
    FALLBACK_DISABLED,
    NO_BACKUP_CODE,
    READY
}

enum class FallbackReason {
    NO_BACKUP_CODE,
    NO_RIDERS_NEARBY,
    HANDOFF_FAILED,
    UNKNOWN
}

data class AvailabilityUiHint(
    val state: AvailabilityUiState,
    val showLobbyButton: Boolean,
    val showBackupNotReadyChip: Boolean,
    val subtitleType: SubtitleType
) {
    enum class SubtitleType {
        BUILD_DISABLED,
        MESH_DISABLED,
        FALLBACK_DISABLED,
        PAIR_BACKUP_CODE,
        FALLBACK_ENABLED
    }
}

fun availabilityToUiHint(
    buildGateOpen: Boolean,
    offlineMeshEnabled: Boolean,
    autoFallbackEnabled: Boolean,
    availability: OfflineAvailability
): AvailabilityUiHint {
    return when {
        !buildGateOpen -> AvailabilityUiHint(
            state = AvailabilityUiState.BUILD_DISABLED,
            showLobbyButton = false,
            showBackupNotReadyChip = false,
            subtitleType = AvailabilityUiHint.SubtitleType.BUILD_DISABLED
        )
        !offlineMeshEnabled -> AvailabilityUiHint(
            state = AvailabilityUiState.MESH_DISABLED,
            showLobbyButton = false,
            showBackupNotReadyChip = false,
            subtitleType = AvailabilityUiHint.SubtitleType.MESH_DISABLED
        )
        !autoFallbackEnabled -> AvailabilityUiHint(
            state = AvailabilityUiState.FALLBACK_DISABLED,
            showLobbyButton = true,
            showBackupNotReadyChip = false,
            subtitleType = AvailabilityUiHint.SubtitleType.FALLBACK_DISABLED
        )
        availability == OfflineAvailability.NO_SHARED_SECRET -> AvailabilityUiHint(
            state = AvailabilityUiState.NO_BACKUP_CODE,
            showLobbyButton = true,
            showBackupNotReadyChip = true,
            subtitleType = AvailabilityUiHint.SubtitleType.PAIR_BACKUP_CODE
        )
        else -> AvailabilityUiHint(
            state = AvailabilityUiState.READY,
            showLobbyButton = true,
            showBackupNotReadyChip = false,
            subtitleType = AvailabilityUiHint.SubtitleType.FALLBACK_ENABLED
        )
    }
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

