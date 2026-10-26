package com.ridervoice.update

import android.app.Application
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateManagerIntegrityTest {

    @Test
    fun verifyAndPrepareInstall_failsClosed_whenChecksumMissing() {
        val mockContext = mockk<Context>(relaxed = true)
        val mockApp = mockk<Application>(relaxed = true)
        every { mockContext.applicationContext } returns mockApp

        val updateManager = UpdateManager(mockContext)

        val tempFile = File.createTempFile("test_ota_", ".apk")
        tempFile.writeText("fake apk binary payload")
        assertTrue(tempFile.exists())

        val releaseWithoutChecksum = AppReleaseInfo(
            versionName = "0.0.99",
            tagName = "v0.0.99",
            apkFileName = "app-debug.apk",
            downloadUrl = "https://example.com/app.apk",
            apkSizeBytes = tempFile.length(),
            releaseNotes = "Missing checksum",
            publishedAt = "2026-01-01T00:00:00Z",
            sha256Checksum = null
        )

        updateManager.verifyAndPrepareInstall(tempFile, releaseWithoutChecksum)

        val state = updateManager.uiState.value
        assertTrue("Expected UpdateUiState.Error, got $state", state is UpdateUiState.Error)
        val errorMsg = (state as UpdateUiState.Error).message
        assertTrue("Error must explain missing checksum, was: $errorMsg", errorMsg.contains("missing required SHA-256 checksum"))
        assertFalse("Temp file must be deleted on integrity failure", tempFile.exists())
    }

    @Test
    fun verifyAndPrepareInstall_failsClosed_whenChecksumBlank() {
        val mockContext = mockk<Context>(relaxed = true)
        val mockApp = mockk<Application>(relaxed = true)
        every { mockContext.applicationContext } returns mockApp

        val updateManager = UpdateManager(mockContext)

        val tempFile = File.createTempFile("test_ota_blank_", ".apk")
        tempFile.writeText("fake apk binary payload")

        val releaseWithBlankChecksum = AppReleaseInfo(
            versionName = "0.0.99",
            tagName = "v0.0.99",
            apkFileName = "app-debug.apk",
            downloadUrl = "https://example.com/app.apk",
            apkSizeBytes = tempFile.length(),
            releaseNotes = "Blank checksum",
            publishedAt = "2026-01-01T00:00:00Z",
            sha256Checksum = "   "
        )

        updateManager.verifyAndPrepareInstall(tempFile, releaseWithBlankChecksum)

        val state = updateManager.uiState.value
        assertTrue("Expected UpdateUiState.Error, got $state", state is UpdateUiState.Error)
        assertFalse("Temp file must be deleted on integrity failure", tempFile.exists())
    }

    @Test
    fun verifyAndPrepareInstall_failsClosed_whenChecksumMismatched() {
        val mockContext = mockk<Context>(relaxed = true)
        val mockApp = mockk<Application>(relaxed = true)
        every { mockContext.applicationContext } returns mockApp

        val updateManager = UpdateManager(mockContext)

        val tempFile = File.createTempFile("test_ota_mismatch_", ".apk")
        tempFile.writeText("fake apk binary payload")

        val releaseWithWrongChecksum = AppReleaseInfo(
            versionName = "0.0.99",
            tagName = "v0.0.99",
            apkFileName = "app-debug.apk",
            downloadUrl = "https://example.com/app.apk",
            apkSizeBytes = tempFile.length(),
            releaseNotes = "Wrong checksum",
            publishedAt = "2026-01-01T00:00:00Z",
            sha256Checksum = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )

        updateManager.verifyAndPrepareInstall(tempFile, releaseWithWrongChecksum)

        val state = updateManager.uiState.value
        assertTrue("Expected UpdateUiState.Error, got $state", state is UpdateUiState.Error)
        val errorMsg = (state as UpdateUiState.Error).message
        assertTrue("Error must report checksum mismatch, was: $errorMsg", errorMsg.contains("checksum mismatch"))
        assertFalse("Temp file must be deleted on mismatch", tempFile.exists())
    }
}
