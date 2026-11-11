package com.ridervoice.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMeshConfigTest {

    @Test
    fun disabledDefault_hasCorrectProperties() {
        val config = RoomMeshConfig.DISABLED
        assertFalse("Disabled default offlineMeshEnabled must be false", config.offlineMeshEnabled)
        assertFalse("Disabled default automaticFallbackEnabled must be false", config.automaticFallbackEnabled)
        assertFalse("Disabled default warmStandbyEnabled must be false", config.warmStandbyEnabled)
        assertTrue("Disabled default configCompleted must be true", config.configCompleted)
    }

    @Test
    fun customConfig_holdsPropertiesAndCopies() {
        val config = RoomMeshConfig(
            offlineMeshEnabled = true,
            automaticFallbackEnabled = true,
            warmStandbyEnabled = true,
            configCompleted = false
        )
        assertTrue(config.offlineMeshEnabled)
        assertTrue(config.automaticFallbackEnabled)
        assertTrue(config.warmStandbyEnabled)
        assertFalse(config.configCompleted)

        val completed = config.copy(configCompleted = true)
        assertTrue(completed.configCompleted)
        assertEquals(config.offlineMeshEnabled, completed.offlineMeshEnabled)
    }
}

