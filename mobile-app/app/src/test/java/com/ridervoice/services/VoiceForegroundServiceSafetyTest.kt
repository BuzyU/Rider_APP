package com.ridervoice.services

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceForegroundServiceSafetyTest {

    @Test
    fun startRefused_withoutMicPermission() {
        val mockContext = mockk<Context>(relaxed = true)
        every {
            mockContext.checkPermission(android.Manifest.permission.RECORD_AUDIO, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        VoiceForegroundService.start(mockContext)

        verify(exactly = 0) { mockContext.startService(any()) }
        verify(exactly = 0) { mockContext.startForegroundService(any()) }
    }

    @Test
    fun startAllowed_withMicPermission() {
        val mockContext = mockk<Context>(relaxed = true)
        every {
            mockContext.checkPermission(android.Manifest.permission.RECORD_AUDIO, any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        VoiceForegroundService.start(mockContext)

        verify(atLeast = 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mockContext.startForegroundService(any())
            } else {
                mockContext.startService(any())
            }
        }
    }

    @Test
    fun stop_doesNotCrash_whenServiceNotRunning() {
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.stopService(any()) } returns false

        // Should complete cleanly without throwing
        VoiceForegroundService.stop(mockContext)
        verify(exactly = 1) { mockContext.stopService(any()) }
    }
}

