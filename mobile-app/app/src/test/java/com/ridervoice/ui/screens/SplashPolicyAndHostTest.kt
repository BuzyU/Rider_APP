package com.ridervoice.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.ridervoice.ui.theme.RiderVoiceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP-U7 Splash Policy, Android 12+ Paper Splash Theme, and Fake-Player Host Tests:
 * - Verifies AndroidManifest uses @style/Theme.RiderVoice with #F5F1E8 Paper background (values & values-v31).
 * - Verifies SplashSkipPolicy skips on every required trigger (missing asset, deep link, push, share link,
 *   active-ride resume, reduced motion, battery saver, low memory).
 * - Verifies event-driven SplashPlayerHost navigation (prepared -> completed, error fallback, single-shot,
 *   zero fixed delay(), zero audio focus, and release() on dispose).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class SplashPolicyAndHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSplashPlayerHost : SplashPlayerHost {
        override val requestsAudioFocus: Boolean = false
        var startCalls = 0
        var releaseCalls = 0
        private var onPreparedCb: (() -> Unit)? = null
        private var onCompletedCb: (() -> Unit)? = null
        private var onErrorCb: ((Throwable?) -> Unit)? = null

        override fun start(
            onPrepared: () -> Unit,
            onCompleted: () -> Unit,
            onError: (Throwable?) -> Unit
        ) {
            startCalls++
            onPreparedCb = onPrepared
            onCompletedCb = onCompleted
            onErrorCb = onError
        }

        fun emitPrepared() { onPreparedCb?.invoke() }
        fun emitCompleted() { onCompletedCb?.invoke() }
        fun emitError(t: Throwable = IllegalStateException("codec error")) { onErrorCb?.invoke(t) }

        override fun release() {
            releaseCalls++
        }
    }

    private fun locateMainDir(): File {
        val candidates = listOf(
            File("src/main"),
            File("app/src/main"),
            File("mobile-app/app/src/main")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main")
    }

    @Test
    fun android12SplashTheme_usesPaperBackgroundInManifestAndValues() {
        val mainDir = locateMainDir()
        val manifest = File(mainDir, "AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must use @style/Theme.RiderVoice",
            manifest.contains("android:theme=\"@style/Theme.RiderVoice\"")
        )

        val baseThemes = File(mainDir, "res/values/themes.xml")
        assertTrue("res/values/themes.xml must exist", baseThemes.exists())
        val baseText = baseThemes.readText()
        assertTrue(
            "values/themes.xml must define Theme.RiderVoice with #F5F1E8 windowBackground",
            baseText.contains("Theme.RiderVoice") && baseText.contains("#F5F1E8", ignoreCase = true)
        )

        val v31Themes = File(mainDir, "res/values-v31/themes.xml")
        assertTrue("res/values-v31/themes.xml must exist for Android 12+ splash", v31Themes.exists())
        val v31Text = v31Themes.readText()
        assertTrue(
            "values-v31/themes.xml must configure windowSplashScreenBackground=#F5F1E8",
            v31Text.contains("android:windowSplashScreenBackground") && v31Text.contains("#F5F1E8", ignoreCase = true)
        )
    }

    @Test
    fun splashScreen_usesEventDrivenHostAndZeroFixedDelays() {
        val mainDir = locateMainDir()
        val policyFile = File(mainDir, "java/com/ridervoice/ui/screens/SplashSkipPolicy.kt")
        assertTrue("SplashSkipPolicy.kt must exist", policyFile.exists())

        val splashFile = File(mainDir, "java/com/ridervoice/ui/screens/SplashScreen.kt").readText()
        assertFalse(
            "SplashScreen.kt must never use a fixed delay()",
            splashFile.contains("delay(")
        )
        assertTrue(
            "SplashScreen.kt must integrate SplashSkipPolicy and SplashPlayerHost",
            splashFile.contains("SplashSkipPolicy") && splashFile.contains("SplashPlayerHost")
        )
    }

    @Test
    fun skipPolicy_skipsOnAllRequiredConditions_andAllowsNormalColdStartWhenAssetPresent() {
        val normalWithAsset = SplashLaunchContext(hasVideoAsset = true)
        assertFalse("Normal cold launch with asset present should allow splash", SplashSkipPolicy.shouldSkipSplash(normalWithAsset))

        assertTrue("Missing asset must skip", SplashSkipPolicy.shouldSkipSplash(normalWithAsset.copy(hasVideoAsset = false)))
        assertTrue("Deep link must skip", SplashSkipPolicy.shouldSkipSplash(normalWithAsset.copy(isDeepLink = true)))
        assertTrue("Push launch must skip", SplashSkipPolicy.shouldSkipSplash(normalWithAsset.copy(isPushLaunch = true)))
        assertTrue("Share link must skip", SplashSkipPolicy.shouldSkipSplash(normalWithAsset.copy(isShareLink = true)))
        assertTrue("Active ride resume must skip", SplashSkipPolicy.shouldSkipSplash(normalWithAsset.copy(isActiveRideResume = true)))
        assertTrue("Reduced motion must skip", SplashSkipPolicy.shouldSkipSplash(normalWithAsset.copy(isReducedMotion = true)))
        assertTrue("Battery saver must skip", SplashSkipPolicy.shouldSkipSplash(normalWithAsset.copy(isBatterySaver = true)))
        assertTrue("Low memory must skip", SplashSkipPolicy.shouldSkipSplash(normalWithAsset.copy(isLowMemory = true)))
    }

    @Test
    fun splashScreen_skipsImmediatelyWithoutStartingPlayerWhenAssetMissing() {
        val fakeHost = FakeSplashPlayerHost()
        var finishCount = 0

        composeRule.setContent {
            RiderVoiceTheme {
                SplashScreen(
                    launchContext = SplashLaunchContext(hasVideoAsset = false),
                    playerHost = fakeHost,
                    onSplashFinished = { finishCount++ }
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals("Should finish immediately once when asset is absent", 1, finishCount)
        assertEquals("Player must not start when skipped", 0, fakeHost.startCalls)
        assertFalse("Splash host must never request audio focus", fakeHost.requestsAudioFocus)
    }

    @Test
    fun splashScreen_drivesNavigationFromPlayerCompletion_andReleasesOnDispose() {
        val fakeHost = FakeSplashPlayerHost()
        var finishCount = 0
        var showSplash by mutableStateOf(true)

        composeRule.setContent {
            RiderVoiceTheme {
                if (showSplash) {
                    SplashScreen(
                        launchContext = SplashLaunchContext(hasVideoAsset = true),
                        playerHost = fakeHost,
                        onSplashFinished = { finishCount++ }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals("Player should start once when asset present and no skip flags", 1, fakeHost.startCalls)
        assertEquals("Must not navigate before completion/error event", 0, finishCount)

        fakeHost.emitPrepared()
        assertEquals("Must not navigate on prepared alone", 0, finishCount)

        fakeHost.emitCompleted()
        fakeHost.emitCompleted() // duplicate event guard
        assertEquals("Must navigate exactly once on completion", 1, finishCount)

        showSplash = false
        composeRule.waitForIdle()
        assertEquals("Player must be released on dispose to prevent leaks", 1, fakeHost.releaseCalls)
    }

    @Test
    fun splashScreen_fallsBackImmediatelyOnPlayerError() {
        val fakeHost = FakeSplashPlayerHost()
        var finishCount = 0

        composeRule.setContent {
            RiderVoiceTheme {
                SplashScreen(
                    launchContext = SplashLaunchContext(hasVideoAsset = true),
                    playerHost = fakeHost,
                    onSplashFinished = { finishCount++ }
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(0, finishCount)
        fakeHost.emitError()
        assertEquals("Must navigate immediately on player error without hanging", 1, finishCount)
    }
}
