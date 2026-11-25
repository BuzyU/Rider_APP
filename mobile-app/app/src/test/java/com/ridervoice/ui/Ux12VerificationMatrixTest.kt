package com.ridervoice.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.models.Participant
import com.ridervoice.navigation.Routes
import com.ridervoice.network.ConnectionState
import com.ridervoice.ui.components.RvBottomNavigationBar
import com.ridervoice.ui.screens.SosScreen
import com.ridervoice.ui.screens.room.RideTopBar
import com.ridervoice.ui.screens.room.RiderList
import com.ridervoice.ui.screens.room.TalkControls
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.theme.ThemeState
import com.ridervoice.ui.viewmodels.SosState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 33], qualifiers = "w411dp-h891dp", application = Application::class)
class Ux12VerificationMatrixTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun resetTheme() {
        ThemeState.set(false)
    }

    private fun sampleParticipants(count: Int): List<Participant> =
        (1..count).map { i ->
            Participant(
                identity = "rider-$i",
                displayName = if (i == 1) "Lead Rider" else "Squad Rider $i"
            )
        }

    @Test
    fun rideHudMatrix_acrossViewportsCutoutsNavModesThemesLocalesAndCounts() {
        data class ViewportProfile(
            val label: String,
            val w: Dp,
            val h: Dp,
            val statusInsetDp: Float,
            val navInsetDp: Float,
            val cutoutSideDp: Float
        )

        val viewports = listOf(
            ViewportProfile("compact-320", 320.dp, 640.dp, 24f, 48f, 0f),
            ViewportProfile("portrait-gesture", 360.dp, 720.dp, 32f, 24f, 0f),
            ViewportProfile("cutout-3button", 411.dp, 891.dp, 38f, 48f, 24f),
            ViewportProfile("landscape-gesture", 640.dp, 360.dp, 24f, 24f, 28f)
        )
        val themes = listOf(false, true)
        val locales = listOf(Locale("en"), Locale("hi"), Locale("mr"))
        val fontScales = listOf(1.0f, 1.3f, 2.0f)
        val riderCounts = listOf(1, 2, 8, 20)

        var vpIdx by mutableIntStateOf(0)
        var localeIdx by mutableIntStateOf(0)
        var scaleIdx by mutableIntStateOf(0)
        var countIdx by mutableIntStateOf(0)
        var hostView: View? = null
        var densityPx = 1f

        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            composeRule.setContent {
                hostView = LocalView.current
                val baseDensity = LocalDensity.current
                densityPx = baseDensity.density
                val vp = viewports[vpIdx]
                val cfg = Configuration(LocalContext.current.resources.configuration).apply {
                    setLocale(locales[localeIdx])
                }
                CompositionLocalProvider(
                    LocalConfiguration provides cfg,
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScales[scaleIdx])
                ) {
                    RiderVoiceTheme {
                        Box(modifier = Modifier.size(width = vp.w, height = vp.h)) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                RideTopBar(
                                    rideName = "Sahyadri Pass",
                                    connectionState = ConnectionState.CONNECTED,
                                    activeSpeaker = "rider-1",
                                    currentUid = "rider-1",
                                    participants = sampleParticipants(riderCounts[countIdx]),
                                    activeDevice = AudioDevice.BluetoothSco("Cardo"),
                                    routerState = RouterState.ACTIVE,
                                    requestedDevice = AudioDevice.BluetoothSco("Cardo"),
                                    routedInput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                                    routedOutput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                                    isCollectingDiag = true,
                                    diagSampleCount = 7,
                                    onAudioRouteClick = {},
                                    onDiagnosticsClick = {},
                                    onLeaveClick = {},
                                    onRescanAudio = {}
                                )
                                RiderList(
                                    participants = sampleParticipants(riderCounts[countIdx]),
                                    currentUid = "rider-1",
                                    activeSpeaker = "rider-1",
                                    isCurrentUserTransmitting = true,
                                    isHost = true,
                                    remoteLocations = emptyMap(),
                                    currentLocation = null,
                                    modifier = Modifier.weight(1f)
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
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            composeRule.waitForIdle()

            for (v in viewports.indices) {
                val vp = viewports[v]
                for (dark in themes) {
                    for (l in locales.indices) {
                        for (s in fontScales.indices) {
                            for (c in riderCounts.indices) {
                                vpIdx = v
                                localeIdx = l
                                scaleIdx = s
                                countIdx = c
                                ThemeState.set(dark)
                                composeRule.waitForIdle()
                                composeRule.runOnUiThread {
                                    val view = hostView ?: return@runOnUiThread
                                    val statusPx = (vp.statusInsetDp * densityPx).toInt()
                                    val navPx = (vp.navInsetDp * densityPx).toInt()
                                    val cutoutPx = (vp.cutoutSideDp * densityPx).toInt()
                                    if (Build.VERSION.SDK_INT >= 30) {
                                        val insets = WindowInsetsCompat.Builder()
                                            .setInsets(
                                                WindowInsetsCompat.Type.statusBars(),
                                                Insets.of(0, statusPx, 0, 0)
                                            )
                                            .setInsets(
                                                WindowInsetsCompat.Type.navigationBars(),
                                                Insets.of(0, 0, 0, navPx)
                                            )
                                            .setInsets(
                                                WindowInsetsCompat.Type.displayCutout(),
                                                Insets.of(cutoutPx, 0, cutoutPx, 0)
                                            )
                                            .build()
                                        ViewCompat.dispatchApplyWindowInsets(view, insets)
                                    } else {
                                        val platformInsets = WindowInsets::class.java
                                            .getConstructor(Rect::class.java)
                                            .newInstance(Rect(0, statusPx, 0, navPx))
                                        val compatInsets = WindowInsetsCompat.toWindowInsetsCompat(platformInsets)
                                        val listener = view.getTag(androidx.core.R.id.tag_on_apply_window_listener)
                                            as? androidx.core.view.OnApplyWindowInsetsListener
                                        listener?.onApplyWindowInsets(view, compatInsets)
                                    }
                                }
                                composeRule.waitForIdle()

                                val topLeft = composeRule.onNodeWithTag("ride_topbar_left").getBoundsInRoot()
                                val pttBounds = composeRule.onNodeWithTag("talk_controls_ptt").getBoundsInRoot()
                                assertTrue(
                                    "TopBar top (${topLeft.top}) < statusInset (${vp.statusInsetDp}) on ${vp.label}",
                                    topLeft.top >= (vp.statusInsetDp - 0.5f).dp
                                )
                                assertTrue(
                                    "PTT bottom (${pttBounds.bottom}) > navBarTop (${vp.h.value - vp.navInsetDp}) on ${vp.label}",
                                    pttBounds.bottom <= (vp.h.value - vp.navInsetDp + 0.5f).dp
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test
    fun primaryControls_haveTalkBackSemanticsAnd48dpBounds_includingBottomNavAndSos() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RiderVoiceTheme {
                Column(modifier = Modifier.size(width = 360.dp, height = 800.dp)) {
                    RideTopBar(
                        rideName = "Night Convoy",
                        connectionState = ConnectionState.RECONNECTING,
                        activeSpeaker = null,
                        currentUid = "rider-1",
                        participants = sampleParticipants(2),
                        activeDevice = AudioDevice.Speakerphone,
                        routerState = RouterState.ACTIVE,
                        requestedDevice = AudioDevice.Speakerphone,
                        routedInput = RoutedDeviceInfo(typeName = "MIC", productName = "Mic"),
                        routedOutput = RoutedDeviceInfo(typeName = "SPK", productName = "Speaker"),
                        isCollectingDiag = true,
                        diagSampleCount = 3,
                        onAudioRouteClick = {},
                        onDiagnosticsClick = {},
                        onLeaveClick = {},
                        onRescanAudio = {}
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
                        onSosClick = {},
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    RvBottomNavigationBar(
                        currentRoute = Routes.HOME,
                        onHomeClick = {},
                        onHistoryClick = {},
                        onSquadClick = {},
                        onSettingsClick = {}
                    )
                    Box(modifier = Modifier.height(260.dp)) {
                        SosScreen(
                            state = SosState.Idle,
                            onCancelClick = {},
                            onSend = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val interactiveTags = listOf(
            "ride_topbar_audio_badge",
            "ride_topbar_leave_button",
            "ride_topbar_diag_pill",
            "talk_controls_mic",
            "talk_controls_deafen",
            "talk_controls_ptt",
            "talk_controls_sos",
            "bottom_nav_home",
            "bottom_nav_history",
            "bottom_nav_squad",
            "bottom_nav_settings"
        )
        for (tag in interactiveTags) {
            val node = composeRule.onNodeWithTag(tag)
            node.assertIsDisplayed()
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            val cfg = node.fetchSemanticsNode().config
            val hasLabel = cfg.contains(SemanticsProperties.ContentDescription) || cfg.contains(SemanticsProperties.Text)
            assertTrue("Control '$tag' must expose TalkBack label/contentDescription", hasLabel)
        }
    }
}
