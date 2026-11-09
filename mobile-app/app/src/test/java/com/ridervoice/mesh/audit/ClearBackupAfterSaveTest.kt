package com.ridervoice.mesh.audit

import android.content.Context
import android.content.SharedPreferences
import com.ridervoice.mesh.session.EncryptedPrefsBackupSessionStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ClearBackupAfterSaveTest {

    @Test
    fun openedPrefsPath_saveThenClear_nullBackupAndDeletesFile() {
        val mockContext = mockk<Context>(relaxed = true)
        val inMemoryData = mutableMapOf<String, Any>()
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)

        every { mockPrefs.getString("backup_pairing_code", null) } answers { inMemoryData["backup_pairing_code"] as? String }
        every { mockPrefs.getLong("backup_created_at_ms", 0L) } answers { (inMemoryData["backup_created_at_ms"] as? Long) ?: 0L }
        every { mockPrefs.getLong("backup_expires_at_ms", 0L) } answers { (inMemoryData["backup_expires_at_ms"] as? Long) ?: 0L }
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } answers { inMemoryData[firstArg()] = secondArg<String>(); mockEditor }
        every { mockEditor.putLong(any(), any()) } answers { inMemoryData[firstArg()] = secondArg<Long>(); mockEditor }
        every { mockEditor.remove(any()) } answers { inMemoryData.remove(firstArg()); mockEditor }
        every { mockEditor.commit() } returns true

        val deleteFileCalled = AtomicBoolean(false)
        val store = EncryptedPrefsBackupSessionStore(
            context = mockContext,
            prefsFactory = { mockPrefs },
            deleteStoreFile = {
                deleteFileCalled.set(true)
                inMemoryData.clear()
                true
            }
        )

        store.saveBackup("12345678", 1000L, 5000L)
        assertEquals("12345678", store.loadBackup()?.pairingCode)

        store.clearBackup()
        assertNull("Backup must be null after clearBackup", store.loadBackup())
        assertTrue("deleteStoreFile must be called", deleteFileCalled.get())
    }

    @Test
    fun unopenedPrefsPath_clear_callsDeleteStoreFile() {
        val mockContext = mockk<Context>(relaxed = true)
        val deleteFileCalled = AtomicBoolean(false)

        val store = EncryptedPrefsBackupSessionStore(
            context = mockContext,
            prefsFactory = { mockk(relaxed = true) },
            deleteStoreFile = {
                deleteFileCalled.set(true)
                true
            }
        )

        store.clearBackup()
        assertTrue("deleteStoreFile must be called even when prefs never opened", deleteFileCalled.get())
        assertNull("loadBackup should still return null", store.loadBackup())
    }
}

