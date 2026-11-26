package com.ridervoice.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.RoutedDeviceInfo
import com.ridervoice.audio.RouterState
import com.ridervoice.mesh.ui.MeshSettingsViewModel
import com.ridervoice.models.Friend
import com.ridervoice.models.LobbyStatus
import com.ridervoice.models.Participant
import com.ridervoice.navigation.Routes
import com.ridervoice.network.ConnectionState
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.components.RvBottomNavigationBar
import com.ridervoice.ui.screens.HomeScreen
import com.ridervoice.ui.screens.LobbyScreen
import com.ridervoice.ui.screens.SettingsScreen
import com.ridervoice.ui.screens.SquadScreen
import com.ridervoice.ui.screens.room.RideTopBar
import com.ridervoice.ui.screens.room.RiderList
import com.ridervoice.ui.screens.room.TalkControls
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.viewmodels.AppSettingsState
import com.ridervoice.ui.viewmodels.AuthViewModel
import com.ridervoice.ui.viewmodels.HomeState
import com.ridervoice.ui.viewmodels.HomeViewModel
import com.ridervoice.ui.viewmodels.LobbyLoadState
import com.ridervoice.ui.viewmodels.LobbyViewModel
import com.ridervoice.ui.viewmodels.SettingsViewModel
import com.ridervoice.ui.viewmodels.SquadUiState
import com.ridervoice.ui.viewmodels.SquadViewModel
import com.ridervoice.ui.viewmodels.UpdateViewModel
import com.ridervoice.update.UpdateUiState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class AccessibilitySemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun collectNodes(root: SemanticsNode, out: MutableList<SemanticsNode> = mutableListOf()): List<SemanticsNode> {
        out += root
        root.children.forEach { collectNodes(it, out) }
        return out
    }

    private fun androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(
        key: androidx.compose.ui.semantics.SemanticsPropertyKey<*>
    ): Any? = if (contains(key)) get(key) else null

    private fun assertNoRolelessEnabledClickables(screenName: String) {
        val rootNode = composeRule.onRoot().fetchSemanticsNode()
        val offenders = collectNodes(rootNode).filter { node ->
            val cfg = node.config
            SemanticsActions.OnClick in cfg &&
                SemanticsProperties.Disabled !in cfg &&
                SemanticsProperties.Role !in cfg &&
                SemanticsProperties.EditableText !in cfg &&
                SemanticsProperties.ToggleableState !in cfg
        }
        assertTrue(
            "$screenName has ${offenders.size} enabled clickable node(s) without an explicit Role: " +
                offenders.map {
                    it.config.getOrNull(SemanticsProperties.Text)
                        ?: it.config.getOrNull(SemanticsProperties.ContentDescription)
                        ?: it.layoutInfo
                },
            offenders.isEmpty()
        )
    }

    @Test
    fun bottomNavBar_exposesSelectableTabsWithRoleTab() {
        composeRule.setContent {
            RiderVoiceTheme {
                RvBottomNavigationBar(
                    currentRoute = Routes.SQUAD,
                    onHomeClick = {},
                    onHistoryClick = {},
                    onSquadClick = {},
                    onSettingsClick = {}
                )
            }
        }

        val tabRoleMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onAllNodes(tabRoleMatcher).assertCountEquals(4)
        composeRule.onNodeWithTag("bottom_nav_squad").assertIsSelected()
    }

    @Test
    fun settingsScreen_exposesSingleSwitchNodesAndHeadingsAndZeroRolelessClickables() {
        val settingsStateFlow = MutableStateFlow(AppSettingsState())
        val disclosureFlow = MutableStateFlow(false)
        val updateStateFlow = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
        val settingsVm = mockk<SettingsViewModel>(relaxed = true) {
            every { settingsState } returns settingsStateFlow
            every { showDiagnosticDisclosure } returns disclosureFlow
        }
        val authVm = mockk<AuthViewModel>(relaxed = true)
        val updateVm = mockk<UpdateViewModel>(relaxed = true) {
            every { uiState } returns updateStateFlow
            every { currentVersionName } returns "0.0.3.25"
        }
        val meshVm = mockk<MeshSettingsViewModel>(relaxed = true)

        composeRule.setContent {
            RiderVoiceTheme {
                SettingsScreen(
                    settingsViewModel = settingsVm,
                    authViewModel = authVm,
                    updateViewModel = updateVm,
                    meshSettingsViewModel = meshVm,
                    onBackClick = {}
                )
            }
        }
        composeRule.waitForIdle()

        val headingNodes = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertTrue("SettingsScreen must expose headings", headingNodes.fetchSemanticsNodes().size >= 2)

        val switchNodes = composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        ).fetchSemanticsNodes()
        assertTrue("SettingsScreen must expose merged Role.Switch controls", switchNodes.size >= 3)
        switchNodes.forEach { sw ->
            assertTrue("Switch node must expose ToggleableState", SemanticsProperties.ToggleableState in sw.config)
            assertTrue("Merged switch row must include label text", sw.config.contains(SemanticsProperties.Text))
        }

        assertNoRolelessEnabledClickables("SettingsScreen")
    }

    @Test
    fun homeScreen_exposesHeadingsAndZeroRolelessClickables() {
        val homeVm = mockk<HomeViewModel>(relaxed = true) {
            every { uiState } returns MutableStateFlow(HomeState())
        }
        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        every { mockAuth.currentUser } returns null

        mockkStatic(FirebaseAuth::class)
        try {
            every { FirebaseAuth.getInstance() } returns mockAuth

            composeRule.setContent {
                RiderVoiceTheme {
                    HomeScreen(
                        viewModel = homeVm,
                        onHostRideClick = {},
                        onJoinRideClick = {},
                        onStartRideClick = {},
                        onSquadClick = {},
                        onSettingsClick = {},
                        onRoutePlannerClick = {},
                        onRideHistoryClick = {},
                        onDeviceSetupClick = {},
                        onInvitesInboxClick = {}
                    )
                }
            }
            composeRule.waitForIdle()
            assertTrue(composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().size >= 2)
            assertNoRolelessEnabledClickables("HomeScreen")
        } finally {
            unmockkStatic(FirebaseAuth::class)
        }
    }

    @Test
    fun squadScreen_exposesHeadingsAndZeroRolelessClickables() {
        val squadVm = mockk<SquadViewModel>(relaxed = true) {
            every { uiState } returns MutableStateFlow(
                SquadUiState(
                    isLoading = false,
                    hasLoadedOnce = true,
                    friends = listOf(
                        Friend(
                            id = "u1",
                            handle = "asha",
                            displayName = "Asha",
                            bikeModel = "RE Himalayan"
                        )
                    )
                )
            )
        }
        val mockUser = mockk<FirebaseUser>(relaxed = true) {
            every { uid } returns "me"
        }
        val mockAuth = mockk<FirebaseAuth>(relaxed = true) {
            every { currentUser } returns mockUser
        }
        mockkStatic(FirebaseAuth::class)
        try {
            every { FirebaseAuth.getInstance() } returns mockAuth
            composeRule.setContent {
                RiderVoiceTheme {
                    SquadScreen(
                        onBackClick = {},
                        onJoinRoom = { _ -> },
                        onOpenRequests = {},
                        viewModel = squadVm
                    )
                }
            }
            composeRule.waitForIdle()
            assertTrue(composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().size >= 2)
            assertNoRolelessEnabledClickables("SquadScreen")
        } finally {
            unmockkStatic(FirebaseAuth::class)
        }
    }

    @Test
    fun lobbyScreen_exposesHeadingsSwitchesAndRoles() {
        val lobbyStatus = LobbyStatus(
            roomId = "room-1",
            convoyName = "Konkan",
            isHost = true,
            joinCode = "123456",
            invites = emptyList()
        )
        val lobbyVm = mockk<LobbyViewModel>(relaxed = true) {
            every { loadState } returns MutableStateFlow(LobbyLoadState.Ready(lobbyStatus))
            every { this@mockk.lobbyStatus } returns MutableStateFlow(lobbyStatus)
            every { activeRideState } returns MutableStateFlow<ActiveRideRepository.ActiveRide?>(null)
            every { error } returns MutableStateFlow<String?>(null)
            every { terminal } returns MutableStateFlow<LobbyViewModel.Terminal?>(null)
        }
        val homeVm = mockk<HomeViewModel>(relaxed = true) {
            every { uiState } returns MutableStateFlow(HomeState())
        }
        composeRule.setContent {
            RiderVoiceTheme {
                LobbyScreen(
                    convoyName = "Konkan",
                    viewModel = lobbyVm,
                    homeViewModel = homeVm,
                    onStartRide = {},
                    onBackClick = {}
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().isNotEmpty())
        assertNoRolelessEnabledClickables("LobbyScreen")
    }

    @Test
    fun rideRoomControls_exposePoliteLiveRegionsAndDeterministicTraversalIndices() {
        val participants = listOf(Participant(identity = "r1", displayName = "Asha"))
        composeRule.setContent {
            RiderVoiceTheme {
                Column {
                    RideTopBar(
                        rideName = "Konkan",
                        connectionState = ConnectionState.CONNECTED,
                        activeSpeaker = "r1",
                        participants = participants,
                        currentUid = "me",
                        activeDevice = AudioDevice.BluetoothSco("Cardo Packtalk Edge"),
                        routerState = RouterState.ACTIVE,
                        requestedDevice = AudioDevice.BluetoothSco("Cardo Packtalk Edge"),
                        routedInput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                        routedOutput = RoutedDeviceInfo(typeName = "BT", productName = "Cardo"),
                        diagSampleCount = 0,
                        isCollectingDiag = false,
                        onAudioRouteClick = {},
                        onDiagnosticsClick = {},
                        onLeaveClick = {},
                        onRescanAudio = {}
                    )
                    RiderList(
                        participants = participants,
                        currentUid = "me",
                        activeSpeaker = "r1",
                        isCurrentUserTransmitting = false,
                        isHost = true,
                        remoteLocations = emptyMap(),
                        currentLocation = null
                    )
                    TalkControls(
                        isMicEnabled = true,
                        isPttPressed = false,
                        isVoxOpen = false,
                        isDeafened = false,
                        onToggleMic = {},
                        onPttPressStart = {},
                        onPttPressEnd = {},
                        onToggleDeafen = {},
                        onSosClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val politeNodes = composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        ).fetchSemanticsNodes()
        assertTrue("RideTopBar must mark connection pill and speaker subtitle as Polite live regions", politeNodes.size >= 2)

        val statusIdx = composeRule.onNodeWithTag("ride_topbar_left").fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        val listIdx = composeRule.onNodeWithTag("rider_list_lazy").fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        val micIdx = composeRule.onNodeWithTag("talk_controls_mic").fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        val sosIdx = composeRule.onNodeWithTag("talk_controls_sos").fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        val leaveIdx = composeRule.onNodeWithTag("ride_topbar_leave_button").fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]

        assertTrue(
            "Expected ordered traversal indices: status($statusIdx) < list($listIdx) < mic($micIdx) < sos($sosIdx) < leave($leaveIdx)",
            statusIdx < listIdx && listIdx < micIdx && micIdx < sosIdx && sosIdx < leaveIdx
        )
    }

    @Test
    fun allClickablesInNonFrozenUi_specifyExplicitRole() {
        val javaRoot = listOf(
            java.io.File("src/main/java/com/ridervoice"),
            java.io.File("app/src/main/java/com/ridervoice"),
            java.io.File("mobile-app/app/src/main/java/com/ridervoice")
        ).firstOrNull { it.exists() } ?: error("Could not locate com/ridervoice source root")

        val frozenRelative = setOf(
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioRouteSheet.kt",
            "ui/screens/room/AudioVuMeter.kt",
            "ui/screens/room/DiagnosticsDialog.kt",
            "ui/screens/room/LeaveRideDialog.kt",
            "ui/screens/room/SosAlertBanner.kt"
        )

        val scanRoots = listOf(java.io.File(javaRoot, "ui"), java.io.File(javaRoot, "mesh/ui"))
        val violations = mutableListOf<String>()
        val clickableRegex = Regex("""\.clickable\s*\(""")

        for (root in scanRoots) {
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val rel = file.relativeTo(javaRoot).path.replace('\\', '/')
                if (rel in frozenRelative) return@forEach

                val text = file.readText()
                clickableRegex.findAll(text).forEach { match ->
                    val snippetEnd = (match.range.last + 220).coerceAtMost(text.length)
                    val callWindow = text.substring(match.range.first, snippetEnd)
                    if (!callWindow.contains("role =")) {
                        val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                        violations += "$rel:$line missing explicit role = in .clickable(...)"
                    }
                }
            }
        }

        assertTrue(
            "Non-frozen .clickable(...) modifiers missing explicit role =:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
