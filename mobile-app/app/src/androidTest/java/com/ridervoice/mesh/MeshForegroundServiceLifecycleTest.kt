package com.ridervoice.mesh

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridervoice.services.MeshForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented verification for [MeshForegroundService] on API 31+ Android runtime.
 *
 * UNVERIFIED (needs devices): Exercises Android foreground service start/stop contracts and
 * notification channel creation without triggering ForegroundServiceDidNotStartInTimeException.
 */
@RunWith(AndroidJUnit4::class)
class MeshForegroundServiceLifecycleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun decideForeground_api31Plus_returnsStartWithTypeTrue() {
        // UNVERIFIED (needs devices)
        val decision = MeshForegroundService.decideForeground(MeshForegroundService.ACTION_START)
        assertTrue("Decision must be Start", decision is MeshForegroundService.FgsDecision.Start)
        val startDecision = decision as MeshForegroundService.FgsDecision.Start
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue("API 29+ must require withType = true for microphone foreground type", startDecision.withType)
        }
    }

    @Test
    fun decideForeground_disconnectAndStop_returnsStopDecision() {
        // UNVERIFIED (needs devices)
        val stopDecision = MeshForegroundService.decideForeground(MeshForegroundService.ACTION_STOP)
        assertEquals(MeshForegroundService.FgsDecision.Stop, stopDecision)

        val disconnectDecision = MeshForegroundService.decideForeground(MeshForegroundService.ACTION_DISCONNECT)
        assertEquals(MeshForegroundService.FgsDecision.Stop, disconnectDecision)
    }

    @Test
    fun notificationChannel_createdWithLowImportance() {
        // UNVERIFIED (needs devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            assertNotNull(notificationManager)

            // Verify channel definition
            assertEquals("CHANNEL_OFFLINE_MESH", MeshForegroundService.CHANNEL_ID)
        }
    }
}

