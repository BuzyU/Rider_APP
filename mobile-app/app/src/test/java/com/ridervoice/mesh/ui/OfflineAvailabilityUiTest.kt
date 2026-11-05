package com.ridervoice.mesh.ui

import com.ridervoice.mesh.switching.OfflineAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAvailabilityUiTest {

    @Test
    fun buildDisabled_returnsBuildDisabledState() {
        val hint = availabilityToUiHint(
            buildGateOpen = false,
            offlineMeshEnabled = true,
            autoFallbackEnabled = true,
            availability = OfflineAvailability.AVAILABLE
        )
        assertEquals(AvailabilityUiState.BUILD_DISABLED, hint.state)
        assertFalse(hint.showLobbyButton)
        assertFalse(hint.showBackupNotReadyChip)
        assertEquals(AvailabilityUiHint.SubtitleType.BUILD_DISABLED, hint.subtitleType)
    }

    @Test
    fun meshDisabled_returnsMeshDisabledState() {
        val hint = availabilityToUiHint(
            buildGateOpen = true,
            offlineMeshEnabled = false,
            autoFallbackEnabled = true,
            availability = OfflineAvailability.AVAILABLE
        )
        assertEquals(AvailabilityUiState.MESH_DISABLED, hint.state)
        assertFalse(hint.showLobbyButton)
        assertFalse(hint.showBackupNotReadyChip)
        assertEquals(AvailabilityUiHint.SubtitleType.MESH_DISABLED, hint.subtitleType)
    }

    @Test
    fun fallbackDisabled_returnsFallbackDisabledState() {
        val hint = availabilityToUiHint(
            buildGateOpen = true,
            offlineMeshEnabled = true,
            autoFallbackEnabled = false,
            availability = OfflineAvailability.AVAILABLE
        )
        assertEquals(AvailabilityUiState.FALLBACK_DISABLED, hint.state)
        assertTrue(hint.showLobbyButton)
        assertFalse(hint.showBackupNotReadyChip)
        assertEquals(AvailabilityUiHint.SubtitleType.FALLBACK_DISABLED, hint.subtitleType)
    }

    @Test
    fun noSharedSecret_returnsNoBackupCodeState() {
        val hint = availabilityToUiHint(
            buildGateOpen = true,
            offlineMeshEnabled = true,
            autoFallbackEnabled = true,
            availability = OfflineAvailability.NO_SHARED_SECRET
        )
        assertEquals(AvailabilityUiState.NO_BACKUP_CODE, hint.state)
        assertTrue(hint.showLobbyButton)
        assertTrue(hint.showBackupNotReadyChip)
        assertEquals(AvailabilityUiHint.SubtitleType.PAIR_BACKUP_CODE, hint.subtitleType)
    }

    @Test
    fun available_returnsReadyState() {
        val hint = availabilityToUiHint(
            buildGateOpen = true,
            offlineMeshEnabled = true,
            autoFallbackEnabled = true,
            availability = OfflineAvailability.AVAILABLE
        )
        assertEquals(AvailabilityUiState.READY, hint.state)
        assertTrue(hint.showLobbyButton)
        assertFalse(hint.showBackupNotReadyChip)
        assertEquals(AvailabilityUiHint.SubtitleType.FALLBACK_ENABLED, hint.subtitleType)
    }

    @Test
    fun mapTransitionReason_mapsCorrectly() {
        assertEquals(
            FallbackReason.NO_BACKUP_CODE,
            mapTransitionReasonToFallbackReason("Total loss confirmed, NO_SHARED_SECRET")
        )
        assertEquals(
            FallbackReason.NO_BACKUP_CODE,
            mapTransitionReasonToFallbackReason(null, OfflineAvailability.NO_SHARED_SECRET)
        )
        assertEquals(
            FallbackReason.NO_RIDERS_NEARBY,
            mapTransitionReasonToFallbackReason("Total loss confirmed, no mesh route")
        )
        assertEquals(
            FallbackReason.HANDOFF_FAILED,
            mapTransitionReasonToFallbackReason("Handoff to offline failed")
        )
        assertEquals(
            FallbackReason.UNKNOWN,
            mapTransitionReasonToFallbackReason("Something unexpected")
        )
    }
}

