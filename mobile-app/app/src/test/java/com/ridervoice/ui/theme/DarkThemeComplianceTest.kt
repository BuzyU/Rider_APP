package com.ridervoice.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.navigation.Routes
import com.ridervoice.network.ConnectionState
import com.ridervoice.ui.components.RvBottomNavigationBar
import com.ridervoice.ui.components.TabProfileAvatar
import com.ridervoice.ui.screens.RideReplayScreen
import com.ridervoice.ui.screens.room.RideTopBar
import com.ridervoice.ui.screens.room.TalkControls
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WP-U6 Dark Theme Decision (Option A — Keep & Prove):
 * - Proves every active DarkPalette contrast pair meets WCAG 2.1 AA (>= 4.5:1).
 * - Guards non-frozen UI against hardcoded Color.White / raw un-themed colors that break Dark Mode.
 * - Verifies ThemeState toggling updates semantic tokens and renders key components cleanly in Dark Mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class DarkThemeComplianceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun resetTheme() {
        ThemeState.set(false)
    }

    private fun linearize(channel: Float): Double =
        if (channel <= 0.03928f) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    private fun relativeLuminance(c: Color): Double =
        0.2126 * linearize(c.red) + 0.7152 * linearize(c.green) + 0.0722 * linearize(c.blue)

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    @Test
    fun darkPalette_allActivePairsMeetWcagAA() {
        val pairs = listOf(
            "Dark Ink on Paper" to (DarkPalette.TextPrimary to DarkPalette.Background),
            "Dark Ink on PaperRaised" to (DarkPalette.TextPrimary to DarkPalette.Surface),
            "Dark Ink on PaperSunken" to (DarkPalette.TextPrimary to DarkPalette.SurfaceAlt),
            "Dark InkSecondary on Paper" to (DarkPalette.TextSecondary to DarkPalette.Background),
            "Dark InkSecondary on PaperRaised" to (DarkPalette.TextSecondary to DarkPalette.Surface),
            "Dark InkSecondary on PaperSunken" to (DarkPalette.TextSecondary to DarkPalette.SurfaceAlt),
            "Dark Pop on Paper" to (DarkPalette.AccentAmber to DarkPalette.Background),
            "Dark Pop on PaperRaised" to (DarkPalette.AccentAmber to DarkPalette.Surface),
            "Dark Pop on PopContainer" to (DarkPalette.AccentAmber to DarkPalette.AccentAmberContainer),
            "Dark PaperOnPop on Pop" to (DarkPalette.OnAccent to DarkPalette.AccentAmber),
            "Dark PaperOnPop on CharcoalSurface" to (DarkPalette.OnAccent to DarkPalette.CharcoalAction),
            "Dark Moss on Paper" to (DarkPalette.Moss to DarkPalette.Background),
            "Dark Moss on MossContainer" to (DarkPalette.Moss to DarkPalette.MossContainer),
            "Dark Brick on Paper" to (DarkPalette.EmergencyRed to DarkPalette.Background),
            "Dark Brick on PaperRaised" to (DarkPalette.EmergencyRed to DarkPalette.Surface),
            "Dark Brick on BrickContainer" to (DarkPalette.EmergencyRed to DarkPalette.EmergencyContainer),
            "Dark PaperOnBrick on Brick" to (DarkPalette.OnEmergency to DarkPalette.EmergencyRed)
        )

        val failures = mutableListOf<String>()
        for ((name, pair) in pairs) {
            val ratio = contrastRatio(pair.first, pair.second)
            if (ratio < 4.5) {
                failures += "$name contrast=${"%.2f".format(ratio)} < 4.5:1"
            }
        }
        assertTrue("DarkPalette WCAG AA failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun nonFrozenUiHasNoHardcodedWhiteOrRawSosHexColors() {
        val candidates = listOf(
            File("src/main/java/com/ridervoice"),
            File("app/src/main/java/com/ridervoice"),
            File("mobile-app/app/src/main/java/com/ridervoice")
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: error("Cannot find com/ridervoice root")

        val frozenOrExcluded = setOf(
            "ui/theme/Color.kt",
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioRouteSheet.kt",
            "ui/screens/room/AudioVuMeter.kt",
            "ui/screens/room/DiagnosticsDialog.kt",
            "ui/screens/room/LeaveRideDialog.kt",
            "ui/screens/room/SosAlertBanner.kt"
        )

        val targets = listOf(
            File(root, "MainActivity.kt"),
            File(root, "ui"),
            File(root, "mesh/ui")
        )

        val violations = mutableListOf<String>()
        for (target in targets) {
            val files = if (target.isFile) listOf(target) else target.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            for (file in files) {
                val rel = file.relativeTo(root).path.replace('\\', '/')
                if (rel in frozenOrExcluded) continue
                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                    if (trimmed.contains("Color.White")) {
                        violations += "$rel:${idx + 1} uses hardcoded Color.White (use PaperOnPop / PaperOnBrick / Ink)"
                    }
                    if (trimmed.contains("Color(0xFF1A0808)") || trimmed.contains("Color(0xFF331010)")) {
                        violations += "$rel:${idx + 1} uses raw dark SOS hex instead of semantic Paper/PaperSunken"
                    }
                }
            }
        }

        assertTrue(
            "Found hardcoded colors that break dark/light theme symmetry:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun themeStateToggle_switchesSemanticTokensAndRendersInDarkMode() {
        ThemeState.set(false)
        assertEquals(LightPalette.Background, Paper)
        assertEquals(LightPalette.TextPrimary, Ink)

        ThemeState.set(true)
        assertEquals(DarkPalette.Background, Paper)
        assertEquals(DarkPalette.TextPrimary, Ink)
        assertEquals(DarkPalette.AccentAmber, Pop)
        assertEquals(DarkPalette.OnAccent, PaperOnPop)

        composeRule.setContent {
            RiderVoiceTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabProfileAvatar(userInitial = "R", onClick = {})
                    RideTopBar(
                        rideName = "Night Ride",
                        connectionState = ConnectionState.CONNECTED,
                        activeSpeaker = null,
                        currentUid = "u1",
                        participants = emptyList(),
                        activeDevice = AudioDevice.Speakerphone,
                        routerState = RouterState.ACTIVE,
                        requestedDevice = AudioDevice.Speakerphone,
                        routedInput = RoutedDeviceInfo(typeName = "MIC", productName = "Mic"),
                        routedOutput = RoutedDeviceInfo(typeName = "SPK", productName = "Speaker"),
                        isCollectingDiag = false,
                        diagSampleCount = 0,
                        onAudioRouteClick = {},
                        onDiagnosticsClick = {},
                        onLeaveClick = {},
                        onRescanAudio = {}
                    )
                    TalkControls(
                        isMicEnabled = true,
                        isDeafened = false,
                        isPttPressed = false,
                        isVoxOpen = false,
                        onToggleMic = {},
                        onToggleDeafen = {},
                        onPttPressStart = {},
                        onPttPressEnd = {},
                        onSosClick = {},
                        modifier = Modifier.height(RvTouchTarget.ptt)
                    )
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
        composeRule.onNodeWithTag("tab_profile_avatar").assertIsDisplayed()
        composeRule.onNodeWithTag("ride_topbar_leave_button").assertIsDisplayed()
        composeRule.onNodeWithTag("talk_controls_ptt").assertIsDisplayed()
        composeRule.onNodeWithTag("rv_bottom_nav_bar").assertIsDisplayed()
        composeRule.onNodeWithText("Night Ride").assertIsDisplayed()
    }
}
