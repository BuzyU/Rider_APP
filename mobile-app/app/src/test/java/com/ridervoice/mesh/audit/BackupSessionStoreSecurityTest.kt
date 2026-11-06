package com.ridervoice.mesh.audit

import android.content.Context
import android.content.SharedPreferences
import com.ridervoice.mesh.session.EncryptedPrefsBackupSessionStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupSessionStoreSecurityTest {

    @Test
    fun noPlaintextFallbackConstantOrReferenceInCodebase() {
        val file = File("src/main/java/com/ridervoice/mesh/session/OfflineSessionHolder.kt")
        val content = if (file.exists()) file.readText() else {
            File("mobile-app/app/src/main/java/com/ridervoice/mesh/session/OfflineSessionHolder.kt").readText()
        }

        assertFalse(
            "OfflineSessionHolder must not have FALLBACK_PREFS_FILE",
            content.contains("FALLBACK_PREFS_FILE")
        )
        assertFalse(
            "OfflineSessionHolder must never fall back to plaintext SharedPreferences",
            content.contains("context.getSharedPreferences(")
        )
    }

    @Test
    fun whenEncryptedPrefsFailsOnCreate_failsClosedToRam_andNeverHitsDisk() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "backup_store_test_" + System.nanoTime())
        tempDir.mkdirs()
        try {
            val mockContext = mockk<Context>(relaxed = true)
            every { mockContext.applicationContext } returns mockContext
            every { mockContext.filesDir } returns tempDir

            // Factory that throws on create
            val store = EncryptedPrefsBackupSessionStore(
                context = mockContext,
                prefsFactory = { throw SecurityException("Keystore corrupted") }
            )

            // Save backup
            store.saveBackup("ABCD1234EFGH5678", 100_000L, 200_000L)

            // Must work in RAM
            val loaded = store.loadBackup()
            assertNotNull("Loaded backup must not be null in RAM fail-closed mode", loaded)
            assertEquals("ABCD1234EFGH5678", loaded?.pairingCode)
            assertEquals(100_000L, loaded?.createdAtMs)
            assertEquals(200_000L, loaded?.expiresAtMs)

            // Assert zero plaintext files created in directory
            val files = tempDir.listFiles() ?: emptyArray()
            assertTrue("No files should be created on disk when encrypted prefs fails", files.isEmpty())

            // Clear backup
            store.clearBackup()
            assertNull("Backup must be cleared from RAM", store.loadBackup())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun whenEncryptedPrefsFailsOnPut_fallsBackToRam() {
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.applicationContext } returns mockContext

        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } throws RuntimeException("Disk full / Keystore crypto error")

        val store = EncryptedPrefsBackupSessionStore(
            context = mockContext,
            prefsFactory = { mockPrefs }
        )

        // Save backup should not throw and should retain in RAM
        store.saveBackup("CODE9999CODE8888", 50_000L, 150_000L)

        val loaded = store.loadBackup()
        assertNotNull(loaded)
        assertEquals("CODE9999CODE8888", loaded?.pairingCode)

        store.clearBackup()
        assertNull(store.loadBackup())
    }
}

