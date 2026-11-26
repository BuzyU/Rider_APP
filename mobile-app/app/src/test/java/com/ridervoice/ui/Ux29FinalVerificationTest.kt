package com.ridervoice.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ridervoice.navigation.Routes
import com.ridervoice.network.ConnectionState
import com.ridervoice.ui.components.RvBottomNavIconNameKey
import com.ridervoice.ui.components.RvBottomNavigationBar
import com.ridervoice.ui.components.RvStateHost
import com.ridervoice.ui.components.RvTextField
import com.ridervoice.ui.components.RvTextFieldUnfocusedBorderColorKey
import com.ridervoice.ui.components.UiLoadState
import com.ridervoice.ui.screens.room.ConnectionPill
import com.ridervoice.ui.screens.room.TalkControls
import com.ridervoice.ui.theme.DarkPalette
import com.ridervoice.ui.theme.LightPalette
import com.ridervoice.ui.theme.LocalRvHaptics
import com.ridervoice.ui.theme.RideErgonomicsSettings
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.theme.RvHapticEvent
import com.ridervoice.ui.theme.RvHapticsController
import com.ridervoice.ui.theme.RvIcons
import com.ridervoice.ui.theme.ThemeState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 33], qualifiers = "w411dp-h891dp", application = Application::class)
class Ux29FinalVerificationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun resetGlobalState() {
        ThemeState.set(false)
        RideErgonomicsSettings.setHapticFeedbackEnabled(true)
        RideErgonomicsSettings.setKeepScreenOnDuringRide(true)
    }

    @Test
    fun rvStateHost_transitionsAcrossAllStatesOnApi26To33() {
        var state by mutableStateOf<UiLoadState<List<String>>>(UiLoadState.Loading)
        var retries = 0
        var emptyActions = 0

        composeRule.setContent {
            RiderVoiceTheme {
                RvStateHost(state = state) { rows ->
                    Text(rows.joinToString(", "))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_loading").assertIsDisplayed()

        composeRule.runOnUiThread {
            state = UiLoadState.Error(message = "Offline", onRetry = { retries++ })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)

        composeRule.runOnUiThread {
            state = UiLoadState.Empty(
                title = "Empty list",
                body = "Start a convoy",
                actionText = "Create",
                onAction = { emptyActions++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_state_empty").assertIsDisplayed()
        composeRule.onNodeWithText("Create").performClick()
        assertEquals(1, emptyActions)

        composeRule.runOnUiThread {
            state = UiLoadState.Content(listOf("Convoy A", "Convoy B"))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Convoy A, Convoy B").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun rvTextField_restoresSaveableInputAndMaintainsVisibleBorderContrastAcrossThemes() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            RiderVoiceTheme {
                Column {
                    var convoy by rememberSaveable { mutableStateOf("") }
                    RvTextField(
                        value = convoy,
                        onValueChange = { convoy = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ux29_convoy_input")
                    )
                    Button(
                        onClick = { convoy = "Sahyadri" },
                        modifier = Modifier.testTag("ux29_set_convoy_btn")
                    ) {
                        Text("Set convoy")
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ux29_set_convoy_btn").performClick()
        composeRule.waitForIdle()

        val lightBorder = composeRule.onNodeWithTag("ux29_convoy_input").fetchSemanticsNode()
            .config[RvTextFieldUnfocusedBorderColorKey]
        assertEquals(LightPalette.BorderStrong, lightBorder)

        ThemeState.set(true)
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Sahyadri").assertIsDisplayed()
        val darkBorder = composeRule.onNodeWithTag("ux29_convoy_input").fetchSemanticsNode()
            .config[RvTextFieldUnfocusedBorderColorKey]
        assertEquals(DarkPalette.BorderStrong, darkBorder)
    }

    @Test
    fun bottomNavBar_exposesTabRolesAndOutlinedFilledGlyphSwitchAcrossApiLevels() {
        var route by mutableStateOf(Routes.HOME)
        composeRule.setContent {
            RiderVoiceTheme {
                RvBottomNavigationBar(
                    currentRoute = route,
                    onHomeClick = { route = Routes.HOME },
                    onHistoryClick = { route = Routes.RIDE_STATS },
                    onSquadClick = { route = Routes.SQUAD },
                    onSettingsClick = { route = Routes.SETTINGS }
                )
            }
        }
        composeRule.waitForIdle()

        val homeNode = composeRule.onNodeWithTag("bottom_nav_home")
        val settingsNode = composeRule.onNodeWithTag("bottom_nav_settings")
        homeNode.assertIsSelected()
        assertEquals(Role.Tab, homeNode.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertEquals(RvIcons.NavHomeSelected.name, homeNode.fetchSemanticsNode().config[RvBottomNavIconNameKey])
        assertEquals(RvIcons.NavSettings.name, settingsNode.fetchSemanticsNode().config[RvBottomNavIconNameKey])

        settingsNode.performClick()
        composeRule.waitForIdle()

        settingsNode.assertIsSelected()
        assertEquals(RvIcons.NavHome.name, homeNode.fetchSemanticsNode().config[RvBottomNavIconNameKey])
        assertEquals(RvIcons.NavSettingsSelected.name, settingsNode.fetchSemanticsNode().config[RvBottomNavIconNameKey])
    }

    @Test
    fun rvHaptics_gatesWhenDisabledAndDebouncesConnectionTransitionsAcrossApiLevels() {
        val events = mutableListOf<RvHapticEvent>()
        var clockMs = 10_000L
        val controller = RvHapticsController(
            sink = { events += it },
            isEnabled = { RideErgonomicsSettings.hapticFeedbackEnabled },
            clock = { clockMs }
        )
        var connState by mutableStateOf(ConnectionState.CONNECTING)

        composeRule.setContent {
            CompositionLocalProvider(LocalRvHaptics provides controller) {
                RiderVoiceTheme {
                    ConnectionPill(state = connState)
                    TalkControls(
                        isMicEnabled = false,
                        isDeafened = false,
                        isPttPressed = false,
                        isVoxOpen = false,
                        onToggleMic = {},
                        onToggleDeafen = {},
                        onPttPressStart = {},
                        onPttPressEnd = {},
                        onSosClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Enabled: mic toggle + first transition fire; immediate flap within 5_000ms is debounced
        composeRule.onNodeWithTag("talk_controls_mic").performClick()
        composeRule.runOnUiThread { connState = ConnectionState.CONNECTED }
        composeRule.waitForIdle()
        clockMs += 1_500L
        composeRule.runOnUiThread { connState = ConnectionState.RECONNECTING }
        composeRule.waitForIdle()
        assertEquals(listOf(RvHapticEvent.TOGGLE, RvHapticEvent.CONFIRM), events)

        // Advance >= 5_000ms from first connection pulse: next transition fires
        clockMs = 15_000L
        composeRule.runOnUiThread { connState = ConnectionState.FAILED }
        composeRule.waitForIdle()
        assertEquals(listOf(RvHapticEvent.TOGGLE, RvHapticEvent.CONFIRM, RvHapticEvent.WARNING), events)

        // Disabled: zero events
        events.clear()
        RideErgonomicsSettings.setHapticFeedbackEnabled(false)
        clockMs = 25_000L
        composeRule.onNodeWithTag("talk_controls_deafen").performClick()
        composeRule.runOnUiThread { connState = ConnectionState.CONNECTED }
        composeRule.waitForIdle()
        assertTrue(events.isEmpty())
    }
}
