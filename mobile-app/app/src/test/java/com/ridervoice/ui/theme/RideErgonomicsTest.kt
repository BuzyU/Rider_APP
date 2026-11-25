package com.ridervoice.ui.theme

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.ridervoice.diagnostics.upload.DiagnosticUploadManager
import com.ridervoice.security.SecurePreferences
import com.ridervoice.ui.screens.room.PttPerforatedButton
import com.ridervoice.ui.viewmodels.SettingsViewModel
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h720dp", application = Application::class)
class RideErgonomicsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun resetSettings() {
        RideErgonomicsSettings.setHapticFeedbackEnabled(true)
        RideErgonomicsSettings.setKeepScreenOnDuringRide(true)
    }

    @Test
    fun settingsPersistence_defaultsTrue_survivesRestartAndClear_andUpdatesLiveState() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SecurePreferences(ctx)
        assertTrue(prefs.isHapticFeedbackEnabled())
        assertTrue(prefs.isKeepScreenOnDuringRide())

        val uploadMgr = mockk<DiagnosticUploadManager>(relaxed = true)
        val vm = SettingsViewModel(prefs, uploadMgr)
        assertTrue(vm.settingsState.value.hapticFeedbackEnabled)
        assertTrue(vm.settingsState.value.keepScreenOnDuringRide)

        vm.toggleHapticFeedback()
        vm.toggleKeepScreenOnDuringRide()

        assertFalse(vm.settingsState.value.hapticFeedbackEnabled)
        assertFalse(vm.settingsState.value.keepScreenOnDuringRide)
        assertFalse(RideErgonomicsSettings.hapticFeedbackEnabled)
        assertFalse(RideErgonomicsSettings.keepScreenOnDuringRide)

        // Survives fresh SecurePreferences read and logout clear()
        val reopened = SecurePreferences(ctx)
        assertFalse(reopened.isHapticFeedbackEnabled())
        assertFalse(reopened.isKeepScreenOnDuringRide())
        reopened.clear()
        assertFalse(reopened.isHapticFeedbackEnabled())
        assertFalse(reopened.isKeepScreenOnDuringRide())
    }

    @Test
    fun pttButton_usesUpdatedEndCallbackWithoutRestart_andTriggersEndOnCancelAndStop() {
        val events = mutableListOf<String>()
        var onEndCallback by mutableStateOf({ events += "end_old" })
        var showPtt by mutableStateOf(true)
        val fakeLifecycleOwner = object : LifecycleOwner {
            val reg = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = reg
        }
        fakeLifecycleOwner.reg.currentState = Lifecycle.State.RESUMED

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides fakeLifecycleOwner) {
                RiderVoiceTheme {
                    if (showPtt) {
                        PttPerforatedButton(
                            isPressed = false,
                            isVoxOpen = false,
                            onPressStart = { events += "start" },
                            onPressEnd = onEndCallback
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1) Press down, swap callback mid-press, then release -> updated callback fires
        val pttNode = composeRule.onNodeWithTag("talk_controls_ptt")
        pttNode.performTouchInput { down(center) }
        composeRule.waitForIdle()
        assertEquals(listOf("start"), events)

        onEndCallback = { events += "end_updated" }
        composeRule.waitForIdle()

        pttNode.performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(listOf("start", "end_updated"), events)

        // 2) Cancel gesture during press still fires onPressEnd
        events.clear()
        pttNode.performTouchInput { down(center) }
        composeRule.waitForIdle()
        pttNode.performTouchInput { cancel() }
        composeRule.waitForIdle()
        assertEquals(listOf("start", "end_updated"), events)

        // 3) Lifecycle ON_STOP while held triggers onPressEnd
        events.clear()
        pttNode.performTouchInput { down(center) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            fakeLifecycleOwner.reg.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        composeRule.waitForIdle()
        assertEquals(listOf("start", "end_updated"), events)

        // Cleanup without duplicate release
        showPtt = false
        composeRule.waitForIdle()
        assertEquals(listOf("start", "end_updated"), events)
    }

    @Test
    fun pttButton_hapticsRespectsToggle_and_keepScreenOnControlsWindowAndViewFlags() {
        val hapticsFired = mutableListOf<HapticFeedbackType>()
        val fakeHaptic = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                hapticsFired += hapticFeedbackType
            }
        }
        var hostView: View? = null
        var showEffect by mutableStateOf(true)

        composeRule.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalHapticFeedback provides fakeHaptic) {
                RiderVoiceTheme {
                    if (showEffect) {
                        KeepRideScreenOnEffect()
                    }
                    PttPerforatedButton(
                        isPressed = false,
                        isVoxOpen = false,
                        onPressStart = {},
                        onPressEnd = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val view = hostView ?: error("Expected hostView")
        assertTrue("keepScreenOn should be true when enabled", view.keepScreenOn)

        val pttNode = composeRule.onNodeWithTag("talk_controls_ptt")
        pttNode.performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(2, hapticsFired.size)

        // Disable haptics & keep-screen-on live
        hapticsFired.clear()
        RideErgonomicsSettings.setHapticFeedbackEnabled(false)
        RideErgonomicsSettings.setKeepScreenOnDuringRide(false)
        composeRule.waitForIdle()

        assertFalse("keepScreenOn must clear when setting disabled", view.keepScreenOn)
        val activity = view.context as? Activity
        if (activity != null) {
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        pttNode.performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(0, hapticsFired.size)

        // Re-enable keep-screen-on and verify dispose clears it
        RideErgonomicsSettings.setKeepScreenOnDuringRide(true)
        composeRule.waitForIdle()
        assertTrue(view.keepScreenOn)

        showEffect = false
        composeRule.waitForIdle()
        assertFalse("keepScreenOn must clear when KeepRideScreenOnEffect disposes", view.keepScreenOn)
    }
}
