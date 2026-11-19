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
    fun releaseBuildConfigurationEnablesOfflineMeshAndPerRoomConfigByDefault() {
        // WP-153: Assert that release builds ship with OFFLINE_MESH_BUILD_ENABLED and PER_ROOM_OFFLINE_CONFIG_ENABLED = true by default.
        val buildGradle = listOf(
            java.io.File("build.gradle"),
            java.io.File("app/build.gradle"),
            java.io.File("../app/build.gradle"),
            java.io.File("mobile-app/app/build.gradle")
        ).firstOrNull { it.exists() }
        assertTrue("build.gradle must be accessible to verify release build configuration", buildGradle != null)
        val content = buildGradle!!.readText()

        val defaultConfigIdx = content.indexOf("defaultConfig {")
        assertTrue("defaultConfig block must exist in build.gradle", defaultConfigIdx != -1)
        val buildTypesIdx = content.indexOf("buildTypes {")
        assertTrue("buildTypes block must exist in build.gradle", buildTypesIdx != -1)
        val defaultConfigBlock = content.substring(defaultConfigIdx, buildTypesIdx)
        assertTrue(
            "defaultConfig must set OFFLINE_MESH_BUILD_ENABLED to true by default",
            defaultConfigBlock.contains("""buildConfigField "boolean", "OFFLINE_MESH_BUILD_ENABLED", "true"""")
        )
        assertTrue(
            "defaultConfig must default PER_ROOM_OFFLINE_CONFIG_ENABLED to true",
            defaultConfigBlock.contains("PER_ROOM_OFFLINE_CONFIG_ENABLED") &&
                defaultConfigBlock.contains("!project.hasProperty('perRoomOfflineConfigEnabled')")
        )

        val releaseIdx = content.indexOf("release {", buildTypesIdx)
        assertTrue("release block must exist in buildTypes", releaseIdx != -1)
        val releaseBlock = content.substring(releaseIdx, content.indexOf("signingConfig", releaseIdx))
        assertTrue(
            "release build type must default OFFLINE_MESH_BUILD_ENABLED to true unless -PenableOfflineMeshInRelease=false",
            releaseBlock.contains("!project.hasProperty('enableOfflineMeshInRelease')") &&
                releaseBlock.contains("""buildConfigField "boolean", "OFFLINE_MESH_BUILD_ENABLED", enableMeshInRelease ? "true" : "false"""")
        )
        assertTrue(
            "release build type must default PER_ROOM_OFFLINE_CONFIG_ENABLED to true unless -PperRoomOfflineConfigEnabled=false",
            releaseBlock.contains("!project.hasProperty('perRoomOfflineConfigEnabled')") &&
                releaseBlock.contains("""buildConfigField "boolean", "PER_ROOM_OFFLINE_CONFIG_ENABLED", enablePerRoomInRelease ? "true" : "false"""")
        )

        // Also assert that if generated release BuildConfig.java is present, both flags are true
        val generatedReleaseConfig = listOf(
            java.io.File("build/generated/source/buildConfig/release/com/ridervoice/BuildConfig.java"),
            java.io.File("app/build/generated/source/buildConfig/release/com/ridervoice/BuildConfig.java"),
            java.io.File("mobile-app/app/build/generated/source/buildConfig/release/com/ridervoice/BuildConfig.java")
        ).firstOrNull { it.exists() }
        generatedReleaseConfig?.let { file ->
            val generatedText = file.readText()
            assertTrue(
                "Generated release BuildConfig.java must have OFFLINE_MESH_BUILD_ENABLED = true",
                generatedText.contains("public static final boolean OFFLINE_MESH_BUILD_ENABLED = true;")
            )
            assertTrue(
                "Generated release BuildConfig.java must have PER_ROOM_OFFLINE_CONFIG_ENABLED = true",
                generatedText.contains("public static final boolean PER_ROOM_OFFLINE_CONFIG_ENABLED = true;")
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

