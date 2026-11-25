package com.ridervoice.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.ridervoice.network.ConnectionState
import com.ridervoice.ui.screens.room.ConnectionPill
import com.ridervoice.ui.screens.room.TalkControls
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
 * WP-U8 Color Token Cleanup, State Visibility, WCAG 2.1 Contrast, and Anti-Slop Guards:
 * - Zero legacy color alias references in non-frozen UI files.
 * - Zero new gradients, blurs, or bitmap drawables beyond the 3 user-accepted exceptions.
 * - Every active color pair in LightPalette & DarkPalette passes WCAG 2.1 AA (>= 4.5:1),
 *   with zero blue/cyan/violet hues.
 * - InkDisabled (2.73:1 on Paper) is never used on active controls or meaningful status indicators.
 * - ConnectionPill (all 5 states) and TalkControls (Mic/Deafen/PTT/VOX) convey state via text/semantics,
 *   not color alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class ColorTokensContrastAndStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun locateMainDir(): File {
        val candidates = listOf(
            File("src/main"),
            File("app/src/main"),
            File("mobile-app/app/src/main")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main")
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

    private fun hueAndSat(c: Color): Pair<Double, Double> {
        val r = c.red.toDouble()
        val g = c.green.toDouble()
        val b = c.blue.toDouble()
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val l = (mx + mn) / 2.0
        val s = if (d == 0.0) 0.0 else d / (1.0 - kotlin.math.abs(2.0 * l - 1.0))
        val h = when {
            d == 0.0 -> 0.0
            mx == r -> 60.0 * (((g - b) / d) % 6.0)
            mx == g -> 60.0 * (((b - r) / d) + 2.0)
            else -> 60.0 * (((r - g) / d) + 4.0)
        }.let { if (it < 0) it + 360.0 else it }
        return h to s
    }

    @Test
    fun nonFrozenUiHasNoLegacyColorAliases() {
        val javaRoot = File(locateMainDir(), "java/com/ridervoice")
        val excluded = setOf(
            "ui/theme/Color.kt",
            "ui/theme/Theme.kt",
            "mesh/ui/MeshColors.kt",
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioRouteSheet.kt",
            "ui/screens/room/AudioVuMeter.kt",
            "ui/screens/room/DiagnosticsDialog.kt",
            "ui/screens/room/LeaveRideDialog.kt",
            "ui/screens/room/SosAlertBanner.kt"
        )
        val legacyRegex = Regex(
            "\\b(NeonOrange|ElectricCyan|NeonViolet|TechGreen|GraphiteBase|DarkSlate|Gunmetal|GraphiteSurface|BorderColor|TextMuted|SuccessGreen|AlertRed|WarningAmber|VhfTeal|SignalGreen|StandbyOchre|AccentViolet|AmberContainer|HazardContainer|TextPrimary|TextSecondary)\\b"
        )
        val targets = listOf(File(javaRoot, "MainActivity.kt"), File(javaRoot, "ui"), File(javaRoot, "mesh/ui"))
        val violations = mutableListOf<String>()
        for (target in targets) {
            val files = if (target.isFile) listOf(target) else target.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            for (f in files) {
                val rel = f.relativeTo(javaRoot).path.replace('\\', '/')
                if (rel in excluded) continue
                f.readLines().forEachIndexed { i, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                    val matches = legacyRegex.findAll(trimmed).map { it.value }.toList()
                    if (matches.isNotEmpty()) {
                        violations += "$rel:${i + 1} uses legacy color alias(es) $matches"
                    }
                }
            }
        }
        assertTrue(
            "Legacy color aliases remain in non-frozen UI files:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun noNewGradientsBlursOrBitmapDrawablesBeyondAcceptedExceptions() {
        val mainDir = locateMainDir()
        val javaRoot = File(mainDir, "java/com/ridervoice")
        val acceptedGradientFiles = setOf(
            "ui/screens/HomeScreen.kt",
            "ui/screens/RideReplayScreen.kt",
            "ui/screens/RoutePlannerScreen.kt"
        )
        val violations = mutableListOf<String>()
        listOf(File(javaRoot, "ui"), File(javaRoot, "mesh/ui")).filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val rel = f.relativeTo(javaRoot).path.replace('\\', '/')
                f.readLines().forEachIndexed { i, line ->
                    val t = line.trim()
                    if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                    if (t.contains("Brush.") && rel !in acceptedGradientFiles) {
                        violations += "$rel:${i + 1} introduces unapproved gradient ($t)"
                    }
                    if (t.contains(".blur(") || t.contains("RenderEffect")) {
                        violations += "$rel:${i + 1} introduces blur/RenderEffect ($t)"
                    }
                }
            }
        }

        val resDir = File(mainDir, "res")
        val bitmaps = resDir.walkTopDown()
            .filter { it.isFile && (it.parentFile?.name?.startsWith("drawable") == true) }
            .filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }
            .map { "${it.parentFile?.name}/${it.name}" }
            .toList()
        val unapprovedBitmaps = bitmaps.filterNot { it == "drawable/hero_create_ride.jpg" }
        if (unapprovedBitmaps.isNotEmpty()) {
            violations += "Unapproved bitmap drawables: $unapprovedBitmaps"
        }

        assertTrue(
            "Unapproved gradient, blur, or bitmap drawable found:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun lightPalette_allActivePairsMeetWcagAA_andZeroBlueOrVioletHues() {
        val pairs = listOf(
            "Light Ink on Paper" to (LightPalette.TextPrimary to LightPalette.Background),
            "Light Ink on PaperRaised" to (LightPalette.TextPrimary to LightPalette.Surface),
            "Light Ink on PaperSunken" to (LightPalette.TextPrimary to LightPalette.SurfaceAlt),
            "Light InkSecondary on Paper" to (LightPalette.TextSecondary to LightPalette.Background),
            "Light InkSecondary on PaperRaised" to (LightPalette.TextSecondary to LightPalette.Surface),
            "Light InkSecondary on PaperSunken" to (LightPalette.TextSecondary to LightPalette.SurfaceAlt),
            "Light Pop on Paper" to (LightPalette.AccentAmber to LightPalette.Background),
            "Light Pop on PaperRaised" to (LightPalette.AccentAmber to LightPalette.Surface),
            "Light Pop on PopContainer" to (LightPalette.AccentAmber to LightPalette.AccentAmberContainer),
            "Light PaperOnPop on Pop" to (LightPalette.OnAccent to LightPalette.AccentAmber),
            "Light PaperOnPop on CharcoalSurface" to (LightPalette.OnAccent to LightPalette.CharcoalAction),
            "Light Moss on Paper" to (LightPalette.Moss to LightPalette.Background),
            "Light Moss on MossContainer" to (LightPalette.Moss to LightPalette.MossContainer),
            "Light Brick on Paper" to (LightPalette.EmergencyRed to LightPalette.Background),
            "Light Brick on PaperRaised" to (LightPalette.EmergencyRed to LightPalette.Surface),
            "Light Brick on BrickContainer" to (LightPalette.EmergencyRed to LightPalette.EmergencyContainer),
            "Light PaperOnBrick on Brick" to (LightPalette.OnEmergency to LightPalette.EmergencyRed),
            "Light StatusOk on Paper" to (LightPalette.StatusOk to LightPalette.Background),
            "Light StatusOk on PaperRaised" to (LightPalette.StatusOk to LightPalette.Surface),
            "Light StatusOk on PaperSunken" to (LightPalette.StatusOk to LightPalette.SurfaceAlt),
            "Light StatusOk on StatusOkContainer" to (LightPalette.StatusOk to LightPalette.StatusOkContainer),
            "Light StatusWarn on Paper" to (LightPalette.StatusWarn to LightPalette.Background),
            "Light StatusWarn on PaperRaised" to (LightPalette.StatusWarn to LightPalette.Surface),
            "Light StatusWarn on PaperSunken" to (LightPalette.StatusWarn to LightPalette.SurfaceAlt),
            "Light StatusWarn on StatusWarnContainer" to (LightPalette.StatusWarn to LightPalette.StatusWarnContainer)
        )
        val failures = mutableListOf<String>()
        for ((name, pair) in pairs) {
            val ratio = contrastRatio(pair.first, pair.second)
            if (ratio < 4.5) {
                failures += "$name contrast=${"%.2f".format(ratio)} < 4.5:1"
            }
        }

        val allPaletteColors = listOf(
            "Light.Background" to LightPalette.Background,
            "Light.Surface" to LightPalette.Surface,
            "Light.SurfaceAlt" to LightPalette.SurfaceAlt,
            "Light.Border" to LightPalette.Border,
            "Light.TextPrimary" to LightPalette.TextPrimary,
            "Light.TextSecondary" to LightPalette.TextSecondary,
            "Light.AccentAmber" to LightPalette.AccentAmber,
            "Light.AccentAmberContainer" to LightPalette.AccentAmberContainer,
            "Light.Moss" to LightPalette.Moss,
            "Light.MossContainer" to LightPalette.MossContainer,
            "Light.EmergencyRed" to LightPalette.EmergencyRed,
            "Light.EmergencyContainer" to LightPalette.EmergencyContainer,
            "Dark.Background" to DarkPalette.Background,
            "Dark.Surface" to DarkPalette.Surface,
            "Dark.SurfaceAlt" to DarkPalette.SurfaceAlt,
            "Dark.AccentAmber" to DarkPalette.AccentAmber,
            "Dark.Moss" to DarkPalette.Moss,
            "Dark.EmergencyRed" to DarkPalette.EmergencyRed,
            "Light.StatusOk" to LightPalette.StatusOk,
            "Light.StatusWarn" to LightPalette.StatusWarn,
            "Light.StatusWarnContainer" to LightPalette.StatusWarnContainer,
            "Dark.StatusOk" to DarkPalette.StatusOk,
            "Dark.StatusWarn" to DarkPalette.StatusWarn,
            "Dark.StatusWarnContainer" to DarkPalette.StatusWarnContainer
        )
        for ((name, color) in allPaletteColors) {
            val (h, s) = hueAndSat(color)
            if (s > 0.15 && h in 170.0..310.0) {
                failures += "$name has banned blue/cyan/violet hue (H=${"%.1f".format(h)}, S=${"%.2f".format(s)})"
            }
        }

        assertTrue("Palette WCAG/hue violations:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun inkDisabled_isNotUsedOnActiveControlsOrStatusIndicators() {
        val javaRoot = File(locateMainDir(), "java/com/ridervoice")
        val checkedFiles = listOf(
            "ui/components/BottomNavShell.kt",
            "ui/screens/HomeScreen.kt"
        )
        val violations = mutableListOf<String>()
        for (rel in checkedFiles) {
            val f = File(javaRoot, rel)
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("InkDisabled")) {
                    violations += "$rel:${i + 1} uses InkDisabled (2.73:1) on active/meaningful element: ${line.trim()}"
                }
            }
        }
        assertTrue("InkDisabled used on active/meaningful elements:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun connectionPillAndTalkControls_conveyAllStatesWithoutColorAlone() {
        val observedLabels = mutableSetOf<String>()
        var currentState by mutableStateOf(ConnectionState.CONNECTED)
        var micOn by mutableStateOf(true)
        var deafened by mutableStateOf(false)
        var pttPressed by mutableStateOf(false)
        var voxOpen by mutableStateOf(false)

        composeRule.setContent {
            RiderVoiceTheme {
                Column {
                    ConnectionPill(state = currentState)
                    TalkControls(
                        isMicEnabled = micOn,
                        isDeafened = deafened,
                        isPttPressed = pttPressed,
                        isVoxOpen = voxOpen,
                        onToggleMic = {},
                        onToggleDeafen = {},
                        onPttPressStart = {},
                        onPttPressEnd = {},
                        onSosClick = {},
                        modifier = Modifier.height(RvTouchTarget.ptt)
                    )
                }
            }
        }

        for (state in ConnectionState.values()) {
            currentState = state
            composeRule.waitForIdle()
            val expectedText = when (state) {
                ConnectionState.CONNECTED -> "CONNECTED"
                ConnectionState.CONNECTING -> "CONNECTING"
                ConnectionState.RECONNECTING -> "RECONNECTING"
                ConnectionState.FAILED -> "NO SIGNAL"
                ConnectionState.DISCONNECTED -> "OFFLINE"
            }
            composeRule.onNodeWithText(expectedText).assertIsDisplayed()
            observedLabels += expectedText
        }
        assertEquals("All 5 ConnectionState values must have distinct non-color text labels", 5, observedLabels.size)

        // Verify Mic ON vs OFF distinct text + contentDescription
        val micOnLabel = composeRule.onNodeWithTag("talk_controls_mic_label").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("")
        micOn = false
        composeRule.waitForIdle()
        val micOffLabel = composeRule.onNodeWithTag("talk_controls_mic_label").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("")
        assertTrue("Mic ON ($micOnLabel) and OFF ($micOffLabel) must have distinct text labels", micOnLabel != micOffLabel)

        // Verify Deafen OFF vs ON distinct text
        val hearingLabel = composeRule.onNodeWithTag("talk_controls_deafen_label").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("")
        deafened = true
        composeRule.waitForIdle()
        val deafenedLabel = composeRule.onNodeWithTag("talk_controls_deafen_label").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("")
        assertTrue("Deafen OFF ($hearingLabel) and ON ($deafenedLabel) must have distinct text labels", hearingLabel != deafenedLabel)

        // Verify PTT Idle vs VOX Open vs Pressed distinct text
        composeRule.onNodeWithText("HOLD TO TALK").assertIsDisplayed()
        voxOpen = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("VOX ACTIVE").assertIsDisplayed()
        pttPressed = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("TALKING").assertIsDisplayed()
    }
}
