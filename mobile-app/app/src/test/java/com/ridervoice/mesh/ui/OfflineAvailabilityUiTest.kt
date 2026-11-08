package com.ridervoice.mesh.ui

import com.ridervoice.mesh.switching.OfflineAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineAvailabilityUiTest {

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


