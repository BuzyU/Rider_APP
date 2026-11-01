package com.ridervoice.mesh

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMeshGateTest {

    @After
    fun tearDown() {
        FeatureFlags.remoteKillSwitch = false
        FeatureFlags.localOverride = null
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun installedSettingsControlGateWhenNoParameterPassed() {
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = true
            override val warmStandbyEnabled = true
        })
        assertTrue(OfflineMeshGate.isOfflineAllowed(buildFlag = true))
        assertTrue(OfflineMeshGate.isAutomaticFallbackAllowed(buildFlag = true))
        assertTrue(OfflineMeshGate.isWarmStandbyAllowed(buildFlag = true))

        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        assertFalse(OfflineMeshGate.isOfflineAllowed(buildFlag = true))
        assertFalse(OfflineMeshGate.isAutomaticFallbackAllowed(buildFlag = true))
        assertFalse(OfflineMeshGate.isWarmStandbyAllowed(buildFlag = true))
    }

    @Test
    fun buildFlagShipsDisabled() {
        // Assert per-build-type value (debug true in test runner, release false)
        assertEquals(com.ridervoice.BuildConfig.OFFLINE_MESH_BUILD_ENABLED, FeatureFlags.OFFLINE_MESH_ENABLED)
    }

    @Test
    fun remoteKillSwitchAndLocalOverrideControlFlag() {
        try {
            FeatureFlags.remoteKillSwitch = true
            assertFalse(FeatureFlags.OFFLINE_MESH_ENABLED)
            assertFalse(OfflineMeshGate.isBuildGateOpen())

            FeatureFlags.remoteKillSwitch = false

            FeatureFlags.localOverride = true
            assertTrue(FeatureFlags.OFFLINE_MESH_ENABLED)
            FeatureFlags.localOverride = false
            assertFalse(FeatureFlags.OFFLINE_MESH_ENABLED)
        } finally {
            FeatureFlags.remoteKillSwitch = false
            FeatureFlags.localOverride = null
        }
    }

    @Test
    fun gateMatrixUsingProductionDefaultsWithoutParameterOverride() {
        try {
            // Production default: AllOff installed
            OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
            assertFalse(OfflineMeshGate.isOfflineAllowed())
            assertFalse(OfflineMeshGate.isAutomaticFallbackAllowed())
            assertFalse(OfflineMeshGate.isWarmStandbyAllowed())

            // User enables offline mesh only
            OfflineMeshGate.install(object : MeshRuntimeSettings {
                override val offlineMeshEnabled = true
                override val automaticFallbackEnabled = false
                override val warmStandbyEnabled = false
            })
            assertEquals(com.ridervoice.BuildConfig.OFFLINE_MESH_BUILD_ENABLED, OfflineMeshGate.isOfflineAllowed())
            assertFalse(OfflineMeshGate.isAutomaticFallbackAllowed())
            assertFalse(OfflineMeshGate.isWarmStandbyAllowed())

            // User enables all
            OfflineMeshGate.install(object : MeshRuntimeSettings {
                override val offlineMeshEnabled = true
                override val automaticFallbackEnabled = true
                override val warmStandbyEnabled = true
            })
            assertEquals(com.ridervoice.BuildConfig.OFFLINE_MESH_BUILD_ENABLED, OfflineMeshGate.isOfflineAllowed())
            assertEquals(com.ridervoice.BuildConfig.OFFLINE_MESH_BUILD_ENABLED, OfflineMeshGate.isAutomaticFallbackAllowed())
            assertEquals(com.ridervoice.BuildConfig.OFFLINE_MESH_BUILD_ENABLED, OfflineMeshGate.isWarmStandbyAllowed())

            // Remote kill switch active overrides user settings
            FeatureFlags.remoteKillSwitch = true
            assertFalse(OfflineMeshGate.isOfflineAllowed())
            assertFalse(OfflineMeshGate.isAutomaticFallbackAllowed())
            assertFalse(OfflineMeshGate.isWarmStandbyAllowed())
        } finally {
            FeatureFlags.remoteKillSwitch = false
            FeatureFlags.localOverride = null
            OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        }
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

