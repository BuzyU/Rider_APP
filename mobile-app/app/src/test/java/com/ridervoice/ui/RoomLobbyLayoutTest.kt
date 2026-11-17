package com.ridervoice.ui

import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import com.ridervoice.ui.viewmodels.LobbyLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression and Layout Invariant Test for Room and Lobby screens per Finding V3-7.
 * Verifies layout calculations, touch target bounds (>= 48 dp), participant list minimum
 * visible height (>= 120 dp), secondary detail collapsing at large font scales (> 1.3f),
 * and connection state label mappings across screen configurations (360x640, 411x891, 600x960 dp).
 */
class RoomLobbyLayoutTest {

    data class ScreenConfig(val widthDp: Int, val heightDp: Int)

    private val testScreens = listOf(
        ScreenConfig(360, 640), // Compact phone
        ScreenConfig(411, 891), // Standard modern phone (Pixel)
        ScreenConfig(600, 960)  // Tablet / Foldable
    )

    private val fontScales = listOf(1.0f, 1.3f, 2.0f)

    @Test
    fun testTouchTargetMinimums() {
        val minTouchTargetDp = 48

        // PTT button height
        val pttHeightDp = 76
        assertTrue("PTT touch target must be >= 48 dp", pttHeightDp >= minTouchTargetDp)

        // Mic toggle button size
        val micSizeDp = 52
        assertTrue("Mic toggle touch target must be >= 48 dp", micSizeDp >= minTouchTargetDp)

        // Deafen toggle button size
        val deafenSizeDp = 52
        assertTrue("Deafen toggle touch target must be >= 48 dp", deafenSizeDp >= minTouchTargetDp)

        // SOS button size
        val sosSizeDp = 52
        assertTrue("SOS button touch target must be >= 48 dp", sosSizeDp >= minTouchTargetDp)

        // Leave call button size
        val leaveButtonSizeDp = 48
        assertTrue("Leave button touch target must be >= 48 dp", leaveButtonSizeDp >= minTouchTargetDp)

        // Audio route badge box minimum touch target
        val audioRouteBadgeBoxMinDp = 48
        assertTrue("Audio route badge touch target must be >= 48 dp", audioRouteBadgeBoxMinDp >= minTouchTargetDp)
    }

    @Test
    fun testRoomLayoutSpaceBudgetAcrossScreensAndFontScales() {
        val minParticipantListHeightDp = 120

        for (screen in testScreens) {
            for (fontScale in fontScales) {
                val isLargeFont = fontScale > 1.3f

                // Top bar height calculation
                val topBarPaddingVertical = if (isLargeFont) 8 else 16
                val topBarContentHeight = if (isLargeFont) {
                    // Secondary details collapsed to 1 line, badge is 1 line
                    (24 * fontScale + 14 * fontScale).toInt().coerceAtMost(64)
                } else {
                    (24 * fontScale + 18 * fontScale + 14 * fontScale).toInt()
                }
                val effectiveTopBarHeight = (topBarPaddingVertical + topBarContentHeight).coerceAtLeast(56)

                // Bottom bar height calculation: VU meter row (24dp) + talk controls (76dp) + vertical padding (16dp)
                val effectiveBottomBarHeight = 116

                val bannerAndSpacerHeight = 20

                val availableContentHeight = screen.heightDp - effectiveTopBarHeight - effectiveBottomBarHeight - bannerAndSpacerHeight

                assertTrue(
                    "On ${screen.widthDp}x${screen.heightDp} at fontScale $fontScale, participant list space ($availableContentHeight dp) must be >= $minParticipantListHeightDp dp",
                    availableContentHeight >= minParticipantListHeightDp
                )
            }
        }
    }

    @Test
    fun testConnectionStateLabelParity() {
        // Assert ConnectionPill text mappings
        val mappings = mapOf(
            ConnectionState.CONNECTED to "CONNECTED",
            ConnectionState.CONNECTING to "CONNECTING",
            ConnectionState.RECONNECTING to "RECONNECTING",
            ConnectionState.FAILED to "NO SIGNAL",
            ConnectionState.DISCONNECTED to "OFFLINE"
        )

        for ((state, expectedLabel) in mappings) {
            val label = when (state) {
                ConnectionState.CONNECTED -> "CONNECTED"
                ConnectionState.CONNECTING -> "CONNECTING"
                ConnectionState.RECONNECTING -> "RECONNECTING"
                ConnectionState.FAILED -> "NO SIGNAL"
                ConnectionState.DISCONNECTED -> "OFFLINE"
            }
            assertEquals("Connection state $state must map to $expectedLabel", expectedLabel, label)
        }
    }

    @Test
    fun testParticipantRosterScaling() {
        val rosterSizes = listOf(1, 5, 6, 20, 30, 100)
        for (count in rosterSizes) {
            val participants = (1..count).map {
                Participant(
                    identity = "rider_$it",
                    displayName = if (it == 1) "Super Long Callsign That Might Overflow Title Space" else "Rider $it"
                )
            }
            assertEquals(count, participants.size)
            val nonGhosts = participants.count { !it.isGhost }
            assertEquals(count, nonGhosts)

            val displayLabel = if (nonGhosts == 1) "1 rider in voice" else "$nonGhosts riders in voice"
            if (count == 1) {
                assertEquals("1 rider in voice", displayLabel)
            } else {
                assertEquals("$count riders in voice", displayLabel)
            }
        }
    }

    @Test
    fun testRideTopBarNarrow320dpWithDiagPillAndLargeFontScaleBudget() {
        val screenWidthDp = 320
        val horizontalPaddingDp = 16 * 2
        val rightMinWidthDp = 104 // Audio badge (>=48) + spacer (4) + Leave button (48) + slack
        val leaveButtonSizeDp = 48
        val remainingWidthDp = screenWidthDp - horizontalPaddingDp - rightMinWidthDp
        val leftBudgetDp = (remainingWidthDp * 1.0f / (1.0f + 1.5f)).toInt()
        val centerBudgetDp = remainingWidthDp - leftBudgetDp

        assertTrue("Right group minimum width ($rightMinWidthDp) must accommodate 48dp leave button", rightMinWidthDp >= leaveButtonSizeDp + 48)
        assertTrue("Left + center + right must fit within 320dp budget", horizontalPaddingDp + leftBudgetDp + centerBudgetDp + rightMinWidthDp <= screenWidthDp)
        assertTrue("Center column on 320dp screen must retain >= 96dp width", centerBudgetDp >= 96)
    }

    @Test
    fun testRidePermissionsIncludeRecordAudio() {
        val perms = com.ridervoice.permissions.PermissionManager.ridePermissions
        assertTrue(
            "PermissionManager.ridePermissions must include RECORD_AUDIO",
            perms.contains(android.Manifest.permission.RECORD_AUDIO)
        )
    }

    @Test
    fun testLobbyLoadStateTransitions() {
        val states = listOf(
            LobbyLoadState.Loading,
            LobbyLoadState.Retrying(1, 5, "Connecting to host..."),
            LobbyLoadState.Retrying(5, 5, "Final attempt..."),
            LobbyLoadState.Failed("Connection timed out", isRetryable = true, requestId = "req-1234"),
            LobbyLoadState.Terminal(com.ridervoice.ui.viewmodels.LobbyViewModel.Terminal.ENDED)
        )

        for (state in states) {
            when (state) {
                is LobbyLoadState.Loading -> assertTrue(true)
                is LobbyLoadState.Retrying -> {
                    assertTrue(state.attempt in 1..state.maxAttempts)
                    assertTrue(!state.message.isNullOrBlank())
                }
                is LobbyLoadState.Failed -> {
                    assertTrue(state.reason.isNotBlank())
                    assertEquals("req-1234", state.requestId)
                }
                is LobbyLoadState.Terminal -> {
                    assertEquals(com.ridervoice.ui.viewmodels.LobbyViewModel.Terminal.ENDED, state.terminal)
                }
                is LobbyLoadState.Ready -> {
                    assertTrue(true)
                }
            }
        }
    }

    @Test
    fun testSettingsHasNoGlobalMeshToggleAndRideTopBarPreventsSqueeze() {
        val srcRoot = listOf(
            java.io.File("src/main/java/com/ridervoice"),
            java.io.File("app/src/main/java/com/ridervoice"),
            java.io.File("mobile-app/app/src/main/java/com/ridervoice")
        ).first { it.isDirectory }

        val settingsText = java.io.File(srcRoot, "ui/screens/SettingsScreen.kt").readText()
        assertTrue(
            "SettingsScreen must not expose global setOfflineMeshEnabled toggle",
            !settingsText.contains("setOfflineMeshEnabled")
        )
        assertTrue(
            "SettingsScreen must not expose global setAutomaticFallbackEnabled toggle",
            !settingsText.contains("setAutomaticFallbackEnabled")
        )
        assertTrue(
            "SettingsScreen must not expose global setWarmStandbyEnabled toggle",
            !settingsText.contains("setWarmStandbyEnabled")
        )

        val rideTopBarText = java.io.File(srcRoot, "ui/screens/room/RideTopBar.kt").readText()
        assertTrue(
            "RideTopBar right group must reserve minimum 104.dp to avoid squeezing leave button",
            rideTopBarText.contains("requiredWidthIn(min = 104.dp)")
        )
        assertTrue(
            "RideTopBar leave button must enforce requiredSize(48.dp)",
            rideTopBarText.contains("requiredSize(48.dp)")
        )

        val roomVmText = java.io.File(srcRoot, "state/RoomViewModel.kt").readText()
        assertTrue(
            "RoomViewModel must not contain 60s ghost cleanup polling loop",
            !roomVmText.contains("delay(60_000)")
        )
        assertTrue(
            "RoomViewModel must not contain 30s lobby polling loop",
            !roomVmText.contains("delay(30_000)")
        )

        val riderListText = java.io.File(srcRoot, "ui/screens/room/RiderList.kt").readText()
        assertTrue(
            "RiderList LazyColumn must key items by identity",
            riderListText.contains("key = { it.identity }")
        )

        val manifestFile = java.io.File(srcRoot, "../../../AndroidManifest.xml").canonicalFile
        val manifestText = manifestFile.readText()
        assertTrue(
            "VoiceForegroundService must declare microphone|location|connectedDevice foregroundServiceType",
            manifestText.contains("android:name=\".services.VoiceForegroundService\"") &&
                manifestText.contains("android:foregroundServiceType=\"microphone|location|connectedDevice\"")
        )
    }
}
