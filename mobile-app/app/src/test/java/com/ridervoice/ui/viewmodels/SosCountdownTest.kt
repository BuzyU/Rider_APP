package com.ridervoice.ui.viewmodels

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ridervoice.models.SosAlertRequest
import com.ridervoice.models.SosAlertResponse
import com.ridervoice.network.ApiService
import com.ridervoice.services.InMemorySosOutbox
import com.ridervoice.ui.screens.EmergencyAlertContent
import com.ridervoice.ui.screens.SosScreen
import com.ridervoice.ui.theme.RiderVoiceTheme
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * WP-171: Automatic SOS safety countdown and double-send prevention tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class SosCountdownTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun advanceSeconds(seconds: Int) {
        repeat(seconds) {
            composeRule.mainClock.advanceTimeBy(1_000L)
            composeRule.waitForIdle()
        }
    }

    @Test
    fun sosScreen_countdownReachesZero_at10Seconds_dispatchesOnce() {
        composeRule.mainClock.autoAdvance = false
        var sendCount = 0

        composeRule.setContent {
            RiderVoiceTheme {
                SosScreen(
                    state = SosState.Idle,
                    onCancelClick = {},
                    onSend = { sendCount++ }
                )
            }
        }
        composeRule.waitForIdle()

        // Visible cancel control must be displayed
        composeRule.onNodeWithText("CANCEL EMERGENCY", substring = true, ignoreCase = true)
            .assertIsDisplayed()

        // At 9s countdown has not fired yet
        advanceSeconds(9)
        assertEquals("SOS must not auto-dispatch before 10s", 0, sendCount)

        // Reaching 10s dispatches exactly once
        advanceSeconds(2)
        assertEquals("SOS must auto-dispatch once when 10s countdown reaches zero", 1, sendCount)

        // Holding after zero never fires a second time
        advanceSeconds(15)
        assertEquals("SOS must never auto-dispatch twice", 1, sendCount)
    }

    @Test
    fun emergencyAlertContent_countdownReachesZero_at15Seconds_dispatchesOnce() {
        composeRule.mainClock.autoAdvance = false
        var dispatchCount = 0

        composeRule.setContent {
            RiderVoiceTheme {
                EmergencyAlertContent(
                    alertType = "CRASH",
                    roomName = "convoy-alpha",
                    emergencyNumber = "112",
                    onCall911 = {},
                    onTriggerDispatch = { _, onResult ->
                        dispatchCount++
                        onResult(true, "alert-srv-1")
                    },
                    onCancelEmergency = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("CANCEL EMERGENCY", substring = true, ignoreCase = true)
            .assertIsDisplayed()

        advanceSeconds(14)
        assertEquals("EmergencyAlertActivity must not auto-dispatch before 15s", 0, dispatchCount)

        advanceSeconds(2)
        assertEquals("EmergencyAlertActivity must auto-dispatch once at 15s", 1, dispatchCount)

        advanceSeconds(15)
        assertEquals("EmergencyAlertActivity must not double-send", 1, dispatchCount)
    }

    @Test
    fun sosScreen_cancelPreventsDispatch() {
        composeRule.mainClock.autoAdvance = false
        var sendCount = 0
        var cancelled = false

        composeRule.setContent {
            RiderVoiceTheme {
                SosScreen(
                    state = SosState.Idle,
                    onCancelClick = { cancelled = true },
                    onSend = { sendCount++ }
                )
            }
        }
        composeRule.waitForIdle()

        advanceSeconds(3)
        composeRule.onNodeWithText("CANCEL EMERGENCY", substring = true, ignoreCase = true)
            .performClick()
        composeRule.waitForIdle()

        assertTrue("Cancel callback must fire", cancelled)

        advanceSeconds(15)
        assertEquals("Cancelled SosScreen countdown must not dispatch", 0, sendCount)
    }

    @Test
    fun emergencyAlertContent_cancelPreventsDispatch() {
        composeRule.mainClock.autoAdvance = false
        var dispatchCount = 0
        var cancelled = false

        composeRule.setContent {
            RiderVoiceTheme {
                EmergencyAlertContent(
                    alertType = "FALL",
                    roomName = "convoy-beta",
                    emergencyNumber = "112",
                    onCall911 = {},
                    onTriggerDispatch = { _, onResult ->
                        dispatchCount++
                        onResult(true, "alert-srv-2")
                    },
                    onCancelEmergency = { _, _ -> cancelled = true }
                )
            }
        }
        composeRule.waitForIdle()

        advanceSeconds(5)
        composeRule.onNodeWithText("CANCEL EMERGENCY", substring = true, ignoreCase = true)
            .performClick()
        composeRule.waitForIdle()

        assertTrue("Cancel emergency callback must fire", cancelled)

        advanceSeconds(20)
        assertEquals("Cancelled EmergencyAlertActivity countdown must not dispatch", 0, dispatchCount)
    }

    @Test
    fun leavingScreen_cancelsCountdownOnBothScreens() {
        composeRule.mainClock.autoAdvance = false
        var showSosScreen by mutableStateOf(true)
        var showEmergencyScreen by mutableStateOf(true)
        var sosSends = 0
        var emergencySends = 0

        composeRule.setContent {
            RiderVoiceTheme {
                if (showSosScreen) {
                    SosScreen(
                        state = SosState.Idle,
                        onCancelClick = {},
                        onSend = { sosSends++ }
                    )
                }
                if (showEmergencyScreen) {
                    EmergencyAlertContent(
                        alertType = "CRASH",
                        roomName = "convoy-leave",
                        emergencyNumber = "112",
                        onCall911 = {},
                        onTriggerDispatch = { _, cb -> emergencySends++; cb(true, "id") },
                        onCancelEmergency = { _, _ -> }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // First verify countdown is actually running before leaving
        composeRule.onNodeWithText("AUTO-SENDING IN 10s", substring = true).assertExists()
        composeRule.onNodeWithText("AUTO-DISPATCH IN 15s", substring = true).assertExists()

        advanceSeconds(3)
        showSosScreen = false
        showEmergencyScreen = false
        composeRule.waitForIdle()

        advanceSeconds(25)
        assertEquals("Leaving SosScreen must cancel countdown", 0, sosSends)
        assertEquals("Leaving EmergencyAlertContent must cancel countdown", 0, emergencySends)
    }

    @Test
    fun processDeathMidCountdown_resumesRemainingTime_andDoesNotDoubleSend() {
        composeRule.mainClock.autoAdvance = false
        val restorationTester = StateRestorationTester(composeRule)
        var sendCount = 0

        restorationTester.setContent {
            RiderVoiceTheme {
                SosScreen(
                    state = SosState.Idle,
                    onCancelClick = {},
                    onSend = { sendCount++ }
                )
            }
        }
        composeRule.waitForIdle()

        // Run 4s of the 10s countdown, then simulate process death & UI state restoration
        advanceSeconds(4)
        assertEquals(0, sendCount)

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        // Must complete after the remaining ~6s (NOT restart a full 10s) and fire only once
        advanceSeconds(7)
        assertEquals("Restored countdown must dispatch once after remaining time", 1, sendCount)

        // A second restoration right after dispatch must never dispatch again
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        advanceSeconds(15)
        assertEquals("Restoring after dispatch must not double-send", 1, sendCount)
    }

    @Test
    fun sosViewModel_duplicateSendAndManualDuringCountdown_reusesIdempotentClientAlertId() = runBlocking {
        val requests = mutableListOf<SosAlertRequest>()
        val api = mockk<ApiService>()
        coEvery { api.sendSosAlert(any()) } answers {
            val req = firstArg<SosAlertRequest>()
            requests.add(req)
            Response.success(SosAlertResponse(sent = true, alertId = "srv-${requests.size}", recipientsCount = 2))
        }

        val vm = SosViewModel(api, { 18.52 to 73.85 }, InMemorySosOutbox())
        vm.sendAlert("room-idemp")
        // Give coroutine time to finish
        kotlinx.coroutines.delay(100)
        // Second invocation (e.g., manual tap race or post-restore trigger on same screen) must not dispatch twice
        vm.sendAlert("room-idemp")
        kotlinx.coroutines.delay(100)

        assertEquals(
            "Single-fire idempotency guard must prevent duplicate SOS API sends for the same incident",
            1,
            requests.size
        )
    }
}
