package com.ridervoice.mesh.audit

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.BackupSessionStore
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.session.StoredBackupMeta
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class RestoreDispatcherTest {

    private class RecordingBackupSessionStore(
        private val meta: StoredBackupMeta?,
        private val threadRef: AtomicReference<String>
    ) : BackupSessionStore {
        override val isPersisted: Boolean = true

        override fun loadBackup(): StoredBackupMeta? {
            threadRef.set(Thread.currentThread().name)
            return meta
        }

        override fun saveBackup(pairingCode: String, createdAtMs: Long, expiresAtMs: Long) {}
        override fun clearBackup() {}
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
    fun restoreExecutesOnInjectedDispatcher() = runBlocking {
        val now = System.currentTimeMillis()
        val meta = StoredBackupMeta(
            pairingCode = "ABCD1234EFGH5678",
            createdAtMs = now,
            expiresAtMs = now + 100_000L
        )

        val threadName = "restore-dispatcher-test-worker"
        val customExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, threadName)
        }
        val customDispatcher = customExecutor.asCoroutineDispatcher()

        val executedThread = AtomicReference<String>()
        val store = RecordingBackupSessionStore(meta, executedThread)
        val holder = OfflineSessionHolder(
            backupStore = store,
            clock = { now },
            dispatcher = customDispatcher
        )

        try {
            val job = holder.restoreBackupIfAllowed()
            job?.join()

            assertNotNull("loadBackup must have executed", executedThread.get())
            assertTrue(
                "Execution must take place on custom dispatcher thread, was: ${executedThread.get()}",
                executedThread.get().startsWith(threadName)
            )
            assertEquals("Holder backup state must be restored", "ABCD1234EFGH5678", holder.backup.value?.pairingCode)
        } finally {
            customExecutor.shutdown()
        }
    }
}
