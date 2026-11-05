package com.ridervoice.mesh.ui

import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeTestMeshRuntimeSettings : MeshRuntimeSettings {
    private val _flow = MutableStateFlow(MeshSettingsSnapshot(false, false, false))
    override val settingsFlow: StateFlow<MeshSettingsSnapshot> = _flow.asStateFlow()

    override val offlineMeshEnabled: Boolean get() = _flow.value.offlineMeshEnabled
    override val automaticFallbackEnabled: Boolean get() = _flow.value.automaticFallbackEnabled
    override val warmStandbyEnabled: Boolean get() = _flow.value.warmStandbyEnabled

    override fun setOfflineMeshEnabled(v: Boolean) {
        val newAuto = if (!v) false else automaticFallbackEnabled
        val newWarm = if (!v) false else warmStandbyEnabled
        _flow.value = MeshSettingsSnapshot(
            offlineMeshEnabled = v,
            automaticFallbackEnabled = newAuto,
            warmStandbyEnabled = newWarm
        )
    }

    override fun setAutomaticFallbackEnabled(v: Boolean) {
        _flow.value = _flow.value.copy(automaticFallbackEnabled = v)
    }

    override fun setWarmStandbyEnabled(v: Boolean) {
        _flow.value = _flow.value.copy(warmStandbyEnabled = v)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MeshSettingsViewModelTest {

    private lateinit var settings: FakeTestMeshRuntimeSettings
    private lateinit var viewModel: MeshSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        settings = FakeTestMeshRuntimeSettings()
        OfflineMeshGate.install(settings)
        viewModel = MeshSettingsViewModel(settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun togglePropagatesToOfflineMeshGateThroughSingleInstance() = runTest {
        assertFalse(OfflineMeshGate.settingsFlow.value.offlineMeshEnabled)
        viewModel.setOfflineMeshEnabled(true)
        assertTrue(OfflineMeshGate.settingsFlow.value.offlineMeshEnabled)
        assertTrue(viewModel.uiState.value.offlineMeshEnabled)
    }

    @Test
    fun disablingMeshClearsTheOtherTwoFlags() = runTest {
        viewModel.setOfflineMeshEnabled(true)
        viewModel.setAutomaticFallbackEnabled(true)
        viewModel.setWarmStandbyEnabled(true)

        assertTrue(viewModel.uiState.value.offlineMeshEnabled)
        assertTrue(viewModel.uiState.value.autoFallback)
        assertTrue(viewModel.uiState.value.warmStandby)

        viewModel.setOfflineMeshEnabled(false)

        assertFalse(viewModel.uiState.value.offlineMeshEnabled)
        assertFalse(viewModel.uiState.value.autoFallback)
        assertFalse(viewModel.uiState.value.warmStandby)
        assertFalse(OfflineMeshGate.settingsFlow.value.automaticFallbackEnabled)
        assertFalse(OfflineMeshGate.settingsFlow.value.warmStandbyEnabled)
    }

    @Test
    fun twoCollectorsSeeTheSameValue() = runTest {
        viewModel.setOfflineMeshEnabled(true)
        viewModel.setAutomaticFallbackEnabled(true)

        val collector1 = viewModel.uiState.first()
        val collector2 = viewModel.uiState.first()

        assertEquals(collector1, collector2)
        assertEquals(true, collector1.offlineMeshEnabled)
        assertEquals(true, collector1.autoFallback)

        viewModel.setWarmStandbyEnabled(true)

        val collector1After = viewModel.uiState.first()
        val collector2After = viewModel.uiState.first()

        assertEquals(collector1After, collector2After)
        assertEquals(true, collector1After.warmStandby)
    }

    @Test
    fun whenWifiHardwareMissing_automaticFallbackCannotBeEnabled() = runTest {
        val noWifiCapability = object : WifiCapabilityChecker {
            override fun isWifiHardwareAvailable(): Boolean = false
        }
        val vm = MeshSettingsViewModel(
            settings = settings,
            availabilityFlow = MutableStateFlow(com.ridervoice.mesh.switching.OfflineAvailability.AVAILABLE),
            wifiCapability = noWifiCapability
        )

        assertFalse(vm.uiState.value.hasWifiHardware)
        vm.setAutomaticFallbackEnabled(true)
        assertFalse(vm.uiState.value.autoFallback)
        assertFalse(settings.automaticFallbackEnabled)
    }

    @Test
    fun whenWifiHardwareAvailable_automaticFallbackCanBeEnabled() = runTest {
        viewModel.setAutomaticFallbackEnabled(true)
        assertTrue(viewModel.uiState.value.autoFallback)
        assertTrue(settings.automaticFallbackEnabled)
    }
}
