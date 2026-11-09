package com.ridervoice.mesh.audit

import android.content.Context
import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.EncryptedPrefsBackupSessionStore
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.OfflineAvailability
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.ui.MeshSettingsViewModel
import com.ridervoice.mesh.ui.WifiCapabilityChecker
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ToggleOffWipeOnFreshProcessTest {

    private class TestSettings(enabled: Boolean = true) : MeshRuntimeSettings {
        private val _flow = MutableStateFlow(MeshSettingsSnapshot(offlineMeshEnabled = enabled, automaticFallbackEnabled = false, warmStandbyEnabled = false))
        override val settingsFlow: StateFlow<MeshSettingsSnapshot> = _flow.asStateFlow()
        override val offlineMeshEnabled: Boolean get() = _flow.value.offlineMeshEnabled
        override val automaticFallbackEnabled: Boolean get() = _flow.value.automaticFallbackEnabled
        override val warmStandbyEnabled: Boolean get() = _flow.value.warmStandbyEnabled
        override fun setOfflineMeshEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(offlineMeshEnabled = v)
        }
        override fun setAutomaticFallbackEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(automaticFallbackEnabled = v)
        }
        override fun setWarmStandbyEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(warmStandbyEnabled = v)
        }
    }

    private val wifiChecker = object : WifiCapabilityChecker {
        override fun isWifiHardwareAvailable(): Boolean = true
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        FeatureFlags.localOverride = null
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        FeatureFlags.localOverride = null
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun toggleOffOnFreshProcess_deletesFileWithoutOpeningPrefs() = runTest {
        FeatureFlags.localOverride = true
        val settings = TestSettings(enabled = true)
        OfflineMeshGate.install(settings)

        val mockContext = mockk<Context>(relaxed = true)
        val deleteFileCalls = AtomicInteger(0)
        val factoryCalls = AtomicInteger(0)

        val store = EncryptedPrefsBackupSessionStore(
            context = mockContext,
            prefsFactory = {
                factoryCalls.incrementAndGet()
                mockk(relaxed = true)
            },
            deleteStoreFile = {
                deleteFileCalls.incrementAndGet()
                true
            }
        )
        val holder = OfflineSessionHolder(backupStore = store)

        val mockCoordinator = mockk<VoiceSessionCoordinator>(relaxed = true)
        every { mockCoordinator.offlineAvailability } returns MutableStateFlow(OfflineAvailability.AVAILABLE)

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val vm = MeshSettingsViewModel(
            settings = settings,
            coordinator = Lazy { mockCoordinator },
            wifiCapability = wifiChecker,
            sessionHolder = Lazy { holder },
            appScope = testScope
        )

        vm.setOfflineMeshEnabled(false)
        testScope.advanceUntilIdle()

        assertEquals("deleteStoreFile must be called when toggle turned off", 1, deleteFileCalls.get())
        assertEquals("prefsFactory must never be invoked", 0, factoryCalls.get())
    }
}
