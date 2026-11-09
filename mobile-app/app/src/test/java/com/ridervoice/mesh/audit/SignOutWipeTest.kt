package com.ridervoice.mesh.audit

import android.content.Context
import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.BackupSessionStore
import com.ridervoice.mesh.session.EncryptedPrefsBackupSessionStore
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.session.StoredBackupMeta
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SignOutWipeTest {

    private class FakeBackupStore : BackupSessionStore {
        var stored: StoredBackupMeta? = null
        override val isPersisted: Boolean get() = stored != null
        override fun saveBackup(pairingCode: String, createdAtMs: Long, expiresAtMs: Long) {
            stored = StoredBackupMeta(pairingCode, createdAtMs, expiresAtMs)
        }
        override fun loadBackup(): StoredBackupMeta? = stored
        override fun clearBackup() {
            stored = null
        }
    }

    private class TestSettings : MeshRuntimeSettings {
        private val _flow = MutableStateFlow(MeshSettingsSnapshot(offlineMeshEnabled = false, automaticFallbackEnabled = false, warmStandbyEnabled = false))
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
    fun gateOpen_toggleOff_storedBackup_clearAllOnSignOut_wipesStoreAndHolder() = runTest {
        FeatureFlags.localOverride = true
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)

        val fakeStore = FakeBackupStore()
        val holder = OfflineSessionHolder(backupStore = fakeStore)
        val now = System.currentTimeMillis()
        val session = OfflineSession.fromCode("ABCD1234EFGH5678", now)!!
        holder.setBackup(session)
        assertEquals("ABCD1234EFGH5678", fakeStore.loadBackup()?.pairingCode)

        val coordinatorTouches = AtomicInteger(0)
        val coordinatorLazy = Lazy<VoiceSessionCoordinator> {
            coordinatorTouches.incrementAndGet()
            error("Coordinator should not be touched")
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val vm = MeshSettingsViewModel(
            settings = MeshRuntimeSettings.AllOff,
            coordinator = coordinatorLazy,
            wifiCapability = wifiChecker,
            sessionHolder = Lazy { holder },
            appScope = testScope
        )

        vm.clearAllOnSignOut()
        testScope.advanceUntilIdle()

        assertNull("Backup must be cleared from holder", holder.backup.value)
        assertNull("Backup store must be cleared", fakeStore.loadBackup())
        assertEquals("Coordinator must never be touched during sign-out", 0, coordinatorTouches.get())
    }

    @Test
    fun gateClosed_storedBackup_clearAllOnSignOut_stillWiped() = runTest {
        FeatureFlags.localOverride = false
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)

        val fakeStore = FakeBackupStore()
        val holder = OfflineSessionHolder(backupStore = fakeStore)
        val now = System.currentTimeMillis()
        val session = OfflineSession.fromCode("ABCD1234EFGH5678", now)!!
        holder.setBackup(session)

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val vm = MeshSettingsViewModel(
            settings = MeshRuntimeSettings.AllOff,
            coordinator = Lazy { error("Coordinator should not be touched") },
            wifiCapability = wifiChecker,
            sessionHolder = Lazy { holder },
            appScope = testScope
        )

        vm.clearAllOnSignOut()
        testScope.advanceUntilIdle()

        assertNull("Backup must be cleared even when gate is closed", holder.backup.value)
        assertNull("Backup store must be cleared even when gate is closed", fakeStore.loadBackup())
    }

    @Test
    fun clearAll_doesNotInitializeKeystoreBackedPrefs_butStillDeletesFile() = runTest {
        val mockContext = mockk<Context>(relaxed = true)
        val factoryCounter = AtomicInteger(0)
        val deleteFileCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = EncryptedPrefsBackupSessionStore(
            context = mockContext,
            prefsFactory = {
                factoryCounter.incrementAndGet()
                mockk(relaxed = true)
            },
            deleteStoreFile = {
                deleteFileCalled.set(true)
                true
            }
        )
        val holder = OfflineSessionHolder(backupStore = store)

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val vm = MeshSettingsViewModel(
            settings = MeshRuntimeSettings.AllOff,
            coordinator = Lazy { error("Coordinator should not be touched") },
            wifiCapability = wifiChecker,
            sessionHolder = Lazy { holder },
            appScope = testScope
        )

        vm.clearAllOnSignOut()
        testScope.advanceUntilIdle()

        assertEquals("Prefs factory must have 0 invocations when store was never populated", 0, factoryCounter.get())
        org.junit.Assert.assertTrue("deleteStoreFile must still be called even when prefs not initialized", deleteFileCalled.get())
    }

    @Test
    fun toggleOnToOff_clearsPersistedBackup() = runTest {
        FeatureFlags.localOverride = true
        val settings = TestSettings()
        settings.setOfflineMeshEnabled(true)
        OfflineMeshGate.install(settings)

        val fakeStore = FakeBackupStore()
        val holder = OfflineSessionHolder(backupStore = fakeStore)
        val now = System.currentTimeMillis()
        val session = OfflineSession.fromCode("ABCD1234EFGH5678", now)!!
        holder.setBackup(session)
        assertEquals("ABCD1234EFGH5678", fakeStore.loadBackup()?.pairingCode)

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

        assertNull("Backup must be cleared when turning offline mesh off", fakeStore.loadBackup())
    }

    @Test
    fun coordinatorLazyNeverCalledDuringSignOut() = runTest {
        val coordinatorCounter = AtomicInteger(0)
        val coordinatorLazy = Lazy<VoiceSessionCoordinator> {
            coordinatorCounter.incrementAndGet()
            error("Coordinator should not be touched")
        }
        val holder = OfflineSessionHolder()

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val vm = MeshSettingsViewModel(
            settings = MeshRuntimeSettings.AllOff,
            coordinator = coordinatorLazy,
            wifiCapability = wifiChecker,
            sessionHolder = Lazy { holder },
            appScope = testScope
        )

        vm.clearAllOnSignOut()
        testScope.advanceUntilIdle()

        assertEquals("Coordinator must never be resolved during sign-out", 0, coordinatorCounter.get())
    }
}
