package com.ridervoice.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.robolectric.Robolectric
import androidx.compose.ui.unit.dp
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
            "Dark PaperOnBrick on Brick" to (DarkPalette.OnEmergency to DarkPalette.EmergencyRed),
            "Dark StatusOk on Paper" to (DarkPalette.StatusOk to DarkPalette.Background),
            "Dark StatusOk on PaperRaised" to (DarkPalette.StatusOk to DarkPalette.Surface),
            "Dark StatusOk on PaperSunken" to (DarkPalette.StatusOk to DarkPalette.SurfaceAlt),
            "Dark StatusOk on StatusOkContainer" to (DarkPalette.StatusOk to DarkPalette.StatusOkContainer),
            "Dark StatusWarn on Paper" to (DarkPalette.StatusWarn to DarkPalette.Background),
            "Dark StatusWarn on PaperRaised" to (DarkPalette.StatusWarn to DarkPalette.Surface),
            "Dark StatusWarn on PaperSunken" to (DarkPalette.StatusWarn to DarkPalette.SurfaceAlt),
            "Dark StatusWarn on StatusWarnContainer" to (DarkPalette.StatusWarn to DarkPalette.StatusWarnContainer)
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
    fun nonFrozenUiHasNoRawHexColorLiterals() {
        val candidates = listOf(
            File("src/main/java/com/ridervoice"),
            File("app/src/main/java/com/ridervoice"),
            File("mobile-app/app/src/main/java/com/ridervoice")
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: error("Cannot find com/ridervoice root")

        // theme/, MeshColors.kt, and frozen online room files are explicitly excluded (frozen files are listed in Blocked)
        val frozenOrAllowed = setOf(
            "mesh/ui/MeshColors.kt",
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioVuMeter.kt",
            "ui/screens/room/SosAlertBanner.kt"
        )
        val rawHexRegex = Regex("""Color\(0x[0-9A-Fa-f]+""")
        val violations = mutableListOf<String>()

        for (target in listOf(File(root, "ui"), File(root, "mesh/ui"))) {
            target.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                if (rel.startsWith("ui/theme/") || rel in frozenOrAllowed) return@forEach
                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                    if (rawHexRegex.containsMatchIn(line)) {
                        violations += "$rel:${idx + 1}: ${trimmed}"
                    }
                }
            }
        }
        assertTrue(
            "Found raw Color(0x...) literals outside theme/MeshColors:\n${violations.joinToString("\n")}",
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

    @Test
    fun themePlumbing_removesAnimatedBgAndWindowBarColorWrites_andCanvasUpdatesMidComposition() {
        val candidates = listOf(
            File("src/main/java/com/ridervoice/ui/theme/Theme.kt"),
            File("app/src/main/java/com/ridervoice/ui/theme/Theme.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/ui/theme/Theme.kt")
        )
        val themeText = candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate Theme.kt")
        val chromeCandidates = listOf(
            File("src/main/java/com/ridervoice/ui/theme/WindowChrome.kt"),
            File("app/src/main/java/com/ridervoice/ui/theme/WindowChrome.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/ui/theme/WindowChrome.kt")
        )
        val chromeText = chromeCandidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate WindowChrome.kt")

        assertTrue("Theme.kt must not use animateColorAsState", !themeText.contains("animateColorAsState"))
        assertTrue("Theme.kt must not use bare SideEffect", !themeText.contains("SideEffect {"))
        assertTrue("Theme.kt must call applyRiderVoiceWindowChrome", themeText.contains("applyRiderVoiceWindowChrome("))
        assertTrue("WindowChrome.kt must call enableEdgeToEdge", chromeText.contains("enableEdgeToEdge("))

        ThemeState.set(false)
        var drawnPopColor: Color? = null
        var schemeBg: Color? = null
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            RiderVoiceTheme {
                schemeBg = MaterialTheme.colorScheme.background
                Canvas(modifier = Modifier.size(32.dp)) {
                    drawnPopColor = Pop
                    drawCircle(color = Pop)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val view = hostView ?: error("Expected hostView")
            val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            view.draw(AndroidCanvas(bmp))
        }
        assertEquals(LightPalette.Background, schemeBg)
        assertEquals(LightPalette.AccentAmber, drawnPopColor)

        ThemeState.set(true)
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val view = hostView ?: error("Expected hostView")
            val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            view.draw(AndroidCanvas(bmp))
        }
        assertEquals(DarkPalette.Background, schemeBg)
        assertEquals(DarkPalette.AccentAmber, drawnPopColor)
    }

    @Test
    fun rootBackdrop_rendersFullWindowPaperAndSyncsDecorViewBackgroundOnThemeToggle() {
        ThemeState.set(false)
        composeRule.setContent {
            RiderVoiceTheme {
                androidx.compose.material3.Text("RootBackdropCheck")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_root_backdrop").assertIsDisplayed()

        val candidates = listOf(
            File("src/main/java/com/ridervoice/ui/theme/WindowChrome.kt"),
            File("app/src/main/java/com/ridervoice/ui/theme/WindowChrome.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/ui/theme/WindowChrome.kt")
        )
        val chromeText = candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate WindowChrome.kt")
        assertTrue("WindowChrome.kt must sync window decorView background", chromeText.contains("decorView.setBackgroundColor"))
        assertTrue("WindowChrome.kt must disable nav bar contrast enforcement on API 29+", chromeText.contains("isNavigationBarContrastEnforced = false"))
        assertTrue("WindowChrome.kt must disable status bar contrast enforcement on API 29+", chromeText.contains("isStatusBarContrastEnforced = false"))
    }

    @Test
    fun nonFrozenUi_usesRvTextFieldInsteadOfDirectOutlinedTextField() {
        val candidates = listOf(
            File("src/main/java/com/ridervoice"),
            File("app/src/main/java/com/ridervoice"),
            File("mobile-app/app/src/main/java/com/ridervoice")
        )
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate com/ridervoice source root")
        val allowlist = setOf(
            "ui/components/RvTextField.kt",
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioRouteSheet.kt",
            "ui/screens/room/AudioVuMeter.kt",
            "ui/screens/room/DiagnosticsDialog.kt",
            "ui/screens/room/LeaveRideDialog.kt",
            "ui/screens/room/SosAlertBanner.kt"
        )
        val hits = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (!(rel.startsWith("ui/") || rel.startsWith("mesh/ui/"))) return@forEach
                if (rel in allowlist) return@forEach
                file.readLines().forEachIndexed { i, line ->
                    if (line.contains("OutlinedTextField(")) {
                        hits += "$rel:${i + 1}: ${line.trim()}"
                    }
                }
            }
        assertTrue("Direct OutlinedTextField( found outside RvTextField.kt:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun rvTextField_rendersVisibleUnfocusedBorderInLightAndDarkThemes() {
        fun linearizeChannel(v: Float): Double =
            if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

        fun luminance(c: Color): Double =
            0.2126 * linearizeChannel(c.red) + 0.7152 * linearizeChannel(c.green) + 0.0722 * linearizeChannel(c.blue)

        fun ratio(a: Color, b: Color): Double {
            val l1 = luminance(a)
            val l2 = luminance(b)
            return (kotlin.math.max(l1, l2) + 0.05) / (kotlin.math.min(l1, l2) + 0.05)
        }

        var hostView: View? = null
        var drawnBorderColor: Color? = null
        ThemeState.set(false)
        composeRule.setContent {
            hostView = LocalView.current
            RiderVoiceTheme {
                Box(
                    modifier = Modifier
                        .size(width = 240.dp, height = 88.dp)
                        .background(Paper)
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    com.ridervoice.ui.components.RvTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rv_text_field_sample")
                    )
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawnBorderColor = InkBorderStrong
                        drawRect(color = InkBorderStrong)
                    }
                }
            }
        }

        for ((isDark, expectedBorder) in listOf(false to LightPalette.BorderStrong, true to DarkPalette.BorderStrong)) {
            ThemeState.set(isDark)
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                val view = hostView ?: error("Expected hostView")
                val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                view.draw(AndroidCanvas(bmp))
            }
            val borderColor = composeRule.onNodeWithTag("rv_text_field_sample").fetchSemanticsNode()
                .config[com.ridervoice.ui.components.RvTextFieldUnfocusedBorderColorKey]
            assertEquals(expectedBorder, drawnBorderColor)
            assertEquals(expectedBorder, borderColor)

            val paperContrast = ratio(borderColor, Paper)
            val raisedContrast = ratio(borderColor, PaperRaised)
            val sunkenContrast = ratio(borderColor, PaperSunken)
            assertTrue(
                "Expected visible unfocused border >= 3.0:1 against ${if (isDark) "Dark" else "Light"} surfaces, got Paper=${"%.2f".format(paperContrast)}, Raised=${"%.2f".format(raisedContrast)}, Sunken=${"%.2f".format(sunkenContrast)}",
                paperContrast >= 3.0 && raisedContrast >= 3.0 && sunkenContrast >= 3.0
            )
        }
    }

    @Test
    fun nonFrozenUiDoesNotReferenceLegacyColorAliasesOrRawBlackScrim() {
        val candidates = listOf(
            File("src/main/java/com/ridervoice"),
            File("app/src/main/java/com/ridervoice"),
            File("mobile-app/app/src/main/java/com/ridervoice")
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: error("Cannot find com/ridervoice")
        val excluded = setOf(
            "ui/theme/Color.kt",
            "mesh/ui/MeshColors.kt",
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioRouteSheet.kt",
            "ui/screens/room/AudioVuMeter.kt",
            "ui/screens/room/DiagnosticsDialog.kt",
            "ui/screens/room/LeaveRideDialog.kt",
            "ui/screens/room/SosAlertBanner.kt"
        )
        val legacyAliasRegex = Regex(
            """(?<!\.)\b(GraphiteBase|DarkSlate|Gunmetal|GraphiteSurface|BorderColor|TextPrimary|TextSecondary|TextMuted|NeonOrange|ElectricCyan|NeonViolet|TechGreen|SuccessGreen|AlertRed|WarningAmber|AmberContainer|HazardContainer)\b"""
        )
        val violations = mutableListOf<String>()

        listOf(File(root, "MainActivity.kt"), File(root, "ui"), File(root, "mesh/ui"))
            .forEach { target ->
                val files = if (target.isFile) listOf(target) else target.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                for (file in files) {
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel in excluded) continue
                    file.readLines().forEachIndexed { i, rawLine ->
                        val line = rawLine.trim()
                        if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                        if (line.contains("Color.Black.copy(")) {
                            violations += "$rel:${i + 1}: raw Color.Black.copy scrim (use RvScrim)"
                        }
                        val match = legacyAliasRegex.find(line)
                        if (match != null) {
                            violations += "$rel:${i + 1}: legacy color alias ${match.value}"
                        }
                    }
                }
            }

        assertEquals(Color(0x73000000), RvScrim)
        assertTrue(
            "Legacy color aliases or raw black scrims found in non-frozen UI:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun windowChrome_matrix_systemLightDark_x_inAppLightDark_andNoReinvocationOnRecompose() {
        val candidates = listOf(
            File("src/main"),
            File("app/src/main"),
            File("mobile-app/app/src/main")
        )
        val mainDir = candidates.firstOrNull { it.isDirectory } ?: error("Cannot find src/main")

        // Verify night & API-bucket theme resources exist with matching light/dark window chrome
        val themeExpectations = listOf(
            "res/values/themes.xml" to "#F5F1E8",
            "res/values-night/themes.xml" to "#0E0E0F",
            "res/values-v28/themes.xml" to "#F5F1E8",
            "res/values-night-v28/themes.xml" to "#0E0E0F",
            "res/values-v29/themes.xml" to "#F5F1E8",
            "res/values-night-v29/themes.xml" to "#0E0E0F",
            "res/values-v30/themes.xml" to "#F5F1E8",
            "res/values-night-v30/themes.xml" to "#0E0E0F",
            "res/values-v31/themes.xml" to "#F5F1E8",
            "res/values-night-v31/themes.xml" to "#0E0E0F"
        )
        for ((relPath, expectedHex) in themeExpectations) {
            val content = File(mainDir, relPath).readText()
            assertTrue("$relPath must set windowBackground $expectedHex", content.contains(expectedHex))
            assertTrue("$relPath must use transparent status/nav bars", content.contains("@android:color/transparent"))
        }

        // Verify all 3 Activity classes apply in-app chrome before setContent
        for (actPath in listOf(
            "java/com/ridervoice/MainActivity.kt",
            "java/com/ridervoice/ui/screens/EmergencyAlertActivity.kt",
            "java/com/ridervoice/ui/screens/RideInviteActivity.kt"
        )) {
            val src = File(mainDir, actPath).readText()
            val chromeIdx = src.indexOf("applyRiderVoiceWindowChrome(")
            val setContentIdx = src.indexOf("setContent {")
            assertTrue("$actPath must call applyRiderVoiceWindowChrome before setContent", chromeIdx in 0 until setContentIdx)
        }

        // Test 2x2 matrix: system {light, dark} x in-app {light, dark}
        for (systemNight in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            for (inAppDark in listOf(false, true)) {
                val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                val activity = controller.get()
                activity.resources.configuration.uiMode =
                    (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or systemNight

                activity.applyRiderVoiceWindowChrome(inAppDark)
                var observedPaper: Color? = null
                activity.setContent {
                    RiderVoiceTheme {
                        observedPaper = Paper
                        androidx.compose.material3.Text("ChromeMatrix")
                    }
                }
                composeRule.waitForIdle()

                val expectedColor = if (inAppDark) DarkPalette.Background else LightPalette.Background
                val expectedArgb = expectedColor.toArgb()
                assertEquals(expectedColor, observedPaper)
                assertEquals(expectedArgb, (activity.window.decorView.background as ColorDrawable).color)
                @Suppress("DEPRECATION")
                run {
                    assertEquals(android.graphics.Color.TRANSPARENT, activity.window.statusBarColor)
                    assertEquals(android.graphics.Color.TRANSPARENT, activity.window.navigationBarColor)
                }
                val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertEquals(!inAppDark, insetsController.isAppearanceLightStatusBars)
                assertEquals(!inAppDark, insetsController.isAppearanceLightNavigationBars)
                if (Build.VERSION.SDK_INT >= 29) {
                    assertTrue(!activity.window.isNavigationBarContrastEnforced)
                    assertTrue(!activity.window.isStatusBarContrastEnforced)
                }
                controller.pause().stop().destroy()
            }
        }

        // Verify recompositions with unchanged isDark do NOT re-invoke enableEdgeToEdge
        ThemeState.set(false)
        var counter by mutableIntStateOf(0)
        composeRule.setContent {
            RiderVoiceTheme {
                androidx.compose.material3.Text("Tick=$counter")
            }
        }
        composeRule.waitForIdle()
        val baselineInvocations = enableEdgeToEdgeInvocationCount
        repeat(5) { step ->
            composeRule.runOnUiThread { counter = step + 1 }
            composeRule.waitForIdle()
        }
        assertEquals(
            "Unchanged isDark recompositions must not re-invoke enableEdgeToEdge",
            baselineInvocations,
            enableEdgeToEdgeInvocationCount
        )

        composeRule.runOnUiThread { ThemeState.set(true) }
        composeRule.waitForIdle()
        assertEquals(
            "Toggling isDark must invoke applyRiderVoiceWindowChrome exactly once",
            baselineInvocations + 1,
            enableEdgeToEdgeInvocationCount
        )
    }
}
