package com.ridervoice.mesh.audit

import android.content.Context
import android.content.SharedPreferences
import com.ridervoice.mesh.session.EncryptedPrefsBackupSessionStore
import com.ridervoice.mesh.session.OfflineSessionHolder
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SignOutWipeOnFreshProcessTest {

    @Test
    fun freshProcess_clearAll_deletesFileWithoutOpeningPrefs() {
        val mockContext = mockk<Context>(relaxed = true)
        val inMemoryMap = mutableMapOf<String, Any>(
            "backup_pairing_code" to "12345678",
            "backup_created_at_ms" to 1000L,
            "backup_expires_at_ms" to 5000L
        )
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)

        val factoryCounter = AtomicInteger(0)
        val deleteFileCalls = AtomicInteger(0)
        var deletedContext: Context? = null

        val store = EncryptedPrefsBackupSessionStore(
            context = mockContext,
            prefsFactory = { _ ->
                factoryCounter.incrementAndGet()
                mockPrefs
            },
            deleteStoreFile = { ctx ->
                deleteFileCalls.incrementAndGet()
                deletedContext = ctx
                inMemoryMap.clear()
                true
            }
        )

        val holder = OfflineSessionHolder(backupStore = store)

        holder.clearAll()

        assertEquals("deleteStoreFile must be called exactly once", 1, deleteFileCalls.get())
        assertEquals("deleteStoreFile must be called with store context", mockContext, deletedContext)
        assertEquals("prefsFactory must never be invoked during fresh process wipe", 0, factoryCounter.get())
        assertTrue("isBackupPersisted must be true after clear", holder.isBackupPersisted)
    }
}

