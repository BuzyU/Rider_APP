package com.ridervoice.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMeshGateTest {

    @Test
    fun buildFlagShipsDisabled() {
        assertFalse(FeatureFlags.OFFLINE_MESH_ENABLED)
    }

    @Test
    fun defaultsAreAllOff() {
        assertFalse(OfflineMeshGate.isOfflineAllowed())
        assertFalse(OfflineMeshGate.isAutomaticFallbackAllowed())
        assertFalse(OfflineMeshGate.isWarmStandbyAllowed())
    }

    @Test
    fun offlineRequiresBothBuildFlagAndUserSetting() {
        for (build in listOf(false, true)) {
            for (user in listOf(false, true)) {
                assertEquals(build && user, OfflineMeshGate.isOfflineAllowed(build, user))
            }
        }
    }

    @Test
    fun userSettingAloneNeverEnablesOffline() {
        assertFalse(OfflineMeshGate.isOfflineAllowed(buildFlag = false, offlineMeshEnabled = true))
    }

    @Test
    fun automaticFallbackRequiresAllThreeGates() {
        for (build in listOf(false, true)) for (user in listOf(false, true)) for (auto in listOf(false, true)) {
            assertEquals(
                build && user && auto,
                OfflineMeshGate.isAutomaticFallbackAllowed(build, user, auto)
            )
        }
    }

    @Test
    fun warmStandbyRequiresAllThreeGates() {
        for (build in listOf(false, true)) for (user in listOf(false, true)) for (warm in listOf(false, true)) {
            assertEquals(
                build && user && warm,
                OfflineMeshGate.isWarmStandbyAllowed(build, user, warm)
            )
        }
    }

    @Test
    fun allGatesOnAllowsEverything() {
        assertTrue(OfflineMeshGate.isOfflineAllowed(true, true))
        assertTrue(OfflineMeshGate.isAutomaticFallbackAllowed(true, true, true))
        assertTrue(OfflineMeshGate.isWarmStandbyAllowed(true, true, true))
    }

    private fun assertEquals(expected: Boolean, actual: Boolean) =
        org.junit.Assert.assertEquals(expected, actual)
}

