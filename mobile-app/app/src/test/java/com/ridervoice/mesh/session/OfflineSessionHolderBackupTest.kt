package com.ridervoice.mesh.session

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSessionHolderBackupTest {

    @Test
    fun provisionFromBackupCode_validAndInvalidCodes() {
        var nowMs = 1_000_000L
        val holder = OfflineSessionHolder(
            backupStore = NoOpBackupSessionStore,
            clock = { nowMs },
            kdfIterations = 1,
            dispatcher = Dispatchers.Unconfined
        )

        assertNull("Initial backup must be null", holder.getBackup())

        // 1. Invalid: Empty string
        val resEmpty = holder.provisionFromBackupCode("")
        assertFalse("Empty code must fail", resEmpty)
        assertNull(holder.getBackup())

        // 2. Invalid: Short code (< 16 chars)
        val resShort = holder.provisionFromBackupCode("ABC123")
        assertFalse("Short code must fail", resShort)
        assertNull(holder.getBackup())

        // 3. Invalid: Illegal characters (not Crockford Base32)
        val resIllegal = holder.provisionFromBackupCode("K7QXM2PA9TDE4WZ!")
        assertFalse("Illegal character code must fail", resIllegal)
        assertNull(holder.getBackup())

        // 4. Valid 16-character Crockford Base32 code
        val validCode = "K7QXM2PA9TDE4WZH"
        val resValid = holder.provisionFromBackupCode(validCode)
        assertTrue("Valid code must succeed", resValid)

        val backup = holder.getBackup()
        assertNotNull("Backup must be non-null after valid provisioning", backup)
        assertFalse("Backup must not be expired", backup!!.isExpired(nowMs))

        // 5. Clear backup removes it
        holder.clearBackup()
        assertNull("Backup must be null after clearBackup", holder.getBackup())
    }
}

