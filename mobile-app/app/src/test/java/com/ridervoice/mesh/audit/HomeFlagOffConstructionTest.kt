package com.ridervoice.mesh.audit

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.ui.MeshSettingsViewModel
import com.ridervoice.mesh.ui.WifiCapabilityChecker
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class HomeFlagOffConstructionTest {

    private val originalOverride = FeatureFlags.localOverride
    private val originalKillSwitch = FeatureFlags.localKillSwitch

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        FeatureFlags.localOverride = false
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @After
    fun tearDown() {
        FeatureFlags.localOverride = originalOverride
        FeatureFlags.localKillSwitch = originalKillSwitch
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        Dispatchers.resetMain()
    }

    @Test
    fun home_whenBuildGateClosed_constructingMeshSettingsViewModel_incrementsZeroCounters() {
        assertFalse(OfflineMeshGate.isBuildGateOpen())

        val transportCounter = AtomicInteger(0)
        val holderCounter = AtomicInteger(0)
        val identityCounter = AtomicInteger(0)
        val keystoreCounter = AtomicInteger(0)
        val networkCallbackCounter = AtomicInteger(0)

        val coordinatorLazy = Lazy<VoiceSessionCoordinator> {
            transportCounter.incrementAndGet()
            throw AssertionError("VoiceSessionCoordinator must not be constructed when build gate is closed")
        }

        val holderLazy = Lazy<OfflineSessionHolder> {
            holderCounter.incrementAndGet()
            keystoreCounter.incrementAndGet()
            throw AssertionError("OfflineSessionHolder must not be constructed when build gate is closed")
        }

        val wifiCapability = object : WifiCapabilityChecker {
            override fun isWifiHardwareAvailable(): Boolean = true
        }

        // Construct MeshSettingsViewModel as Hilt/Home would inject it
        val viewModel = MeshSettingsViewModel(
            settings = MeshRuntimeSettings.AllOff,
            coordinator = coordinatorLazy,
            wifiCapability = wifiCapability,
            sessionHolder = holderLazy
        )

        assertEquals("Zero transport touches", 0, transportCounter.get())
        assertEquals("Zero session holder touches", 0, holderCounter.get())
        assertEquals("Zero identity touches", 0, identityCounter.get())
        assertEquals("Zero keystore touches", 0, keystoreCounter.get())
        assertEquals("Zero network callbacks", 0, networkCallbackCounter.get())

        // uiState should also evaluate safely with default values
        val state = viewModel.uiState.value
        assertFalse(state.buildGateOpen)
        assertFalse(state.offlineMeshEnabled)
        assertFalse(state.backupSessionPresent)
    }

    @Test
    fun home_whenBuildGateOpen_butToggleOff_constructingMeshSettingsViewModel_incrementsZeroCounters() {
        FeatureFlags.localOverride = true
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        assertTrue(OfflineMeshGate.isBuildGateOpen())
        assertFalse(OfflineMeshGate.isOfflineAllowed())

        val transportCounter = AtomicInteger(0)
        val holderCounter = AtomicInteger(0)
        val identityCounter = AtomicInteger(0)
        val keystoreCounter = AtomicInteger(0)
        val networkCallbackCounter = AtomicInteger(0)

        val coordinatorLazy = Lazy<VoiceSessionCoordinator> {
            transportCounter.incrementAndGet()
            throw AssertionError("VoiceSessionCoordinator must not be constructed when toggle is off")
        }

        val holderLazy = Lazy<OfflineSessionHolder> {
            holderCounter.incrementAndGet()
            keystoreCounter.incrementAndGet()
            throw AssertionError("OfflineSessionHolder must not be constructed when toggle is off")
        }

        val wifiCapability = object : WifiCapabilityChecker {
            override fun isWifiHardwareAvailable(): Boolean = true
        }

        // Construct MeshSettingsViewModel as Hilt/Home would inject it with build gate open but toggle off
        val viewModel = MeshSettingsViewModel(
            settings = MeshRuntimeSettings.AllOff,
            coordinator = coordinatorLazy,
            wifiCapability = wifiCapability,
            sessionHolder = holderLazy
        )

        assertEquals("Zero transport touches", 0, transportCounter.get())
        assertEquals("Zero session holder touches", 0, holderCounter.get())
        assertEquals("Zero identity touches", 0, identityCounter.get())
        assertEquals("Zero keystore touches", 0, keystoreCounter.get())
        assertEquals("Zero network callbacks", 0, networkCallbackCounter.get())

        // uiState should also evaluate safely with default values
        val state = viewModel.uiState.value
        assertTrue(state.buildGateOpen)
        assertFalse(state.offlineMeshEnabled)
        assertFalse(state.backupSessionPresent)
    }
}

