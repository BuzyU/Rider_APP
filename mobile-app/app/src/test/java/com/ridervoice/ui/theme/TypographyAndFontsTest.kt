package com.ridervoice.ui.theme

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ridervoice.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * WP-U1: Verifies Manrope is bundled locally as static res/font TTFs with zero GMS GoogleFont
 * provider dependency, single UI FontFamily, OFL notice present, and Devanagari (hi/mr) line-height
 * rendering without vertical clipping at fontScale 1.0, 1.3, and 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp", application = Application::class)
class TypographyAndFontsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun locateAppModuleDir(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            if (File(dir, "src/main/java/com/ridervoice/ui/theme/Type.kt").exists()) return dir
            if (File(dir, "mobile-app/app/src/main/java/com/ridervoice/ui/theme/Type.kt").exists()) {
                return File(dir, "mobile-app/app")
            }
            dir = dir.parentFile ?: break
        }
        throw IllegalStateException("Could not locate app module directory")
    }

    @Test
    fun typographyUsesSingleBundledManropeFamily_andNoGoogleFontsProvider() {
        val styles = listOf(
            Typography.displayLarge,
            Typography.titleLarge,
            Typography.titleMedium,
            Typography.labelLarge,
            Typography.labelSmall,
            Typography.bodyLarge,
            Typography.bodyMedium,
            Typography.bodySmall
        )
        val families = styles.map { it.fontFamily }.toSet()
        assertEquals("Typography must use exactly one FontFamily (ManropeFamily)", setOf(ManropeFamily), families)

        assertTrue(
            "ManropeFamily must be a FontListFontFamily backed by bundled res/font resources",
            ManropeFamily is FontListFontFamily
        )
        val fonts = (ManropeFamily as FontListFontFamily).fonts
        assertTrue(
            "Every font in ManropeFamily must be a bundled ResourceFont (no GoogleFont provider). Found: ${fonts.map { it::class.simpleName }}",
            fonts.all { it is ResourceFont }
        )
        val weights = fonts.map { it.weight }.toSet()
        assertTrue("Missing FontWeight.Normal (400)", FontWeight.Normal in weights)
        assertTrue("Missing FontWeight.Medium (500)", FontWeight.Medium in weights)
        assertTrue("Missing FontWeight.SemiBold (600)", FontWeight.SemiBold in weights)
        assertTrue("Missing FontWeight.Bold (700)", FontWeight.Bold in weights)

        val appDir = locateAppModuleDir()
        val typeKt = File(appDir, "src/main/java/com/ridervoice/ui/theme/Type.kt").readText()
        assertFalse("Type.kt must not reference GoogleFont", typeKt.contains("GoogleFont"))
        assertFalse("Type.kt must not define dead BarlowCondensedFamily", typeKt.contains("BarlowCondensedFamily"))
        assertFalse("Type.kt must not define dead SpaceMonoFamily", typeKt.contains("SpaceMonoFamily"))
        assertFalse("Type.kt must not define dead InterFamily", typeKt.contains("InterFamily"))
        assertFalse("Type.kt must not define dead OrbitronFamily", typeKt.contains("OrbitronFamily"))

        val buildGradle = File(appDir, "build.gradle").readText()
        assertFalse("build.gradle must not depend on ui-text-google-fonts", buildGradle.contains("ui-text-google-fonts"))
        assertFalse("Unused font_certs.xml must be removed", File(appDir, "src/main/res/values/font_certs.xml").exists())
        assertTrue("Manrope OFL license must be bundled", File(appDir, "src/main/assets/licenses/MANROPE_OFL.txt").exists())
    }

    @Test
    fun rendersBundledManropeOfflineWithoutGooglePlayServicesFontProvider() {
        composeRule.setContent {
            RiderVoiceTheme {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Regular 400", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("w400"))
                    Text("Medium 500", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("w500"))
                    Text("SemiBold 600", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("w600"))
                    Text("Bold 700", style = MaterialTheme.typography.displayLarge, modifier = Modifier.testTag("w700"))
                }
            }
        }
        composeRule.waitForIdle()

        listOf("w400", "w500", "w600", "w700").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val b = node.getBoundsInRoot()
            assertTrue("$tag width must be > 0", (b.right - b.left) > 0.dp)
            assertTrue("$tag height must be > 0", (b.bottom - b.top) > 0.dp)
        }
    }

    @Test
    fun devanagariStrings_hiAndMr_doNotClipOrOverlapAtFontScales_1_13_20() {
        val baseContext = RuntimeEnvironment.getApplication()
        val locales = listOf(Locale("hi"), Locale("mr"))
        val scales = listOf(1.0f, 1.3f, 2.0f)

        var currentScale by mutableFloatStateOf(1.0f)
        var dashboardStr by mutableStateOf("")
        var lobbyStr by mutableStateOf("")
        var rideStr by mutableStateOf("")
        var settingsStr by mutableStateOf("")

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = currentScale)
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = 320.dp, height = 760.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = dashboardStr,
                                style = MaterialTheme.typography.displayLarge,
                                modifier = Modifier.fillMaxWidth().testTag("deva_dashboard")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = lobbyStr,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth().testTag("deva_lobby")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = rideStr,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().testTag("deva_ride")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = settingsStr,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().testTag("deva_settings")
                            )
                        }
                    }
                }
            }
        }

        val tags = listOf("deva_dashboard", "deva_lobby", "deva_ride", "deva_settings")
        for (locale in locales) {
            val cfg = Configuration(baseContext.resources.configuration).apply { setLocale(locale) }
            val locRes = baseContext.createConfigurationContext(cfg).resources
            dashboardStr = locRes.getString(R.string.action_start_ride)
            lobbyStr = locRes.getString(R.string.lobby_waiting_host)
            rideStr = locRes.getString(R.string.mesh_banner_offline_active)
            settingsStr = locRes.getString(R.string.settings_headset_setup_subtitle)

            for (scale in scales) {
                currentScale = scale
                composeRule.waitForIdle()

                val bounds = tags.map { tag ->
                    val node = composeRule.onNodeWithTag(tag)
                    node.assertIsDisplayed()
                    val layouts = mutableListOf<TextLayoutResult>()
                    val action = node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
                    assertTrue(action.action?.invoke(layouts) == true)
                    val layout = layouts.first()
                    assertFalse(
                        "Devanagari ($locale @ ${scale}x) $tag clipped vertically",
                        layout.didOverflowHeight
                    )
                    assertFalse(
                        "Devanagari ($locale @ ${scale}x) $tag clipped horizontally",
                        layout.didOverflowWidth
                    )
                    tag to node.getBoundsInRoot()
                }
                for (i in 0 until bounds.size - 1) {
                    val (t1, b1) = bounds[i]
                    val (t2, b2) = bounds[i + 1]
                    assertTrue(
                        "Devanagari ($locale @ ${scale}x) $t1 ($b1) overlaps $t2 ($b2)",
                        b1.bottom <= b2.top
                    )
                }
            }
        }
    }
}
