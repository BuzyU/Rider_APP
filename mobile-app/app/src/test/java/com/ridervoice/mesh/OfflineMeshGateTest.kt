package com.ridervoice.mesh

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMeshGateTest {

    @After
    fun tearDown() {
        FeatureFlags.localKillSwitch = false
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
    fun releaseBuildConfigurationGuardsOfflineMeshDisabled() {
        // Assert that the release build configuration explicitly enforces OFFLINE_MESH_BUILD_ENABLED = false.
        val buildGradle = listOf(
            java.io.File("build.gradle"),
            java.io.File("app/build.gradle"),
            java.io.File("../app/build.gradle"),
            java.io.File("mobile-app/app/build.gradle")
        ).firstOrNull { it.exists() }
        assertTrue("build.gradle must be accessible to verify release build configuration", buildGradle != null)
        val content = buildGradle!!.readText()

        val buildTypesIdx = content.indexOf("buildTypes {")
        assertTrue("buildTypes block must exist in build.gradle", buildTypesIdx != -1)
        val releaseIdx = content.indexOf("release {", buildTypesIdx)
        assertTrue("release block must exist in buildTypes", releaseIdx != -1)
        val releaseBlock = content.substring(releaseIdx, content.indexOf("signingConfig", releaseIdx))
        assertTrue(
            "release build type must explicitly configure OFFLINE_MESH_BUILD_ENABLED = false",
            releaseBlock.contains("""buildConfigField "boolean", "OFFLINE_MESH_BUILD_ENABLED", "false"""")
        )

        // Also assert that the generated release BuildConfig (if compiled) has OFFLINE_MESH_BUILD_ENABLED = false
        val generatedReleaseConfig = listOf(
            java.io.File("build/generated/source/buildConfig/release/com/ridervoice/BuildConfig.java"),
            java.io.File("app/build/generated/source/buildConfig/release/com/ridervoice/BuildConfig.java"),
            java.io.File("mobile-app/app/build/generated/source/buildConfig/release/com/ridervoice/BuildConfig.java")
        ).firstOrNull { it.exists() }
        generatedReleaseConfig?.let { file ->
            assertTrue(
                "Generated release BuildConfig.java must have OFFLINE_MESH_BUILD_ENABLED = false",
                file.readText().contains("public static final boolean OFFLINE_MESH_BUILD_ENABLED = false;")
            )
        }
    }

    @Test
    fun localKillSwitchAndLocalOverrideControlFlag() {
        try {
            FeatureFlags.localKillSwitch = true
            assertFalse(FeatureFlags.OFFLINE_MESH_ENABLED)
            assertFalse(OfflineMeshGate.isBuildGateOpen())

            FeatureFlags.localKillSwitch = false

            FeatureFlags.localOverride = true
            assertTrue(FeatureFlags.OFFLINE_MESH_ENABLED)
            FeatureFlags.localOverride = false
            assertFalse(FeatureFlags.OFFLINE_MESH_ENABLED)
        } finally {
            FeatureFlags.localKillSwitch = false
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
            FeatureFlags.localKillSwitch = true
            assertFalse(OfflineMeshGate.isOfflineAllowed())
            assertFalse(OfflineMeshGate.isAutomaticFallbackAllowed())
            assertFalse(OfflineMeshGate.isWarmStandbyAllowed())
        } finally {
            FeatureFlags.localKillSwitch = false
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

