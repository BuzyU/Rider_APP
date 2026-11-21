package com.ridervoice.localization

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.ridervoice.R
import com.ridervoice.navigation.Routes
import com.ridervoice.ui.components.RvBottomNavigationBar
import com.ridervoice.ui.screens.RideReplayScreen
import com.ridervoice.ui.theme.RiderVoiceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * WP-U2: Screen-level localization verification rendering redesigned UI components under
 * Hindi (hi) and Marathi (mr) locales and asserting localized text (not English) is shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class LocalizedUiRenderingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun localizedContext(lang: String) = RuntimeEnvironment.getApplication().let { base ->
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale(lang)) }
        base.createConfigurationContext(cfg)
    }

    @Test
    fun bottomNavBar_rendersHindiLabels_notEnglish() {
        val hiCtx = localizedContext("hi")
        val hiSettings = hiCtx.getString(R.string.nav_settings)
        val hiHistory = hiCtx.getString(R.string.tab_history)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides hiCtx,
                LocalConfiguration provides hiCtx.resources.configuration
            ) {
                RiderVoiceTheme {
                    RvBottomNavigationBar(
                        currentRoute = Routes.HOME,
                        onHomeClick = {},
                        onHistoryClick = {},
                        onSquadClick = {},
                        onSettingsClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(hiSettings).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(hiHistory).assertIsDisplayed()
        composeRule.onNodeWithText(hiSettings).assertDoesNotExist()
        composeRule.onNodeWithText(hiHistory).assertDoesNotExist()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
        composeRule.onNodeWithText("History").assertDoesNotExist()
    }

    @Test
    fun rideReplayScreen_rendersMarathiLabels_notEnglish() {
        val mrCtx = localizedContext("mr")
        val mrSummary = mrCtx.getString(R.string.summary_ride_summary)
        val mrNoGps = mrCtx.getString(R.string.replay_no_gps_title)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides mrCtx,
                LocalConfiguration provides mrCtx.resources.configuration
            ) {
                RiderVoiceTheme {
                    RideReplayScreen(
                        rideId = "test-1",
                        waypoints = emptyList(),
                        events = emptyList(),
                        onBack = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(mrSummary).assertIsDisplayed()
        composeRule.onNodeWithText(mrNoGps).assertIsDisplayed()
        composeRule.onNodeWithText("Ride Summary").assertDoesNotExist()
        composeRule.onNodeWithText("No GPS track recorded").assertDoesNotExist()
    }
}
