package com.ridervoice.mesh.ui

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.OfflineAvailability
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshSettingsViewModelStateParityTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        FeatureFlags.localOverride = true
        FeatureFlags.localKillSwitch = false
    }

    @After
    fun tearDown() {
        FeatureFlags.localOverride = null
        FeatureFlags.localKillSwitch = false
        Dispatchers.resetMain()
    }

    @Test
    fun hiltAndInternalConstructors_produceIdenticalInitialAndDynamicUiState() = runTest(testDispatcher) {
        val settings = FakeTestMeshRuntimeSettings()
        OfflineMeshGate.install(settings)
        val wifiChecker = object : WifiCapabilityChecker {
            override fun isWifiHardwareAvailable(): Boolean = true
        }

        val sessionHolder = OfflineSessionHolder()
        val availFlow = MutableStateFlow(OfflineAvailability.DISABLED)
        val coordinator = mockk<VoiceSessionCoordinator>(relaxed = true) {
            every { offlineAvailability } returns availFlow
        }

        // 1. Hilt-style construction
        val hiltVm = MeshSettingsViewModel(
            settings = settings,
            coordinator = dagger.Lazy { coordinator },
            wifiCapability = wifiChecker,
            sessionHolder = dagger.Lazy { sessionHolder },
            appScope = this
        )

        // 2. Internal-style construction
        val internalVm = MeshSettingsViewModel(
            availability = { availFlow },
            backup = { sessionHolder.backup },
            holderForWipe = { sessionHolder },
            wifiCapability = wifiChecker,
            settings = settings
        )

        testScheduler.advanceUntilIdle()

        // Initial state parity
        assertEquals("Initial uiState must be identical", hiltVm.uiState.value, internalVm.uiState.value)

        // Dynamic update: toggle mesh on
        settings.setOfflineMeshEnabled(true)
        availFlow.value = OfflineAvailability.AVAILABLE
        hiltVm.uiState.first { it.offlineAvailability == OfflineAvailability.AVAILABLE }
        internalVm.uiState.first { it.offlineAvailability == OfflineAvailability.AVAILABLE }

        assertEquals("Dynamic uiState after enabling mesh must be identical", hiltVm.uiState.value, internalVm.uiState.value)

        // Dynamic update: change availability
        availFlow.value = OfflineAvailability.NO_SHARED_SECRET
        hiltVm.uiState.first { it.offlineAvailability == OfflineAvailability.NO_SHARED_SECRET }
        internalVm.uiState.first { it.offlineAvailability == OfflineAvailability.NO_SHARED_SECRET }

        assertEquals("Dynamic uiState after availability change must be identical", hiltVm.uiState.value, internalVm.uiState.value)
    }
}

