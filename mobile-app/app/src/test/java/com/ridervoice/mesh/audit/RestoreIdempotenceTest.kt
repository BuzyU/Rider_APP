package com.ridervoice.mesh.audit

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.BackupSessionStore
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.session.StoredBackupMeta
import com.ridervoice.mesh.switching.MeshAutoSwitchManager
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RestoreIdempotenceTest {

    private class CountingBackupSessionStore(
        private val meta: StoredBackupMeta?
    ) : BackupSessionStore {
        val loadCount = AtomicInteger(0)
        val clearCount = AtomicInteger(0)
        override val isPersisted: Boolean = true

        override fun loadBackup(): StoredBackupMeta? {
            loadCount.incrementAndGet()
            return meta
        }

        override fun saveBackup(pairingCode: String, createdAtMs: Long, expiresAtMs: Long) {}

        override fun clearBackup() {
            clearCount.incrementAndGet()
        }
    }

    private class FakeRuntimeSettings : MeshRuntimeSettings {
        private val _flow = MutableStateFlow(
            MeshSettingsSnapshot(
                offlineMeshEnabled = true,
                automaticFallbackEnabled = true,
                warmStandbyEnabled = true
            )
        )
        override val settingsFlow = _flow.asStateFlow()
        override val offlineMeshEnabled get() = _flow.value.offlineMeshEnabled
        override val automaticFallbackEnabled get() = _flow.value.automaticFallbackEnabled
        override val warmStandbyEnabled get() = _flow.value.warmStandbyEnabled
        override fun setOfflineMeshEnabled(v: Boolean) {}
        override fun setAutomaticFallbackEnabled(v: Boolean) {}
        override fun setWarmStandbyEnabled(v: Boolean) {}
    }

    @Before
    fun setUp() {
        FeatureFlags.localOverride = true
        OfflineMeshGate.install(FakeRuntimeSettings())
    }

    @After
    fun tearDown() {
        FeatureFlags.localOverride = null
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun startCalledFiveTimes_triggersLoadBackupOnlyOnce() = runBlocking {
        val now = System.currentTimeMillis()
        val meta = StoredBackupMeta(
            pairingCode = "ABCD1234EFGH5678",
            createdAtMs = now,
            expiresAtMs = now + 100_000L
        )
        val store = CountingBackupSessionStore(meta)
        val holder = OfflineSessionHolder(
            backupStore = store,
            clock = { now }
        )

        val autoSwitchManager = MeshAutoSwitchManager(
            activeRideRepository = mockk(relaxed = true),
            liveKitManager = mockk(relaxed = true),
            coordinator = mockk(relaxed = true),
            meshTransport = mockk(relaxed = true),
            healthSignalsProvider = mockk(relaxed = true),
            secretProvider = mockk(relaxed = true),
            sessionHolder = holder
        )

        repeat(5) {
            autoSwitchManager.start()
        }

        // Wait for any restore in holder
        val job = holder.restoreBackupIfAllowed()
        job?.join()

        assertEquals("loadBackup must be called at most once across 5 start() calls", 1, store.loadCount.get())
        assertNotNull("Backup session must be restored", holder.backup.value)
    }

    @Test
    fun restoreBackupIfAllowedCalledRepeatedlyDirectly_isIdempotent() = runBlocking {
        val now = System.currentTimeMillis()
        val meta = StoredBackupMeta(
            pairingCode = "ABCD1234EFGH5678",
            createdAtMs = now,
            expiresAtMs = now + 100_000L
        )
        val store = CountingBackupSessionStore(meta)
        val holder = OfflineSessionHolder(
            backupStore = store,
            clock = { now }
        )

        val jobs = (1..5).map { holder.restoreBackupIfAllowed() }
        jobs.forEach { it?.join() }

        assertEquals("loadBackup must be called only once even when invoked 5 times directly", 1, store.loadCount.get())
    }
}
