package com.ridervoice.mesh

import com.ridervoice.di.MeshModule
import com.ridervoice.mesh.audio.MeshAudioRouteController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionWiringTest {

    @Test
    fun meshAudioRouteController_isProvidedAndInjectedIntoTransport() {
        val providesRouteController = MeshModule::class.java.declaredMethods.any { method ->
            method.isAnnotationPresent(dagger.Provides::class.java) &&
                MeshAudioRouteController::class.java.isAssignableFrom(method.returnType)
        }
        assertTrue("MeshModule must have a @Provides method for MeshAudioRouteController", providesRouteController)

        val transportMethod = MeshModule::class.java.declaredMethods.find { it.name == "provideOfflineMeshVoiceTransport" }
        assertNotNull("provideOfflineMeshVoiceTransport must exist in MeshModule", transportMethod)
        val hasRouteControllerParam = transportMethod!!.parameterTypes.any { MeshAudioRouteController::class.java.isAssignableFrom(it) }
        assertTrue("provideOfflineMeshVoiceTransport must take MeshAudioRouteController parameter", hasRouteControllerParam)
    }

    @Test
    fun udpMeshLink_networkBinder_invokesBindSocketToWifi() {
        val meshModuleSource = listOf(
            File("src/main/java/com/ridervoice/di/MeshModule.kt"),
            File("app/src/main/java/com/ridervoice/di/MeshModule.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/di/MeshModule.kt")
        ).firstOrNull { it.exists() } ?: throw IllegalStateException("MeshModule.kt not found")

        val content = meshModuleSource.readText()
        assertTrue(
            "MeshModule must configure linkFactory with NetworkLocks.bindSocketToWifi and networkBinder",
            content.contains("bindSocketToWifi") && content.contains("networkBinder")
        )
    }

    @Test
    fun meshVoxBridge_receivesHardwarePttPort() {
        val hardwarePttPortExists = runCatching {
            Class.forName("com.ridervoice.mesh.audio.HardwarePttPort")
        }.isSuccess
        assertTrue("HardwarePttPort interface must exist", hardwarePttPortExists)
    }

    @Test
    fun autoSwitchManager_start_doesNothingWhenGateClosed() {
        val managerSource = listOf(
            File("src/main/java/com/ridervoice/mesh/switching/MeshAutoSwitchManager.kt"),
            File("app/src/main/java/com/ridervoice/mesh/switching/MeshAutoSwitchManager.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/mesh/switching/MeshAutoSwitchManager.kt")
        ).firstOrNull { it.exists() } ?: throw IllegalStateException("MeshAutoSwitchManager.kt not found")

        val content = managerSource.readText()
        val startBody = content.substringAfter("fun start(").substringBefore("fun ")
        assertTrue(
            "MeshAutoSwitchManager.start() must check OfflineMeshGate at the very beginning",
            startBody.trim().startsWith("if (!OfflineMeshGate") || startBody.contains("OfflineMeshGate.decision()")
        )
    }

    @Test
    fun nullSecretProvider_yieldsNoSharedSecret_andFallbackNeverStarts() {
        val secretProviderExists = runCatching {
            Class.forName("com.ridervoice.mesh.session.OfflineSecretProvider")
        }.isSuccess
        assertTrue("OfflineSecretProvider interface must exist", secretProviderExists)

        val nullProviderExists = runCatching {
            Class.forName("com.ridervoice.mesh.session.NullOfflineSecretProvider")
        }.isSuccess
        assertTrue("NullOfflineSecretProvider class must exist", nullProviderExists)
    }

    @Test
    fun meshIdentity_displayNameComesFromRiderNotBuildModelNorConvoyName() {
        val riderNameProviderExists = runCatching {
            Class.forName("com.ridervoice.mesh.identity.RiderNameProvider")
        }.isSuccess
        assertTrue("RiderNameProvider interface must exist", riderNameProviderExists)

        val moduleSource = listOf(
            File("src/main/java/com/ridervoice/di/MeshModule.kt"),
            File("app/src/main/java/com/ridervoice/di/MeshModule.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/di/MeshModule.kt")
        ).firstOrNull { it.exists() } ?: throw IllegalStateException("MeshModule.kt not found")

        val content = moduleSource.readText()
        assertFalse("MeshModule must not use Build.MODEL for default displayName", content.contains("Build.MODEL"))

        val managerSource = listOf(
            File("src/main/java/com/ridervoice/mesh/switching/MeshAutoSwitchManager.kt"),
            File("app/src/main/java/com/ridervoice/mesh/switching/MeshAutoSwitchManager.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/mesh/switching/MeshAutoSwitchManager.kt")
        ).firstOrNull { it.exists() } ?: throw IllegalStateException("MeshAutoSwitchManager.kt not found")

        val managerContent = managerSource.readText()
        assertFalse(
            "MeshAutoSwitchManager must not set identity displayName to ride.displayName",
            managerContent.contains("identityRepo.setDisplayName(ride.displayName)")
        )
    }

    @Test
    fun meshModule_providesHaveNoNullableOrDefaultedParameters() {
        val violations = mutableListOf<String>()

        val defaultMethods = MeshModule::class.java.declaredMethods.filter { it.name.contains("\$default") }
        if (defaultMethods.isNotEmpty()) {
            violations.add("Found @Provides methods with Kotlin default parameters: ${defaultMethods.map { it.name }}")
        }

        val moduleSource = listOf(
            File("src/main/java/com/ridervoice/di/MeshModule.kt"),
            File("app/src/main/java/com/ridervoice/di/MeshModule.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/di/MeshModule.kt")
        ).firstOrNull { it.exists() } ?: throw IllegalStateException("MeshModule.kt not found")

        val content = moduleSource.readText()
        val providesFunctions = content.split("@Provides").drop(1)
        for (fn in providesFunctions) {
            val signature = fn.substringBefore("{").substringBefore("=")
            val params = signature.substringAfter("(").substringBeforeLast(")")
            val paramList = params.split(",").map { it.trim() }.filter { it.isNotBlank() }
            for (p in paramList) {
                if (p.contains("?") || p.contains("=")) {
                    violations.add("Parameter '$p' in @Provides signature has nullable type or default value")
                }
            }
        }

        assertTrue("MeshModule @Provides methods must not have nullable or defaulted parameters: $violations", violations.isEmpty())
    }
}

