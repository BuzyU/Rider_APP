package com.ridervoice.services

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import com.ridervoice.network.LiveKitManager
import com.ridervoice.state.ActiveRideRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceForegroundServiceSafetyTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class TestVoiceForegroundService : VoiceForegroundService() {
        var updateTypesCalled = false

        override fun updateForegroundServiceTypeIfRunning() {
            updateTypesCalled = true
        }
    }

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

    @Test
    fun typeMask_restartWithoutLocationPermission_startsMicrophoneOnlyService() {
        val service = VoiceForegroundService()
        service.sdkIntProvider = { 34 } // Android 14
        service.permissionChecker = { perm ->
            when (perm) {
                android.Manifest.permission.RECORD_AUDIO -> PackageManager.PERMISSION_GRANTED
                else -> PackageManager.PERMISSION_DENIED
            }
        }
        service.appVisibilityProvider = { true }

        val mask = service.getForegroundServiceTypeMask()
        assertTrue(
            "Mask must contain MICROPHONE type",
            (mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
        )
        assertFalse(
            "Mask must NOT contain LOCATION type when location permission is absent",
            (mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
        )
    }

    @Test
    fun typeMask_whenAppInBackgroundOnAndroid12Plus_excludesLocationEvenIfPermissionGranted() {
        val service = VoiceForegroundService()
        service.sdkIntProvider = { 34 } // Android 14 (API 34 >= 31)
        service.permissionChecker = { PackageManager.PERMISSION_GRANTED }
        // App is not visible (background restart / process revival)
        service.appVisibilityProvider = { false }

        val mask = service.getForegroundServiceTypeMask()
        assertTrue(
            "Mask must contain MICROPHONE type",
            (mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
        )
        assertFalse(
            "Mask must NOT contain LOCATION type when app is not visible on API 31+",
            (mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
        )
    }

    @Test
    fun typeMask_whenAppIsVisible_includesLocationAndMic() {
        val service = VoiceForegroundService()
        service.sdkIntProvider = { 34 } // Android 14
        service.permissionChecker = { PackageManager.PERMISSION_GRANTED }
        service.appVisibilityProvider = { true }

        val mask = service.getForegroundServiceTypeMask()
        assertTrue(
            "Mask must contain MICROPHONE type",
            (mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
        )
        assertTrue(
            "Mask must contain LOCATION type when visible with permission",
            (mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
        )
    }

    @Test
    fun refreshTypes_doesNothing_whenServiceNotRunning() {
        val mockContext = mockk<Context>(relaxed = true)
        VoiceForegroundService.isRunning = false

        VoiceForegroundService.refreshTypes(mockContext)

        verify(exactly = 0) { mockContext.startService(any()) }
        verify(exactly = 0) { mockContext.startForegroundService(any()) }
    }

    @Test
    fun refreshTypes_sendsIntent_whenServiceIsRunning() {
        val mockContext = mockk<Context>(relaxed = true)
        VoiceForegroundService.isRunning = true

        try {
            VoiceForegroundService.refreshTypes(mockContext)
            verify(atLeast = 1) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mockContext.startForegroundService(any())
                } else {
                    mockContext.startService(any())
                }
            }
        } finally {
            VoiceForegroundService.isRunning = false
        }
    }

    @Test
    fun startForegroundException_leadsToStopSelf_andSetsActiveRideRepositoryError() {
        val service = VoiceForegroundService()
        var stopSelfCalled = false
        service.stopSelfAction = { stopSelfCalled = true }

        val mockActiveRide = mockk<ActiveRideRepository>(relaxed = true)
        service.activeRideRepository = mockActiveRide

        // Simulate getForegroundServiceTypeMask throwing SecurityException during type refresh
        service.sdkIntProvider = { throw SecurityException("Location FGS disallowed from bg") }

        service.updateForegroundServiceTypeIfRunning()

        assertTrue(stopSelfCalled)
        verify(exactly = 1) { mockActiveRide.setVoiceServiceError("voice stopped, reopen the app") }
    }

    @Test
    fun nullIntentAfterProcessDeath_withLiveRide_initiatesTokenRefresh() {
        val service = TestVoiceForegroundService()
        var stopSelfCalled = false
        service.stopSelfAction = { stopSelfCalled = true }

        val mockActiveRide = mockk<ActiveRideRepository>(relaxed = true)
        val mockLiveKit = mockk<LiveKitManager>(relaxed = true)
        service.activeRideRepository = mockActiveRide
        service.liveKitManager = mockLiveKit

        val liveRide = ActiveRideRepository.ActiveRide(
            roomId = "room-test-1",
            displayName = "Room 1",
            isHost = false,
            phase = ActiveRideRepository.Phase.LIVE
        )
        every { mockActiveRide.state } returns MutableStateFlow(liveRide)

        val result = service.onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_REDELIVER_INTENT, result)
        assertFalse("Should not call stopSelf immediately when ride is LIVE", stopSelfCalled)
    }
}
