package com.ridervoice.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.models.Participant
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.ui.screens.HomeScreen
import com.ridervoice.ui.screens.LobbyScreen
import com.ridervoice.ui.screens.room.RiderList
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.viewmodels.HomeState
import com.ridervoice.ui.viewmodels.HomeViewModel
import com.ridervoice.ui.viewmodels.LobbyLoadState
import com.ridervoice.ui.viewmodels.LobbyViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class LifecycleStateCollectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class ControlledLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private fun resolveSourceRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/java/com/ridervoice"),
            File(cwd, "app/src/main/java/com/ridervoice"),
            File(cwd, "mobile-app/app/src/main/java/com/ridervoice")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Unable to locate com/ridervoice source root from $cwd")
    }

    @Test
    fun noCollectAsStateInNonFrozenUiFiles() {
        val root = resolveSourceRoot()
        val frozenAllowlist = setOf(
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioVuMeter.kt"
        )
        val scopedPrefixes = listOf(
            "MainActivity.kt",
            "navigation/",
            "ui/",
            "mesh/ui/"
        )

        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (scopedPrefixes.none { rel == it || rel.startsWith(it) }) return@forEach
                if (rel in frozenAllowlist) return@forEach

                file.readLines().forEachIndexed { idx, line ->
                    if (line.contains(".collectAsState(") && !line.contains("// UX-17 exception:")) {
                        violations += "$rel:${idx + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "Found unexpected .collectAsState( in non-frozen UI/navigation files:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun collectAsStateWithLifecycle_dropsSubscriberCountOnStop_andRestoresOnStart_riderListHost() {
        val participantsFlow = MutableStateFlow(listOf(Participant(identity = "r1", displayName = "Asha")))
        val activeSpeakerFlow = MutableSharedFlow<String?>(replay = 0)
        val owner = ControlledLifecycleOwner()
        composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.STARTED) }

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RiderVoiceTheme {
                    val participants by participantsFlow.collectAsStateWithLifecycle()
                    val speaker by activeSpeakerFlow.collectAsStateWithLifecycle(initialValue = null)
                    RiderList(
                        participants = participants,
                        currentUid = "r1",
                        activeSpeaker = speaker,
                        isCurrentUserTransmitting = false,
                        isHost = true,
                        remoteLocations = emptyMap(),
                        currentLocation = null
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, participantsFlow.subscriptionCount.value)
        assertEquals(1, activeSpeakerFlow.subscriptionCount.value)

        composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.CREATED) } // STOPPED
        composeRule.waitForIdle()
        assertEquals(0, participantsFlow.subscriptionCount.value)
        assertEquals(0, activeSpeakerFlow.subscriptionCount.value)

        composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.STARTED) }
        composeRule.waitForIdle()
        assertEquals(1, participantsFlow.subscriptionCount.value)
        assertEquals(1, activeSpeakerFlow.subscriptionCount.value)
    }

    @Test
    fun collectAsStateWithLifecycle_dropsSubscriberCountOnStop_andRestoresOnStart_homeScreen() {
        val uiStateFlow = MutableStateFlow(HomeState())
        val mockVm = mockk<HomeViewModel>(relaxed = true) {
            every { uiState } returns uiStateFlow
        }
        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        every { mockAuth.currentUser } returns null

        val owner = ControlledLifecycleOwner()
        composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.STARTED) }

        mockkStatic(FirebaseAuth::class)
        try {
            every { FirebaseAuth.getInstance() } returns mockAuth

            composeRule.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RiderVoiceTheme {
                        HomeScreen(
                            viewModel = mockVm,
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
            }
            composeRule.waitForIdle()
            assertEquals(1, uiStateFlow.subscriptionCount.value)

            composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.CREATED) }
            composeRule.waitForIdle()
            assertEquals(0, uiStateFlow.subscriptionCount.value)

            composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.STARTED) }
            composeRule.waitForIdle()
            assertEquals(1, uiStateFlow.subscriptionCount.value)
        } finally {
            unmockkStatic(FirebaseAuth::class)
        }
    }

    @Test
    fun collectAsStateWithLifecycle_dropsSubscriberCountOnStop_andRestoresOnStart_lobbyScreen() {
        val loadStateFlow = MutableStateFlow<LobbyLoadState>(LobbyLoadState.Loading)
        val lobbyStatusFlow = MutableStateFlow<com.ridervoice.models.LobbyStatus?>(null)
        val activeRideFlow = MutableStateFlow<ActiveRideRepository.ActiveRide?>(null)
        val errorFlow = MutableStateFlow<String?>(null)
        val terminalFlow = MutableStateFlow<LobbyViewModel.Terminal?>(null)
        val homeStateFlow = MutableStateFlow(HomeState())

        val mockLobbyVm = mockk<LobbyViewModel>(relaxed = true) {
            every { loadState } returns loadStateFlow
            every { lobbyStatus } returns lobbyStatusFlow
            every { activeRideState } returns activeRideFlow
            every { error } returns errorFlow
            every { terminal } returns terminalFlow
        }
        val mockHomeVm = mockk<HomeViewModel>(relaxed = true) {
            every { uiState } returns homeStateFlow
        }

        val owner = ControlledLifecycleOwner()
        composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.STARTED) }

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RiderVoiceTheme {
                    LobbyScreen(
                        convoyName = "Sunday Ride",
                        viewModel = mockLobbyVm,
                        homeViewModel = mockHomeVm,
                        onStartRide = {},
                        onBackClick = {},
                        onInviteMoreClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, loadStateFlow.subscriptionCount.value)
        assertEquals(1, lobbyStatusFlow.subscriptionCount.value)
        assertEquals(1, activeRideFlow.subscriptionCount.value)
        assertEquals(1, errorFlow.subscriptionCount.value)
        assertEquals(1, terminalFlow.subscriptionCount.value)

        composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.CREATED) }
        composeRule.waitForIdle()
        assertEquals(0, loadStateFlow.subscriptionCount.value)
        assertEquals(0, lobbyStatusFlow.subscriptionCount.value)
        assertEquals(0, activeRideFlow.subscriptionCount.value)
        assertEquals(0, errorFlow.subscriptionCount.value)
        assertEquals(0, terminalFlow.subscriptionCount.value)

        composeRule.runOnUiThread { owner.moveTo(Lifecycle.State.STARTED) }
        composeRule.waitForIdle()
        assertEquals(1, loadStateFlow.subscriptionCount.value)
        assertEquals(1, lobbyStatusFlow.subscriptionCount.value)
        assertEquals(1, activeRideFlow.subscriptionCount.value)
        assertEquals(1, errorFlow.subscriptionCount.value)
        assertEquals(1, terminalFlow.subscriptionCount.value)
    }
}
