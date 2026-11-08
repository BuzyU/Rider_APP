package com.ridervoice.mesh.audit

import android.content.Context
import android.content.SharedPreferences
import com.ridervoice.mesh.session.EncryptedPrefsBackupSessionStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BackupPersistenceTruthTest {

    @Test
    fun callingIsPersistedOnFreshStore_doesNotInitializePrefs() {
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.applicationContext } returns mockContext

        val factoryInvocationCount = AtomicInteger(0)
        val store = EncryptedPrefsBackupSessionStore(
            context = mockContext,
            prefsFactory = {
                factoryInvocationCount.incrementAndGet()
                mockk(relaxed = true)
            }
        )

        // Reading isPersisted before any write must be optimistic and NOT trigger factory
        assertTrue("Fresh store defaults to isPersisted = true", store.isPersisted)
        assertEquals("Factory must not be invoked by reading isPersisted", 0, factoryInvocationCount.get())
    }

    @Test
    fun whenSaveBackupSucceeds_isPersistedIsTrue() {
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.applicationContext } returns mockContext

        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putLong(any(), any()) } returns mockEditor

        val store = EncryptedPrefsBackupSessionStore(
            context = mockContext,
            prefsFactory = { mockPrefs }
        )

        store.saveBackup("ABCD1234EFGH5678", 1000L, 2000L)
        assertTrue("Successful save must report isPersisted = true", store.isPersisted)
    }

    @Test
    fun whenApplyThrows_isPersistedIsFalse() {
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.applicationContext } returns mockContext

        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putLong(any(), any()) } returns mockEditor
        every { mockEditor.apply() } throws RuntimeException("Disk write failure")

        val store = EncryptedPrefsBackupSessionStore(
            context = mockContext,
            prefsFactory = { mockPrefs }
        )

        store.saveBackup("ABCD1234EFGH5678", 1000L, 2000L)
        assertFalse("Failed apply() must report isPersisted = false", store.isPersisted)
    }
}

