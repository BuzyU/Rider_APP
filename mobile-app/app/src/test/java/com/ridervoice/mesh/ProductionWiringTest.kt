package com.ridervoice.mesh

import android.content.Context
import com.ridervoice.di.MeshModule
import com.ridervoice.mesh.audio.HardwarePttPort
import com.ridervoice.mesh.audio.MeshAudioRouteController
import com.ridervoice.mesh.identity.RiderNameProvider
import com.ridervoice.mesh.link.NetworkLocks
import com.ridervoice.mesh.link.UdpMeshLink
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.switching.Clock
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.state.ActiveRideRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val mockLocks = mockk<NetworkLocks>(relaxed = true)
        val binder: (java.net.DatagramSocket) -> Unit = { socket -> mockLocks.bindSocketToWifi(socket) }
        val link = UdpMeshLink(
            bindPort = MeshConfig.MESH_PORT,
            gateAllowed = { true },
            networkBinder = binder
        )
        val mockSocket = mockk<java.net.DatagramSocket>(relaxed = true)
        binder.invoke(mockSocket)
        verify(exactly = 1) { mockLocks.bindSocketToWifi(mockSocket) }

        val portField = UdpMeshLink::class.java.getDeclaredField("bindPort").apply { isAccessible = true }
        assertEquals(45454, portField.getInt(link))
        assertEquals(45454, MeshConfig.MESH_PORT)
    }

    @Test
    fun meshVoxBridge_receivesHardwarePttPort() {
        val hardwarePttPortExists = runCatching {
            Class.forName("com.ridervoice.mesh.audio.HardwarePttPort")
        }.isSuccess
        assertTrue("HardwarePttPort interface must exist", hardwarePttPortExists)

        val pttParamInVoxBridge = com.ridervoice.mesh.audio.MeshVoxBridge::class.java.constructors.any { c ->
            c.parameterTypes.any { HardwarePttPort::class.java.isAssignableFrom(it) }
        }
        assertTrue("MeshVoxBridge constructor must accept HardwarePttPort", pttParamInVoxBridge)

        val transportMethod = MeshModule::class.java.declaredMethods.find { it.name == "provideOfflineMeshVoiceTransport" }
        assertNotNull("provideOfflineMeshVoiceTransport must exist in MeshModule", transportMethod)
        val hasPttParam = transportMethod!!.parameterTypes.any { HardwarePttPort::class.java.isAssignableFrom(it) }
        assertTrue("provideOfflineMeshVoiceTransport must take HardwarePttPort parameter", hasPttParam)
    }

    @Test
    fun autoSwitchManager_start_doesNothingWhenGateClosed() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        val mockActiveRide = mockk<ActiveRideRepository>(relaxed = true)
        val mockCoordinator = mockk<VoiceSessionCoordinator>(relaxed = true)
        val manager = MeshAutoSwitchManager(
            activeRideRepository = mockActiveRide,
            liveKitManager = mockk(relaxed = true),
            coordinator = mockCoordinator,
            meshTransport = mockk(relaxed = true),
            healthSignalsProvider = mockk(relaxed = true),
            clock = Clock { 0L },
            dispatcher = Dispatchers.Unconfined
        )
        manager.start()
        verify(exactly = 0) { mockCoordinator.setOnlineSession(any()) }
    }

    @Test
    fun nullSecretProvider_yieldsNoSharedSecret_andFallbackNeverStarts() {
        val nullProvider = NullOfflineSecretProvider()
        assertNull(nullProvider.getSessionForOnlineRide("test-ride"))
        assertNull(nullProvider.backupSession.value)
    }

    @Test
    fun meshIdentity_displayNameComesFromRiderNotBuildModelNorConvoyName() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString("user_callsign", any()) } returns "CustomRiderName"
        every { prefs.getString("mesh_identity_display_name", any()) } returns null
        every { prefs.getString("mesh_identity_device_id", any()) } returns "fixed-device-id"

        val riderNameProvider = RiderNameProvider { "CustomRiderName" }
        val repo = MeshModule.provideMeshIdentityRepository(context, riderNameProvider)
        val identity = repo.get()
        assertEquals("CustomRiderName", identity.displayName)
        assertFalse(identity.displayName.contains("Android"))
        assertFalse(identity.displayName.contains("Build"))
    }

    @Test
    fun meshModule_providesHaveNoNullableOrDefaultedParameters() {
        val defaultMethods = MeshModule::class.java.declaredMethods.filter { it.name.contains("\$default") }
        assertTrue("Found @Provides methods with Kotlin default parameters: $defaultMethods", defaultMethods.isEmpty())

        val providesMethods = MeshModule::class.java.declaredMethods.filter {
            it.isAnnotationPresent(dagger.Provides::class.java)
        }
        for (m in providesMethods) {
            for (p in m.parameterTypes) {
                assertNotNull("Parameter type must not be null in ${m.name}", p)
            }
        }
    }
}
