package com.ridervoice.mesh.audit

import com.ridervoice.mesh.FeatureFlags
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.BackupSessionStore
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.session.StoredBackupMeta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreCompletedOnlyAfterRunTest {

    private class FakeStore(private val meta: StoredBackupMeta?) : BackupSessionStore {
        override val isPersisted: Boolean get() = true
        override fun saveBackup(pairingCode: String, createdAtMs: Long, expiresAtMs: Long) {}
        override fun loadBackup(): StoredBackupMeta? = meta
        override fun clearBackup() {}
    }

    @After
    fun tearDown() {
        FeatureFlags.localOverride = null
        FeatureFlags.localKillSwitch = false
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun restoreBackupIfAllowed_whenGateClosed_doesNotSetRestoreCompleted() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        FeatureFlags.localOverride = true
        FeatureFlags.localKillSwitch = false
        // Install AllOff so OfflineMeshGate.isOfflineAllowed() == false
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)

        val meta = StoredBackupMeta("0123456789ABCDEF", 1000L, 1000L + 3600_000L)
        val store = FakeStore(meta)
        val holder = OfflineSessionHolder(
            backupStore = store,
            clock = { 1000L },
            dispatcher = testDispatcher
        )

        // 1. Calling restoreBackupIfAllowed when gate is closed returns null
        val nullJob = holder.restoreBackupIfAllowed()
        assertNull("restoreBackupIfAllowed must return null when gate is closed", nullJob)

        // Assert restoreCompleted is NOT true
        assertFalse("restoreCompleted must NOT be true when gate is closed", holder.isRestoreCompletedForTest)
        assertNull("Backup must not be restored yet", holder.backup.value)

        // 2. Open the gate
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = false
            override val warmStandbyEnabled = false
        })

        // Calling restoreBackupIfAllowed now must succeed and launch a job (not blocked by premature restoreCompleted)
        val restoreJob = holder.restoreBackupIfAllowed()
        assertNotNull("restoreBackupIfAllowed must return a Job once gate opens", restoreJob)

        testDispatcher.scheduler.advanceUntilIdle()
        restoreJob?.join()

        // Assert restoreCompleted is now true and backup is populated
        assertTrue("restoreCompleted must be true after restore successfully runs", holder.isRestoreCompletedForTest)
        assertNotNull("Backup session must be restored", holder.backup.value)
    }
}

