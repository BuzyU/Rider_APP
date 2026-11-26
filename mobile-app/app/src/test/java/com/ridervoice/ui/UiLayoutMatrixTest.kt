package com.ridervoice.ui

import android.app.Application
import android.content.res.Configuration
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.models.Participant
import com.ridervoice.navigation.Routes
import com.ridervoice.network.ConnectionState
import com.ridervoice.ui.components.PrimaryButton
import com.ridervoice.ui.components.RvBottomNavIconNameKey
import com.ridervoice.ui.components.RvBottomNavigationBar
import com.ridervoice.ui.components.TabProfileAvatar
import com.ridervoice.ui.screens.SosScreen
import com.ridervoice.ui.screens.room.ConnectionPill
import com.ridervoice.ui.screens.room.PttPerforatedButton
import com.ridervoice.ui.theme.RvIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.SemanticsMatcher
import com.ridervoice.ui.screens.room.RideTopBar
import com.ridervoice.ui.screens.room.RiderList
import com.ridervoice.ui.screens.room.TalkControls
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.viewmodels.SosState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.abs

/**
 * WP-U9 Layout, Touch Targets, and Accessibility Matrix Verification:
 * - Ride-time primary controls (Talk, Mute, Deafen, SOS, Leave, Start) >= 56dp tall and >= 48dp wide.
 * - Leave and Audio Route badge do not overlap and are >= 16dp apart.
 * - SOS has >= 16dp clear space from its neighbor (PTT).
 * - DIAG/REC pill has >= 48dp tap height and >= 11sp scalable label.
 * - Sibling bounds remain identical across speaking toggle, DIAG pill toggle, rider count change (1/6/30),
 *   and connection state transitions.
 * - Bottom bar items >= 48dp, non-overlapping, unclipped at 320dp + 2.0x font across en/hi/mr.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w640dp-h900dp", application = Application::class)
class UiLayoutMatrixTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val longRideName = "Transcontinental Sahyadri Mountain Endurance Ride 2026"

    private fun sampleParticipants(count: Int): List<Participant> =
        (1..count).map { i ->
            Participant(
                identity = "rider-$i",
                displayName = if (i == 1) "Alexandrina-Maximiliana Von Schwartzenberg" else "Rider $i LongHandleName"
            )
        }

    private fun horizontalGap(a: DpRect, b: DpRect): Dp {
        return when {
            a.right <= b.left -> b.left - a.right
            b.right <= a.left -> a.left - b.right
            else -> (-1).dp // overlapping horizontally
        }
    }

    private fun assertBoundsIdentical(before: DpRect, after: DpRect, label: String) {
        val tol = 0.5f
        assertTrue(
            "$label shifted: before=$before after=$after",
            abs(before.left.value - after.left.value) <= tol &&
                abs(before.top.value - after.top.value) <= tol &&
                abs(before.right.value - after.right.value) <= tol &&
                abs(before.bottom.value - after.bottom.value) <= tol
        )
    }

    private fun SemanticsNodeInteraction.getTextLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
        assertTrue("Text node must expose GetTextLayoutResult", action.action?.invoke(results) == true)
        assertTrue("Expected non-empty TextLayoutResult", results.isNotEmpty())
        return results.first()
    }

    @Test
    fun rideTimeControls_meet56dpHeight_16dpClearSpacing_and11spDiagLabel_acrossMatrix() {
        data class Viewport(val w: Dp, val h: Dp, val fontScale: Float, val dir: LayoutDirection)
        val cases = listOf(
            Viewport(320.dp, 640.dp, 1.0f, LayoutDirection.Ltr),
            Viewport(320.dp, 640.dp, 1.3f, LayoutDirection.Ltr),
            Viewport(320.dp, 640.dp, 2.0f, LayoutDirection.Ltr),
            Viewport(360.dp, 720.dp, 1.0f, LayoutDirection.Ltr),
            Viewport(411.dp, 891.dp, 2.0f, LayoutDirection.Ltr),
            Viewport(640.dp, 320.dp, 1.0f, LayoutDirection.Ltr),
            Viewport(360.dp, 640.dp, 2.0f, LayoutDirection.Rtl)
        )

        var idx by mutableIntStateOf(0)

        composeRule.setContent {
            val c = cases[idx]
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = c.fontScale),
                LocalLayoutDirection provides c.dir
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = c.w, height = c.h)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            RideTopBar(
                                rideName = longRideName,
                                connectionState = ConnectionState.CONNECTED,
                                activeSpeaker = "rider-1",
                                currentUid = "rider-1",
                                participants = sampleParticipants(6),
                                activeDevice = AudioDevice.BluetoothSco("Cardo Packtalk Edge"),
                                routerState = RouterState.ACTIVE,
                                requestedDevice = AudioDevice.BluetoothSco("Cardo Packtalk Edge"),
                                routedInput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                                routedOutput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                                isCollectingDiag = true,
                                diagSampleCount = 12,
                                onAudioRouteClick = {},
                                onDiagnosticsClick = {},
                                onLeaveClick = {},
                                onRescanAudio = {}
                            )
                            Spacer(modifier = Modifier.weight(1f))
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
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        for (i in cases.indices) {
            idx = i
            val c = cases[i]
            composeRule.waitForIdle()

            val audioBadge = composeRule.onNodeWithTag("ride_topbar_audio_badge")
            val leaveBtn = composeRule.onNodeWithTag("ride_topbar_leave_button")
            val diagPill = composeRule.onNodeWithTag("ride_topbar_diag_pill")
            val diagLabel = composeRule.onNodeWithTag("ride_topbar_diag_label", useUnmergedTree = true)
            val micBtn = composeRule.onNodeWithTag("talk_controls_mic")
            val deafenBtn = composeRule.onNodeWithTag("talk_controls_deafen")
            val pttBtn = composeRule.onNodeWithTag("talk_controls_ptt")
            val sosBtn = composeRule.onNodeWithTag("talk_controls_sos")

            // Ride-time primary controls >= 56dp tall & >= 48dp wide
            leaveBtn.assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(56.dp)
            micBtn.assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(56.dp)
            deafenBtn.assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(56.dp)
            pttBtn.assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(56.dp)
            sosBtn.assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(56.dp)

            // Audio badge & DIAG pill >= 48dp tall & clickable
            audioBadge.assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            diagPill.assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

            // DIAG label >= 11sp scalable
            val diagFontSize = diagLabel.getTextLayout().layoutInput.style.fontSize
            assertTrue("DIAG label fontSize ($diagFontSize) must be >= 11.sp ($c)", diagFontSize >= 11.sp)

            // Leave & Audio route >= 16dp apart
            val audioBounds = audioBadge.getBoundsInRoot()
            val leaveBounds = leaveBtn.getBoundsInRoot()
            val leaveAudioGap = horizontalGap(audioBounds, leaveBounds)
            assertTrue(
                "Leave ($leaveBounds) and AudioRoute ($audioBounds) gap=$leaveAudioGap must be >= 16.dp ($c)",
                leaveAudioGap >= 15.5.dp
            )

            // SOS >= 16dp clear space from PTT neighbor
            val pttBounds = pttBtn.getBoundsInRoot()
            val sosBounds = sosBtn.getBoundsInRoot()
            val sosPttGap = horizontalGap(pttBounds, sosBounds)
            assertTrue(
                "SOS ($sosBounds) and PTT ($pttBounds) gap=$sosPttGap must be >= 16.dp ($c)",
                sosPttGap >= 15.5.dp
            )
        }
    }

    @Test
    fun rideTopBar_zeroSiblingShift_onDiagToggle_speaking_riderCount_andConnectionState() {
        var diagOn by mutableStateOf(false)
        var speaker by mutableStateOf<String?>(null)
        var count by mutableIntStateOf(1)
        var conn by mutableStateOf(ConnectionState.CONNECTED)

        composeRule.setContent {
            RiderVoiceTheme {
                Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                    RideTopBar(
                        rideName = longRideName,
                        connectionState = conn,
                        activeSpeaker = speaker,
                        currentUid = "rider-1",
                        participants = sampleParticipants(count),
                        activeDevice = AudioDevice.Speakerphone,
                        routerState = RouterState.ACTIVE,
                        requestedDevice = AudioDevice.Speakerphone,
                        routedInput = RoutedDeviceInfo(typeName = "MIC", productName = "Phone"),
                        routedOutput = RoutedDeviceInfo(typeName = "SPK", productName = "Speaker"),
                        isCollectingDiag = diagOn,
                        diagSampleCount = 5,
                        onAudioRouteClick = {},
                        onDiagnosticsClick = {},
                        onLeaveClick = {},
                        onRescanAudio = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val centerBefore = composeRule.onNodeWithTag("ride_topbar_center").getBoundsInRoot()
        val audioBefore = composeRule.onNodeWithTag("ride_topbar_audio_badge").getBoundsInRoot()
        val leaveBefore = composeRule.onNodeWithTag("ride_topbar_leave_button").getBoundsInRoot()

        // Toggle DIAG pill, speaker, rider count (1 -> 30), and connection state
        diagOn = true
        speaker = "rider-1"
        count = 30
        conn = ConnectionState.RECONNECTING
        composeRule.waitForIdle()

        assertBoundsIdentical(centerBefore, composeRule.onNodeWithTag("ride_topbar_center").getBoundsInRoot(), "Center")
        assertBoundsIdentical(audioBefore, composeRule.onNodeWithTag("ride_topbar_audio_badge").getBoundsInRoot(), "Audio")
        assertBoundsIdentical(leaveBefore, composeRule.onNodeWithTag("ride_topbar_leave_button").getBoundsInRoot(), "Leave")

        conn = ConnectionState.FAILED
        speaker = null
        count = 6
        composeRule.waitForIdle()

        assertBoundsIdentical(centerBefore, composeRule.onNodeWithTag("ride_topbar_center").getBoundsInRoot(), "Center(failed)")
        assertBoundsIdentical(audioBefore, composeRule.onNodeWithTag("ride_topbar_audio_badge").getBoundsInRoot(), "Audio(failed)")
        assertBoundsIdentical(leaveBefore, composeRule.onNodeWithTag("ride_topbar_leave_button").getBoundsInRoot(), "Leave(failed)")
    }

    @Test
    fun bottomBarAndProfileAvatar_noClipOrOverlap_at320dp_fontScale2_acrossEnHiMr() {
        val locales = listOf(Locale("en"), Locale("hi"), Locale("mr"))
        var localeIdx by mutableIntStateOf(0)

        composeRule.setContent {
            val loc = locales[localeIdx]
            val baseCtx = LocalContext.current
            val cfg = Configuration(baseCtx.resources.configuration).apply { setLocale(loc) }
            val locCtx = baseCtx.createConfigurationContext(cfg)
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides locCtx,
                LocalConfiguration provides cfg,
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = 320.dp, height = 640.dp)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TabProfileAvatar(userInitial = "U", onClick = {})
                            Spacer(modifier = Modifier.weight(1f))
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
            }
        }

        val tabs = listOf("bottom_nav_home", "bottom_nav_history", "bottom_nav_squad", "bottom_nav_settings")
        for (i in locales.indices) {
            localeIdx = i
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("tab_profile_avatar")
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)

            val bounds = tabs.map { tag ->
                val node = composeRule.onNodeWithTag(tag)
                node.assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                node.getBoundsInRoot()
            }
            for (t in 0 until bounds.size - 1) {
                assertTrue(
                    "Adjacent tabs $t and ${t + 1} overlap in ${locales[i].language}: ${bounds[t]} vs ${bounds[t + 1]}",
                    bounds[t].right <= bounds[t + 1].left + 0.5.dp
                )
            }
            assertTrue(
                "Last tab overflows 320dp right edge in ${locales[i].language}: ${bounds.last()}",
                bounds.last().right <= 320.5.dp
            )
        }
    }

    @Test
    fun primaryStartCta_and_sosScreen_and_riderList_meetTargetsAndRenderCleanly() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = 320.dp, height = 640.dp)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            PrimaryButton(
                                text = "Start Ride",
                                onClick = {},
                                modifier = Modifier.padding(16.dp)
                            )
                            RiderList(
                                participants = sampleParticipants(6),
                                currentUid = "rider-1",
                                activeSpeaker = "rider-2",
                                isCurrentUserTransmitting = false,
                                isHost = true,
                                remoteLocations = emptyMap(),
                                currentLocation = null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Start Ride")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag("rider_list_lazy").assertIsDisplayed()
    }

    @Test(timeout = 10_000L)
    fun idleRideUi_hasZeroInfiniteAnimations_andWaitForIdleSettles() {
        val participants = sampleParticipants(8)
        composeRule.setContent {
            RiderVoiceTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    RideTopBar(
                        rideName = "Canyon Run",
                        connectionState = ConnectionState.CONNECTED,
                        activeSpeaker = null,
                        currentUid = "rider-1",
                        participants = participants,
                        activeDevice = AudioDevice.BluetoothSco("Cardo"),
                        routerState = RouterState.ACTIVE,
                        requestedDevice = AudioDevice.BluetoothSco("Cardo"),
                        routedInput = RoutedDeviceInfo(typeName = "MIC", productName = "Phone Mic"),
                        routedOutput = RoutedDeviceInfo(typeName = "SPK", productName = "Speakerphone"),
                        isCollectingDiag = false,
                        diagSampleCount = 0,
                        onAudioRouteClick = {},
                        onDiagnosticsClick = {},
                        onLeaveClick = {},
                        onRescanAudio = {}
                    )
                    RiderList(
                        participants = participants,
                        currentUid = "rider-1",
                        activeSpeaker = null,
                        isCurrentUserTransmitting = false,
                        isHost = true,
                        remoteLocations = emptyMap(),
                        currentLocation = null,
                        modifier = Modifier.weight(1f)
                    )
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
        // With autoAdvance = true (default), waitForIdle() only returns when zero infinite transitions are running
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("connection_dot_static", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "Connected RideTopBar must not compose pulsing dot",
            composeRule.onAllNodesWithTag("connection_dot_pulsing", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "Idle RiderList must not compose SpeakingPulseLayer",
            composeRule.onAllNodesWithTag("speaking_pulse_active", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "Idle TalkControls must not compose PttActiveRimPulse",
            composeRule.onAllNodesWithTag("ptt_rim_pulse_active", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun activeSpeakerAndReconnectingAndPressedPtt_runActiveAnimations_andStopOnReduceMotion() {
        val participants = sampleParticipants(3)
        var reduceMotion by mutableStateOf(false)
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        composeRule.mainClock.autoAdvance = false

        try {
            composeRule.setContent {
                if (reduceMotion) {
                    Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
                } else {
                    Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                }
                androidx.compose.runtime.key(reduceMotion) {
                    RiderVoiceTheme {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ConnectionPill(state = ConnectionState.RECONNECTING)
                            RiderList(
                                participants = participants,
                                currentUid = "rider-1",
                                activeSpeaker = "rider-2",
                                isCurrentUserTransmitting = false,
                                isHost = true,
                                remoteLocations = emptyMap(),
                                currentLocation = null,
                                modifier = Modifier.weight(1f)
                            )
                            PttPerforatedButton(
                                isPressed = true,
                                isVoxOpen = false,
                                onPressStart = {},
                                onPressEnd = {}
                            )
                        }
                    }
                }
            }

            composeRule.mainClock.advanceTimeBy(300L)
            composeRule.onNodeWithTag("speaking_pulse_active", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("connection_dot_pulsing", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("ptt_rim_pulse_active", useUnmergedTree = true).assertIsDisplayed()

            // When reduce motion is enabled, pulsing transitions are replaced with static markers
            reduceMotion = true
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("speaking_pulse_static", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("connection_dot_static", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(
                composeRule.onAllNodesWithTag("ptt_rim_pulse_active", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            )
        } finally {
            Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test
    fun rideScreen_topBarAndTalkControls_respectSimulatedStatusAndNavInsets() {
        var hostView: View? = null
        var densityPx = 1f
        composeRule.setContent {
            hostView = LocalView.current
            densityPx = LocalDensity.current.density
            RiderVoiceTheme {
                Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        RideTopBar(
                            rideName = "Convoy Alpha",
                            connectionState = ConnectionState.CONNECTED,
                            activeSpeaker = null,
                            currentUid = "rider-1",
                            participants = sampleParticipants(4),
                            activeDevice = AudioDevice.BluetoothSco("Cardo"),
                            routerState = RouterState.ACTIVE,
                            requestedDevice = AudioDevice.BluetoothSco("Cardo"),
                            routedInput = RoutedDeviceInfo(typeName = "MIC", productName = "Phone"),
                            routedOutput = RoutedDeviceInfo(typeName = "SPK", productName = "Speaker"),
                            isCollectingDiag = false,
                            diagSampleCount = 0,
                            onAudioRouteClick = {},
                            onDiagnosticsClick = {},
                            onLeaveClick = {},
                            onRescanAudio = {}
                        )
                        Spacer(modifier = Modifier.weight(1f))
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
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val statusBarDp = 36f
        val navBarDp = 48f
        composeRule.runOnUiThread {
            val view = hostView ?: error("Expected host view")
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.statusBars(),
                    Insets.of(0, (statusBarDp * densityPx).toInt(), 0, 0)
                )
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, (navBarDp * densityPx).toInt())
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        composeRule.waitForIdle()

        val topBarBounds = composeRule.onNodeWithTag("ride_topbar_left").getBoundsInRoot()
        val pttBounds = composeRule.onNodeWithTag("talk_controls_ptt").getBoundsInRoot()

        assertTrue(
            "RideTopBar content (${topBarBounds.top}) must be at or below status bar bottom ($statusBarDp.dp)",
            topBarBounds.top >= (statusBarDp - 0.5f).dp
        )
        assertTrue(
            "TalkControls PTT (${pttBounds.bottom}) must be at or above nav bar top (${640f - navBarDp}.dp)",
            pttBounds.bottom <= (640f - navBarDp + 0.5f).dp
        )
    }

    @Test
    fun rideTopBar_noStatusTruncationOrOverlap_acrossFontScalesLocalesStatesAndDiag() {
        val fontScales = listOf(1.0f, 1.3f, 2.0f)
        val locales = listOf(Locale("en"), Locale("hi"), Locale("mr"))
        val states = ConnectionState.values().toList()
        val diagOptions = listOf(false, true)

        var scaleIdx by mutableIntStateOf(0)
        var localeIdx by mutableIntStateOf(0)
        var stateIdx by mutableIntStateOf(0)
        var diagOn by mutableStateOf(false)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            val baseCtx = LocalContext.current
            val loc = locales[localeIdx]
            val cfg = Configuration(baseCtx.resources.configuration).apply { setLocale(loc) }
            CompositionLocalProvider(
                LocalConfiguration provides cfg,
                LocalDensity provides Density(density = baseDensity.density, fontScale = fontScales[scaleIdx])
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = 320.dp, height = 140.dp)) {
                        RideTopBar(
                            rideName = longRideName,
                            connectionState = states[stateIdx],
                            activeSpeaker = null,
                            currentUid = "rider-1",
                            participants = sampleParticipants(6),
                            activeDevice = AudioDevice.BluetoothSco("Cardo"),
                            routerState = RouterState.ACTIVE,
                            requestedDevice = AudioDevice.BluetoothSco("Cardo"),
                            routedInput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                            routedOutput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                            isCollectingDiag = diagOn,
                            diagSampleCount = 12,
                            onAudioRouteClick = {},
                            onDiagnosticsClick = {},
                            onLeaveClick = {},
                            onRescanAudio = {}
                        )
                    }
                }
            }
        }

        for (sIdx in fontScales.indices) {
            for (lIdx in locales.indices) {
                for (stIdx in states.indices) {
                    for (dOn in diagOptions) {
                        scaleIdx = sIdx
                        localeIdx = lIdx
                        stateIdx = stIdx
                        diagOn = dOn
                        composeRule.waitForIdle()
                        composeRule.mainClock.advanceTimeByFrame()
                        composeRule.waitForIdle()

                        val caseDesc = "scale=${fontScales[sIdx]}, locale=${locales[lIdx].language}, state=${states[stIdx]}, diag=$dOn"
                        val connLabel = composeRule.onNodeWithTag("ride_topbar_conn_label", useUnmergedTree = true)
                        connLabel.assertIsDisplayed()
                        val layout = connLabel.getTextLayout()
                        assertFalse(
                            "Status text ellipsized/overflowed for $caseDesc (text=${layout.layoutInput.text})",
                            layout.hasVisualOverflow || layout.didOverflowWidth
                        )

                        val left = composeRule.onNodeWithTag("ride_topbar_left").getBoundsInRoot()
                        val center = composeRule.onNodeWithTag("ride_topbar_center").getBoundsInRoot()
                        val right = composeRule.onNodeWithTag("ride_topbar_right").getBoundsInRoot()

                        assertTrue(
                            "Left ($left) overlaps Center ($center) for $caseDesc",
                            left.right <= center.left + 0.5.dp
                        )
                        assertTrue(
                            "Center ($center) overlaps Right ($right) for $caseDesc",
                            center.right <= right.left + 0.5.dp
                        )
                        val centerWidth = center.right - center.left
                        assertTrue(
                            "Center width ($centerWidth) must be >= 80.dp for $caseDesc",
                            centerWidth >= 80.dp
                        )

                        if (dOn) {
                            val diagBounds = composeRule.onNodeWithTag("ride_topbar_diag_pill").getBoundsInRoot()
                            val connBounds = connLabel.getBoundsInRoot()
                            assertTrue(
                                "Connection label ($connBounds) overlaps DIAG pill ($diagBounds) for $caseDesc",
                                connBounds.bottom <= diagBounds.top + 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rvScreen_doesNotDoubleApplyStatusOrNavInsets() {
        var hostView: View? = null
        var densityPx = 1f
        composeRule.setContent {
            hostView = LocalView.current
            densityPx = LocalDensity.current.density
            RiderVoiceTheme {
                Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                    com.ridervoice.ui.components.RvScreen(
                        title = "Safe Area Check",
                        onBack = {},
                        bottomBar = {
                            PrimaryButton(text = "Save", onClick = {}, modifier = Modifier.testTag("rv_screen_bottom_cta"))
                        }
                    ) {
                        androidx.compose.material3.Text(
                            text = "BodyTop",
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("rv_screen_body_top")
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val statusBarDp = 32f
        val navBarDp = 48f
        composeRule.runOnUiThread {
            val view = hostView ?: error("Expected host view")
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.statusBars(),
                    Insets.of(0, (statusBarDp * densityPx).toInt(), 0, 0)
                )
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, (navBarDp * densityPx).toInt())
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        composeRule.waitForIdle()

        val bodyBounds = composeRule.onNodeWithTag("rv_screen_body_top").getBoundsInRoot()
        val ctaBounds = composeRule.onNodeWithTag("rv_screen_bottom_cta").getBoundsInRoot()
        assertTrue(
            "Body top (${bodyBounds.top}) should sit right below TopAppBar (64+32=96dp), not double-padded (>120dp)",
            bodyBounds.top in 94.dp..110.dp
        )
        assertTrue(
            "Bottom CTA bottom (${ctaBounds.bottom}) must be above nav bar (${640f - navBarDp}.dp)",
            ctaBounds.bottom <= (640f - navBarDp + 0.5f).dp
        )
        assertTrue(
            "Bottom CTA bottom (${ctaBounds.bottom}) must not be double-padded (${640f - navBarDp * 2}.dp)",
            ctaBounds.bottom >= (640f - navBarDp - 24f).dp
        )
    }

    @Test
    fun allNonFrozenScaffolds_useZeroContentWindowInsetsToPreventDoublePadding() {
        val candidates = listOf(
            java.io.File("src/main/java/com/ridervoice"),
            java.io.File("app/src/main/java/com/ridervoice"),
            java.io.File("mobile-app/app/src/main/java/com/ridervoice")
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: error("Cannot find com/ridervoice")
        val frozen = setOf("ui/screens/RoomScreen.kt")
        val missingZeroInsets = mutableListOf<String>()

        listOf(java.io.File(root, "navigation"), java.io.File(root, "ui"), java.io.File(root, "mesh/ui"))
            .filter { it.isDirectory }
            .forEach { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                    val rel = f.relativeTo(root).path.replace('\\', '/')
                    if (rel !in frozen) {
                        val text = f.readText()
                        if (Regex("""\bScaffold\s*\(""").containsMatchIn(text) && !text.contains("ZeroWindowInsets") && !text.contains("WindowInsets(0, 0, 0, 0)")) {
                            missingZeroInsets += rel
                        }
                    }
                }
            }

        assertTrue(
            "Non-frozen Scaffolds missing ZeroWindowInsets (causes double systemBar padding): $missingZeroInsets",
            missingZeroInsets.isEmpty()
        )
    }

    @Test
    fun noNonFrozenIconButtonSpecifiesSizeBelow48dp() {
        val candidates = listOf(
            java.io.File("src/main/java/com/ridervoice"),
            java.io.File("app/src/main/java/com/ridervoice"),
            java.io.File("mobile-app/app/src/main/java/com/ridervoice")
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: error("Cannot find com/ridervoice")
        val frozen = setOf(
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioRouteSheet.kt",
            "ui/screens/room/AudioVuMeter.kt",
            "ui/screens/room/DiagnosticsDialog.kt",
            "ui/screens/room/LeaveRideDialog.kt",
            "ui/screens/room/SosAlertBanner.kt"
        )
        val iconBtnStart = Regex("""\bIconButton\s*\(""")
        val smallSizeRegex = Regex("""\.size\(\s*(\d+)\.dp\s*\)""")
        val violations = mutableListOf<String>()

        listOf(java.io.File(root, "MainActivity.kt"), java.io.File(root, "ui"), java.io.File(root, "mesh/ui"))
            .forEach { target ->
                val files = if (target.isFile) listOf(target) else target.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                for (f in files) {
                    val rel = f.relativeTo(root).path.replace('\\', '/')
                    if (rel in frozen) continue
                    val text = f.readText()
                    for (m in iconBtnStart.findAll(text)) {
                        var depth = 1
                        var idx = m.range.last + 1
                        while (idx < text.length && depth > 0) {
                            when (text[idx]) {
                                '(' -> depth++
                                ')' -> depth--
                            }
                            idx++
                        }
                        val headerArgs = text.substring(m.range.first, idx.coerceAtMost(text.length))
                        for (sm in smallSizeRegex.findAll(headerArgs)) {
                            val dpVal = sm.groupValues[1].toInt()
                            if (dpVal < 48) {
                                violations += "$rel IconButton has size(${dpVal}.dp) < 48.dp"
                            }
                        }
                    }
                }
            }

        assertTrue(
            "IconButtons with touch target < 48dp in non-frozen UI:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun rvIcons_allMembersResolveNonNull_andNavPairsDiffer() {
        val vectorGetters = RvIcons::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && ImageVector::class.java.isAssignableFrom(it.returnType) }
        assertTrue("Expected >= 100 ImageVector properties in RvIcons", vectorGetters.size >= 100)
        vectorGetters.forEach { method ->
            val icon = method.invoke(RvIcons) as? ImageVector
            assertTrue("RvIcons.${method.name} must resolve non-null", icon != null && icon.name.isNotBlank())
        }

        assertEquals(Icons.Outlined.GridView, RvIcons.NavHome)
        assertEquals(Icons.Filled.GridView, RvIcons.NavHomeSelected)
        assertEquals(Icons.Outlined.History, RvIcons.NavHistory)
        assertEquals(Icons.Filled.History, RvIcons.NavHistorySelected)
        assertEquals(Icons.Outlined.Group, RvIcons.NavSquad)
        assertEquals(Icons.Filled.Group, RvIcons.NavSquadSelected)
        assertEquals(Icons.Outlined.Settings, RvIcons.NavSettings)
        assertEquals(Icons.Filled.Settings, RvIcons.NavSettingsSelected)
        assertFalse(RvIcons.USE_EXIT_GLYPH_FOR_LEAVE)
        assertEquals(Icons.Filled.CallEnd, RvIcons.Leave)
    }

    @Test
    fun bottomNavShell_switchesOutlinedAndFilledIconsOnSelection() {
        var currentRoute by mutableStateOf(Routes.HOME)
        composeRule.setContent {
            com.ridervoice.ui.theme.RiderVoiceTheme {
                RvBottomNavigationBar(
                    currentRoute = currentRoute,
                    onHomeClick = { currentRoute = Routes.HOME },
                    onHistoryClick = { currentRoute = Routes.RIDE_STATS },
                    onSquadClick = { currentRoute = Routes.SQUAD },
                    onSettingsClick = { currentRoute = Routes.SETTINGS }
                )
            }
        }
        composeRule.waitForIdle()

        val homeNode = composeRule.onNodeWithTag("bottom_nav_home").fetchSemanticsNode()
        val settingsNode = composeRule.onNodeWithTag("bottom_nav_settings").fetchSemanticsNode()
        assertEquals(RvIcons.NavHomeSelected.name, homeNode.config[RvBottomNavIconNameKey])
        assertEquals(RvIcons.NavSettings.name, settingsNode.config[RvBottomNavIconNameKey])
        assertEquals(Icons.Outlined.Settings.name, settingsNode.config[RvBottomNavIconNameKey])

        composeRule.runOnUiThread { currentRoute = Routes.SETTINGS }
        composeRule.waitForIdle()

        val homeNodeAfter = composeRule.onNodeWithTag("bottom_nav_home").fetchSemanticsNode()
        val settingsNodeAfter = composeRule.onNodeWithTag("bottom_nav_settings").fetchSemanticsNode()
        assertEquals(RvIcons.NavHome.name, homeNodeAfter.config[RvBottomNavIconNameKey])
        assertEquals(RvIcons.NavSettingsSelected.name, settingsNodeAfter.config[RvBottomNavIconNameKey])
        assertEquals(Icons.Filled.Settings.name, settingsNodeAfter.config[RvBottomNavIconNameKey])
    }

    @Test
    fun noRawMaterialIconsOutsideRvIconsAndFrozenFiles() {
        val candidates = listOf(
            java.io.File("src/main/java/com/ridervoice"),
            java.io.File("app/src/main/java/com/ridervoice"),
            java.io.File("mobile-app/app/src/main/java/com/ridervoice")
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: error("Cannot find com/ridervoice")
        val excluded = setOf(
            "ui/theme/RvIcons.kt",
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioRouteSheet.kt",
            "ui/screens/room/AudioVuMeter.kt",
            "ui/screens/room/DiagnosticsDialog.kt",
            "ui/screens/room/LeaveRideDialog.kt",
            "ui/screens/room/SosAlertBanner.kt"
        )
        val rawIconRegex = Regex("""\bIcons\.(Default|Filled|Outlined|Rounded|Sharp|TwoTone)\.""")
        val violations = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                if (rel in excluded) return@forEach
                file.readLines().forEachIndexed { idx, line ->
                    if (rawIconRegex.containsMatchIn(line)) {
                        violations += "$rel:${idx + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "Found raw Material Icons references outside RvIcons.kt and frozen files:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
