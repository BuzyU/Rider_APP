package com.ridervoice.ui

import android.app.Application
import android.location.Location
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.PeerTransportKind
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.ui.MeshBottomBar
import com.ridervoice.mesh.ui.MeshPeerCard
import com.ridervoice.models.Participant
import com.ridervoice.models.RiderLocation
import com.ridervoice.network.ConnectionState
import com.ridervoice.ui.components.ParticipantCard
import com.ridervoice.ui.components.PrimaryButton
import com.ridervoice.ui.components.RiderRow
import com.ridervoice.ui.screens.room.RideTopBar
import com.ridervoice.ui.screens.room.RiderList
import com.ridervoice.ui.screens.room.TalkControls
import com.ridervoice.ui.theme.RiderVoiceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * WP-172: Real Compose UI + Robolectric layout verification suite across 320/360/411 dp widths,
 * fontScale 1.0/2.0, landscape (640x320), RTL, 1/6/30 riders, long names, 48dp targets,
 * non-overlapping bounds, zero sibling layout shift on speaking/TX toggle, and zero text clipping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w640dp-h900dp", application = Application::class)
class RoomLobbyLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val longRiderName = "Alexandrina-Maximiliana Von Schwartzenberg-MountainConvoy-Lead-2026"
    private val longRideName = "Transcontinental Alpine Pass Endurance Convoy 2026"

    private fun testLocation(lat: Double, lng: Double, speedMps: Float = 35.5f): Location =
        Location("test").apply {
            latitude = lat
            longitude = lng
            speed = speedMps
        }

    private fun sampleParticipants(count: Int): List<Participant> =
        (1..count).map { i ->
            Participant(
                identity = "rider-$i",
                displayName = if (i == 1) longRiderName else "Rider Alpha-$i LongSquadMember"
            )
        }

    private fun sampleMeshPeers(count: Int, speakingPeerId: String? = null): List<UnifiedVoicePeer> =
        (1..count).map { i ->
            val id = "peer-$i"
            UnifiedVoicePeer(
                id = id,
                name = if (i == 1) longRiderName else "Mesh Rider #$i LongHandle",
                isOnline = true,
                transport = PeerTransportKind.OFFLINE_MESH,
                hopCount = if (i % 2 == 0) 2 else 1,
                nextHopId = if (i % 2 == 0) "peer-1" else null,
                isDirect = i % 2 != 0,
                isRelayed = i % 2 == 0,
                routeAgeMs = 1500L,
                isSpeaking = id == speakingPeerId
            )
        }

    private fun assertNoOverlap(a: DpRect, b: DpRect, labelA: String, labelB: String) {
        val horizontalOverlap = a.left < b.right && b.left < a.right
        val verticalOverlap = a.top < b.bottom && b.top < a.bottom
        assertFalse(
            "$labelA ($a) overlaps $labelB ($b)",
            horizontalOverlap && verticalOverlap
        )
    }

    private fun assertBoundsIdentical(before: DpRect, after: DpRect, label: String) {
        val tol = 0.25f
        assertTrue(
            "$label shifted on toggle: before=$before after=$after",
            abs(before.left.value - after.left.value) <= tol &&
                abs(before.top.value - after.top.value) <= tol &&
                abs(before.right.value - after.right.value) <= tol &&
                abs(before.bottom.value - after.bottom.value) <= tol
        )
    }

    private fun SemanticsNodeInteraction.getTextLayouts(): List<TextLayoutResult> {
        val results = mutableListOf<TextLayoutResult>()
        val node = fetchSemanticsNode()
        val action = node.config[SemanticsActions.GetTextLayoutResult]
        assertTrue("Text node must expose GetTextLayoutResult", action.action?.invoke(results) == true)
        return results
    }

    private fun SemanticsNodeInteraction.assertNoSingleLineClip(label: String) {
        val layouts = getTextLayouts()
        assertTrue("$label produced no TextLayoutResult", layouts.isNotEmpty())
        val layout = layouts.first()
        assertEquals("$label wrapped unexpectedly (${layout.lineCount} lines)", 1, layout.lineCount)
        assertFalse("$label clipped horizontally (didOverflowWidth)", layout.didOverflowWidth)
        assertFalse("$label clipped vertically (didOverflowHeight)", layout.didOverflowHeight)
    }

    private fun assertTouchTarget48dpAndClickable(node: SemanticsNodeInteraction, label: String) {
        node.assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        val b = node.getBoundsInRoot()
        assertTrue("$label width ${b.right - b.left} < 48.dp", (b.right - b.left) >= 48.dp)
        assertTrue("$label height ${b.bottom - b.top} < 48.dp", (b.bottom - b.top) >= 48.dp)
    }

    @Test
    fun rideTopBar_and_talkControls_matrix_320_360_411_landscape_rtl_fontScale2() {
        data class ViewportCase(
            val width: Dp,
            val height: Dp,
            val fontScale: Float,
            val dir: LayoutDirection
        )
        val cases = listOf(
            ViewportCase(320.dp, 640.dp, 1.0f, LayoutDirection.Ltr),
            ViewportCase(320.dp, 640.dp, 2.0f, LayoutDirection.Ltr),
            ViewportCase(360.dp, 720.dp, 1.0f, LayoutDirection.Ltr),
            ViewportCase(411.dp, 891.dp, 2.0f, LayoutDirection.Ltr),
            ViewportCase(640.dp, 320.dp, 1.0f, LayoutDirection.Ltr),
            ViewportCase(360.dp, 640.dp, 2.0f, LayoutDirection.Rtl)
        )

        var caseIdx by mutableIntStateOf(0)
        var activeSpeaker by mutableStateOf<String?>(null)
        var isPttPressed by mutableStateOf(false)
        var isVoxOpen by mutableStateOf(false)
        var isMicEnabled by mutableStateOf(false)
        var isDeafened by mutableStateOf(true)

        composeRule.setContent {
            val c = cases[caseIdx]
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = c.fontScale),
                LocalLayoutDirection provides c.dir
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = c.width, height = c.height)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            RideTopBar(
                                rideName = longRideName,
                                connectionState = ConnectionState.CONNECTED,
                                activeSpeaker = activeSpeaker,
                                currentUid = "rider-1",
                                participants = sampleParticipants(6),
                                activeDevice = AudioDevice.BluetoothSco("Cardo Packtalk Edge Helmet Headset"),
                                routerState = RouterState.ACTIVE,
                                requestedDevice = AudioDevice.BluetoothSco("Cardo Packtalk Edge Helmet Headset"),
                                routedInput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                                routedOutput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                                isCollectingDiag = true,
                                diagSampleCount = 42,
                                onAudioRouteClick = {},
                                onDiagnosticsClick = {},
                                onLeaveClick = {},
                                onRescanAudio = {}
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            TalkControls(
                                isMicEnabled = isMicEnabled,
                                isDeafened = isDeafened,
                                isPttPressed = isPttPressed,
                                isVoxOpen = isVoxOpen,
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
            val c = cases[i]
            caseIdx = i
            activeSpeaker = null
            isPttPressed = false
            isVoxOpen = false
            isMicEnabled = false
            isDeafened = true
            composeRule.waitForIdle()

            val audioBadge = composeRule.onNodeWithTag("ride_topbar_audio_badge")
            val leaveBtn = composeRule.onNodeWithTag("ride_topbar_leave_button")
            val micBtn = composeRule.onNodeWithTag("talk_controls_mic")
            val deafenBtn = composeRule.onNodeWithTag("talk_controls_deafen")
            val pttBtn = composeRule.onNodeWithTag("talk_controls_ptt")
            val sosBtn = composeRule.onNodeWithTag("talk_controls_sos")

            assertTouchTarget48dpAndClickable(audioBadge, "TopBar audio route ($c)")
            assertTouchTarget48dpAndClickable(leaveBtn, "TopBar Leave ($c)")
            assertTouchTarget48dpAndClickable(micBtn, "TalkControls Mic ($c)")
            assertTouchTarget48dpAndClickable(deafenBtn, "TalkControls Deafen ($c)")
            assertTouchTarget48dpAndClickable(pttBtn, "TalkControls PTT ($c)")

            composeRule.onNodeWithTag("talk_controls_mic_label")
                .assertNoSingleLineClip("Mic label ($c)")
            composeRule.onNodeWithTag("talk_controls_deafen_label")
                .assertNoSingleLineClip("Deafen label ($c)")
            composeRule.onNodeWithTag("talk_controls_sos_label")
                .assertNoSingleLineClip("SOS label ($c)")

            val topCenter = composeRule.onNodeWithTag("ride_topbar_center").getBoundsInRoot()
            val topAudioBounds = audioBadge.getBoundsInRoot()
            val topLeaveBounds = leaveBtn.getBoundsInRoot()
            assertNoOverlap(topCenter, topAudioBounds, "TopCenter", "AudioBadge ($c)")
            assertNoOverlap(topAudioBounds, topLeaveBounds, "AudioBadge", "LeaveButton ($c)")

            val micBoundsBefore = micBtn.getBoundsInRoot()
            val deafenBoundsBefore = deafenBtn.getBoundsInRoot()
            val pttBoundsBefore = pttBtn.getBoundsInRoot()
            val sosBoundsBefore = sosBtn.getBoundsInRoot()
            assertNoOverlap(micBoundsBefore, deafenBoundsBefore, "Mic", "Deafen ($c)")
            assertNoOverlap(deafenBoundsBefore, pttBoundsBefore, "Deafen", "PTT ($c)")
            assertNoOverlap(pttBoundsBefore, sosBoundsBefore, "PTT", "SOS ($c)")

            // Toggle activeSpeaker + PTT + VOX + Mic -> 0px shift on sibling bounds
            activeSpeaker = "rider-2"
            isPttPressed = true
            isVoxOpen = true
            isMicEnabled = true
            composeRule.waitForIdle()

            assertBoundsIdentical(topAudioBounds, audioBadge.getBoundsInRoot(), "AudioBadge ($c)")
            assertBoundsIdentical(topLeaveBounds, leaveBtn.getBoundsInRoot(), "LeaveButton ($c)")
            assertBoundsIdentical(micBoundsBefore, micBtn.getBoundsInRoot(), "MicButton ($c)")
            assertBoundsIdentical(deafenBoundsBefore, deafenBtn.getBoundsInRoot(), "DeafenButton ($c)")
            assertBoundsIdentical(pttBoundsBefore, pttBtn.getBoundsInRoot(), "PttButton ($c)")
            assertBoundsIdentical(sosBoundsBefore, sosBtn.getBoundsInRoot(), "SosButton ($c)")
        }
    }

    @Test
    fun participantCard_telemetryDoesNotClip_at320dp_fontScale2_andNoSiblingShiftOnSpeakingToggle() {
        var speaking by mutableStateOf(false)

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = 320.dp, height = 640.dp)) {
                        ParticipantCard(
                            participant = Participant(
                                identity = "rider-1",
                                displayName = longRiderName
                            ),
                            isSpeaking = speaking,
                            isCurrentUser = false,
                            isHost = true,
                            distanceMeters = 14_500f,
                            speedKmh = 128f
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val nameNode = composeRule.onNodeWithTag("participant_name_rider-1")
        val distNode = composeRule.onNodeWithTag("participant_distance_rider-1")
        val speedNode = composeRule.onNodeWithTag("participant_speed_rider-1")

        distNode.assertIsDisplayed().assertNoSingleLineClip("ParticipantCard distance (320dp, fontScale 2.0)")
        speedNode.assertIsDisplayed().assertNoSingleLineClip("ParticipantCard speed (320dp, fontScale 2.0)")

        val nameBoundsBefore = nameNode.getBoundsInRoot()
        val distBoundsBefore = distNode.getBoundsInRoot()
        val speedBoundsBefore = speedNode.getBoundsInRoot()
        assertNoOverlap(nameBoundsBefore, distBoundsBefore, "ParticipantName", "Distance")

        speaking = true
        composeRule.waitForIdle()

        assertBoundsIdentical(nameBoundsBefore, nameNode.getBoundsInRoot(), "ParticipantName")
        assertBoundsIdentical(distBoundsBefore, distNode.getBoundsInRoot(), "ParticipantDistance")
        assertBoundsIdentical(speedBoundsBefore, speedNode.getBoundsInRoot(), "ParticipantSpeed")
    }

    @Test
    fun riderList_supports_1_6_30_riders_and_landscape_and_rtl() {
        val counts = listOf(1, 6, 30)
        var idx by mutableIntStateOf(0)
        val curLoc = testLocation(18.5204, 73.8567, 25f)

        composeRule.setContent {
            val count = counts[idx]
            val list = sampleParticipants(count)
            val remoteLocs = list.associate { p ->
                p.identity to RiderLocation(
                    riderId = p.identity,
                    lat = 18.53,
                    lng = 73.86,
                    speed = 32f,
                    heading = 90f,
                    timestamp = 1_000_000L
                )
            }
            val height = if (count == 30) 640.dp else 320.dp
            val width = if (count == 6) 640.dp else 320.dp
            val dir = if (count == 30) LayoutDirection.Rtl else LayoutDirection.Ltr
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f),
                LocalLayoutDirection provides dir
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = width, height = height)) {
                        RiderList(
                            participants = list,
                            currentUid = "rider-1",
                            activeSpeaker = "rider-1",
                            isCurrentUserTransmitting = true,
                            isHost = true,
                            remoteLocations = remoteLocs,
                            currentLocation = curLoc
                        )
                    }
                }
            }
        }

        for (i in counts.indices) {
            idx = i
            val count = counts[i]
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("rider_list_lazy").performScrollToIndex(0)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("participant_card_rider-1").assertIsDisplayed()
            if (count > 1) {
                composeRule.onNodeWithTag("rider_list_lazy").performScrollToIndex(count - 1)
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("participant_card_rider-$count").assertIsDisplayed()
            }
        }
    }

    @Test
    fun lobbyRosterAndStartButton_noAvatarSpeakingShift_noClip_320_fontScale2_rtl() {
        var speaking by mutableStateOf(false)
        val riders = sampleParticipants(30)

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f),
                LocalLayoutDirection provides LayoutDirection.Rtl
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = 320.dp, height = 640.dp)) {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth().testTag("lobby_rider_list"),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(riders) { r ->
                                    RiderRow(
                                        name = r.displayName ?: r.identity,
                                        subtitle = "Active • Offline Ready • Joined",
                                        status = "Host",
                                        isSpeaking = r.identity == "rider-1" && speaking
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            PrimaryButton(
                                text = "Start ride (30 riders)",
                                onClick = {},
                                modifier = Modifier.testTag("lobby_start_button")
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val startBtn = composeRule.onNodeWithTag("lobby_start_button")
        assertTouchTarget48dpAndClickable(startBtn, "Lobby Start button")
        val listBounds = composeRule.onNodeWithTag("lobby_rider_list").getBoundsInRoot()
        val startBounds = startBtn.getBoundsInRoot()
        assertNoOverlap(listBounds, startBounds, "LobbyRiderList", "StartButton")

        val childAvatarBefore = composeRule.onAllNodes(hasTestTag("rider_avatar_inner"))[0].getBoundsInRoot()
        val nameBefore = composeRule.onAllNodes(hasTestTag("rider_row_name"))[0].getBoundsInRoot()

        speaking = true
        composeRule.waitForIdle()

        val childAvatarAfter = composeRule.onAllNodes(hasTestTag("rider_avatar_inner"))[0].getBoundsInRoot()
        val nameAfter = composeRule.onAllNodes(hasTestTag("rider_row_name"))[0].getBoundsInRoot()

        assertBoundsIdentical(childAvatarBefore, childAvatarAfter, "Lobby RiderAvatar inner bounds")
        assertBoundsIdentical(nameBefore, nameAfter, "Lobby RiderRow name bounds")
        val nameLayouts = composeRule.onAllNodes(hasTestTag("rider_row_name"))[0].getTextLayouts()
        assertEquals(1, nameLayouts.first().lineCount)
        assertFalse(nameLayouts.first().didOverflowHeight)
    }

    @Test
    fun offlineMeshControls_matrix_320_fontScale2_speakingAndPttZeroShift_andNoClip() {
        var speakingPeerId by mutableStateOf<String?>(null)
        var isPttActive by mutableStateOf(false)
        val peers = sampleMeshPeers(6, speakingPeerId)

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                RiderVoiceTheme {
                    Box(modifier = Modifier.size(width = 320.dp, height = 640.dp)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(peers, key = { it.id }) { p ->
                                    MeshPeerCard(
                                        peer = p,
                                        isSpeaking = p.id == speakingPeerId,
                                        allPeers = peers
                                    )
                                }
                            }
                            MeshBottomBar(
                                isMicEnabled = true,
                                isDeafened = false,
                                activeRoute = AudioRoute.BLUETOOTH,
                                isVoxEnabled = true,
                                isPttActive = isPttActive,
                                onToggleMic = {},
                                onToggleDeafen = {},
                                onCycleAudioRoute = {},
                                onToggleVox = {},
                                onPttPressChange = {},
                                onLeave = {}
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val audioBtn = composeRule.onNodeWithTag("mesh_bottom_audio_route")
        val pttBtn = composeRule.onNodeWithTag("mesh_bottom_ptt")
        val leaveBtn = composeRule.onNodeWithTag("mesh_bottom_leave")
        val peerName = composeRule.onNodeWithTag("mesh_peer_name_peer-1")
        val speakingBadge = composeRule.onNodeWithTag("mesh_peer_speaking_peer-1")

        assertTouchTarget48dpAndClickable(audioBtn, "Mesh AudioRoute")
        assertTouchTarget48dpAndClickable(pttBtn, "Mesh PTT")
        assertTouchTarget48dpAndClickable(leaveBtn, "Mesh Leave")

        speakingBadge.assertNoSingleLineClip("MeshPeerCard Speaking badge (320dp, fontScale 2.0)")

        assertNoOverlap(audioBtn.getBoundsInRoot(), pttBtn.getBoundsInRoot(), "MeshAudio", "MeshPTT")
        assertNoOverlap(
            composeRule.onNodeWithTag("mesh_bottom_deafen").getBoundsInRoot(),
            leaveBtn.getBoundsInRoot(),
            "MeshDeafen",
            "MeshLeave"
        )

        val peerNameBefore = peerName.getBoundsInRoot()
        val audioBefore = audioBtn.getBoundsInRoot()
        val pttBefore = pttBtn.getBoundsInRoot()
        val leaveBefore = leaveBtn.getBoundsInRoot()

        speakingPeerId = "peer-1"
        isPttActive = true
        composeRule.waitForIdle()

        assertBoundsIdentical(peerNameBefore, peerName.getBoundsInRoot(), "MeshPeerCard name")
        assertBoundsIdentical(audioBefore, audioBtn.getBoundsInRoot(), "MeshAudioRoute")
        assertBoundsIdentical(pttBefore, pttBtn.getBoundsInRoot(), "MeshPTT")
        assertBoundsIdentical(leaveBefore, leaveBtn.getBoundsInRoot(), "MeshLeave")
    }

    @Test
    fun allLazyListItems_inUiAndMeshUi_provideExplicitKeys() {
        val candidates = listOf(
            java.io.File("src/main/java/com/ridervoice"),
            java.io.File("app/src/main/java/com/ridervoice"),
            java.io.File("mobile-app/app/src/main/java/com/ridervoice")
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: error("Could not locate source root")
        val itemsCallRegex = Regex("""\bitems(?:Indexed)?\s*\(""")
        val violations = mutableListOf<String>()

        for (sub in listOf("ui", "mesh/ui")) {
            java.io.File(root, sub).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val lines = file.readLines()
                    val rel = file.relativeTo(root).path.replace('\\', '/')
                    lines.forEachIndexed { idx, line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                        if (itemsCallRegex.containsMatchIn(line)) {
                            val snippet = lines.subList(idx, minOf(idx + 5, lines.size)).joinToString(" ")
                            if (!snippet.contains("key =") && !snippet.contains("key=")) {
                                violations += "$rel:${idx + 1}: ${trimmed}"
                            }
                        }
                    }
                }
        }

        assertTrue(
            "Found keyless LazyColumn/LazyRow items():\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun keyedLazyColumn_preservesItemStateOnPrepend_and_riderListOrderDoesNotJumpOnSpeakerChange() {
        var nextSlotSeq = 0
        var itemsList by mutableStateOf(listOf("alpha", "beta", "gamma"))
        var activeSpeaker by mutableStateOf<String?>(null)
        val participants = sampleParticipants(6)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RiderVoiceTheme {
                Column(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                    LazyColumn(modifier = Modifier.height(140.dp)) {
                        items(itemsList, key = { it }) { id ->
                            val seq = remember { nextSlotSeq++ }
                            androidx.compose.material3.Text(
                                text = "${id}_seq$seq",
                                modifier = Modifier.testTag("keyed_$id")
                            )
                        }
                    }
                    RiderList(
                        participants = participants,
                        currentUid = "rider-1",
                        activeSpeaker = activeSpeaker,
                        isCurrentUserTransmitting = false,
                        isHost = true,
                        remoteLocations = emptyMap(),
                        currentLocation = null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val initialAlphaText = composeRule.onNodeWithTag("keyed_alpha").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")
        assertTrue("Expected alpha_seq0, got $initialAlphaText", initialAlphaText == "alpha_seq0")

        val initialCardTops = participants.take(4).associate { p ->
            p.identity to composeRule.onNodeWithTag("participant_card_${p.identity}").getBoundsInRoot().top
        }

        // Prepend a new item and toggle activeSpeaker to rider-3
        itemsList = listOf("zero", "alpha", "beta", "gamma")
        activeSpeaker = "rider-3"
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("keyed_zero").assertIsDisplayed()
        val afterAlphaText = composeRule.onNodeWithTag("keyed_alpha").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")
        assertTrue(
            "Keyed LazyColumn must preserve alpha's remembered slot state on prepend (got $afterAlphaText)",
            afterAlphaText == initialAlphaText
        )

        // Verify no rider card swapped positions on activeSpeaker change
        for ((id, topBefore) in initialCardTops) {
            val topAfter = composeRule.onNodeWithTag("participant_card_$id").getBoundsInRoot().top
            assertTrue(
                "Rider $id reordered on speaker change: before=$topBefore after=$topAfter",
                kotlin.math.abs(topBefore.value - topAfter.value) <= 0.5f
            )
        }
    }

    @Test
    fun eightRiders_activeSpeakerChange_recomposesAtMostOldAndNewSpeakerCards() {
        val participants = sampleParticipants(8)
        var activeSpeaker by mutableStateOf<String?>("rider-2")
        val composeCounts = mutableMapOf<String, Int>()
        val callbacks = participants.associate { p ->
            p.identity to {
                composeCounts[p.identity] = (composeCounts[p.identity] ?: 0) + 1
            }
        }

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RiderVoiceTheme {
                LazyColumn(modifier = Modifier.size(width = 360.dp, height = 960.dp)) {
                    items(participants, key = { it.identity }) { participant ->
                        com.ridervoice.ui.components.ParticipantCard(
                            participant = participant,
                            isSpeaking = activeSpeaker == participant.identity,
                            isCurrentUser = participant.identity == "rider-1",
                            isHost = participant.identity == "rider-1",
                            onComposed = callbacks.getValue(participant.identity)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val initialCounts = composeCounts.toMap()
        assertTrue("Expected all 8 cards composed initially", initialCounts.size == 8)

        // Change activeSpeaker from rider-2 to rider-5
        activeSpeaker = "rider-5"
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        val recomposedRiders = participants.map { it.identity }.filter { id ->
            (composeCounts[id] ?: 0) > (initialCounts[id] ?: 0)
        }
        assertTrue(
            "Expected at most 2 cards (old/new speaker) to recompose, but recomposed: $recomposedRiders",
            recomposedRiders.size <= 2 &&
                recomposedRiders.all { it == "rider-2" || it == "rider-5" }
        )
    }

    @Test
    fun composeStabilityConf_containsNoWildcardsCollectionsOrMutableClasses() {
        val confFile = listOf(
            java.io.File("compose-stability.conf"),
            java.io.File("app/compose-stability.conf"),
            java.io.File("mobile-app/app/compose-stability.conf")
        ).firstOrNull { it.exists() } ?: error("Could not find compose-stability.conf")

        val entries = confFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }

        assertTrue("compose-stability.conf must declare explicit immutable entries", entries.isNotEmpty())
        val forbiddenSubstrings = listOf(
            "*",
            "kotlin.collections.",
            "java.util.",
            "android.location.Location",
            "RideSession"
        )
        for (entry in entries) {
            for (bad in forbiddenSubstrings) {
                assertTrue(
                    "compose-stability.conf entry '$entry' contains forbidden pattern '$bad'",
                    !entry.contains(bad)
                )
            }
            assertTrue(
                "compose-stability.conf entry '$entry' must be an explicit com.ridervoice.* FQCN",
                entry.startsWith("com.ridervoice.")
            )
        }
    }
}
