package com.ridervoice.mesh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridervoice.mesh.offline.OfflineConfigCoordinator
import com.ridervoice.mesh.offline.OfflineConfigStatus
import com.ridervoice.permissions.PermissionManager
import com.ridervoice.services.VoiceForegroundService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideRuntimeAndPermissionsInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun ridePermissions_onAndroid12Plus_includesRecordAudioBluetoothConnectAndNotifications() {
        val perms = PermissionManager.ridePermissions.toSet()
        assertTrue("RECORD_AUDIO required for ride voice", perms.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue("ACCESS_FINE_LOCATION required for rider telemetry", perms.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue("ACCESS_COARSE_LOCATION required alongside fine location", perms.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("BLUETOOTH_CONNECT required on API 31+", perms.contains(Manifest.permission.BLUETOOTH_CONNECT))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue("POST_NOTIFICATIONS required on API 33+", perms.contains(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    @Test
    fun voiceForegroundService_packageManagerServiceInfo_declaresMicrophoneLocationAndConnectedDeviceTypes() {
        val component = ComponentName(context, VoiceForegroundService::class.java)
        val serviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(component, 0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val fgsType = serviceInfo.foregroundServiceType
            assertTrue(
                "VoiceForegroundService must declare FOREGROUND_SERVICE_TYPE_MICROPHONE",
                (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
            )
            assertTrue(
                "VoiceForegroundService must declare FOREGROUND_SERVICE_TYPE_LOCATION",
                (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
            )
            assertTrue(
                "VoiceForegroundService must declare FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE",
                (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) != 0
            )
        }
    }

    @Test
    fun offlineConfigCoordinator_realAndroidSocketAndReset_reachesReadyAndClearsCleanly() = runBlocking {
        val coordinator = OfflineConfigCoordinator()
        coordinator.startConfig(
            scope = this,
            backupCode = "K7QXM2PA9TDE4WZH",
            isHostMeshEnabled = true
        )

        val readyState = withTimeout(10_000L) {
            coordinator.state.first { it.status == OfflineConfigStatus.READY }
        }
        assertEquals(OfflineConfigStatus.READY, readyState.status)
        assertTrue(readyState.isReady)

        coordinator.reset()
        assertEquals(OfflineConfigStatus.NOT_STARTED, coordinator.state.value.status)
    }
}
