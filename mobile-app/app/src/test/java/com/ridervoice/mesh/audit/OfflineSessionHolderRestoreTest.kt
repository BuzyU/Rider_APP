package com.ridervoice.mesh.audit

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.BackupSessionStore
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.session.StoredBackupMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OfflineSessionHolderRestoreTest {

    private class CountingBackupSessionStore : BackupSessionStore {
        val loadCount = AtomicInteger(0)
        val saveCount = AtomicInteger(0)
        val clearCount = AtomicInteger(0)
        override val isPersisted: Boolean = true

        override fun loadBackup(): StoredBackupMeta? {
            loadCount.incrementAndGet()
            return null
        }

        override fun saveBackup(pairingCode: String, createdAtMs: Long, expiresAtMs: Long) {
            saveCount.incrementAndGet()
        }

        override fun clearBackup() {
            clearCount.incrementAndGet()
        }
    }

    private class FakeRuntimeSettings(initial: Boolean = false) : MeshRuntimeSettings {
        private val _flow = MutableStateFlow(
            MeshSettingsSnapshot(
                offlineMeshEnabled = initial,
                automaticFallbackEnabled = true,
                warmStandbyEnabled = true
            )
        )
        override val settingsFlow = _flow.asStateFlow()
        override val offlineMeshEnabled get() = _flow.value.offlineMeshEnabled
        override val automaticFallbackEnabled get() = _flow.value.automaticFallbackEnabled
        override val warmStandbyEnabled get() = _flow.value.warmStandbyEnabled
        override fun setOfflineMeshEnabled(v: Boolean) {
            _flow.value = _flow.value.copy(offlineMeshEnabled = v)
        }
        override fun setAutomaticFallbackEnabled(v: Boolean) {}
        override fun setWarmStandbyEnabled(v: Boolean) {}
    }

    @Before
    fun setUp() {
        FeatureFlags.localOverride = true
    }

    @After
    fun tearDown() {
        FeatureFlags.localOverride = null
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun constructionDoesNotReadDisk() {
        val settings = FakeRuntimeSettings(initial = false)
        OfflineMeshGate.install(settings)
        assertFalse(OfflineMeshGate.isOfflineAllowed())

        val store = CountingBackupSessionStore()
        val holder = OfflineSessionHolder(backupStore = store)

        assertEquals("Construction must not read disk", 0, store.loadCount.get())
    }

    @Test
    fun restoreBackupIfAllowed_withToggleOff_doesNotReadDisk() = runBlocking {
        val settings = FakeRuntimeSettings(initial = false)
        OfflineMeshGate.install(settings)
        assertFalse(OfflineMeshGate.isOfflineAllowed())

        val store = CountingBackupSessionStore()
        val holder = OfflineSessionHolder(backupStore = store)

        val job = holder.restoreBackupIfAllowed()
        job?.join()

        assertEquals("restoreBackupIfAllowed with toggle off must not read disk", 0, store.loadCount.get())
    }

    @Test
    fun restoreBackupIfAllowed_withToggleOn_readsDisk() = runBlocking {
        val settings = FakeRuntimeSettings(initial = true)
        OfflineMeshGate.install(settings)
        assertTrue(OfflineMeshGate.isOfflineAllowed())

        val store = CountingBackupSessionStore()
        val holder = OfflineSessionHolder(backupStore = store)

        val job = holder.restoreBackupIfAllowed()
        job?.join()

        assertEquals("restoreBackupIfAllowed with toggle on must read disk once", 1, store.loadCount.get())
    }
}
