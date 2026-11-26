package com.ridervoice.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.ridervoice.ui.components.LocalRvFeedback
import com.ridervoice.ui.components.RvSnackbarHost
import com.ridervoice.ui.components.rememberRvFeedbackController
import com.ridervoice.ui.theme.RiderVoiceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class RvFeedbackTest {

    @get:Rule
    val composeRule = createComposeRule()

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
    fun noToastMakeTextRemainsInSourceTree() {
        val root = resolveSourceRoot()
        val hits = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    if (line.contains("Toast.makeText")) {
                        hits += "${file.relativeTo(root).invariantSeparatorsPath}:${idx + 1}: ${line.trim()}"
                    }
                }
            }
        assertTrue("Expected zero Toast.makeText calls in main source, found:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun rvSnackbarHost_rendersSuccessWarningErrorAndUndoActionCallback() {
        lateinit var controller: com.ridervoice.ui.components.RvFeedbackController
        var undoInvoked = false

        composeRule.setContent {
            RiderVoiceTheme {
                controller = rememberRvFeedbackController()
                CompositionLocalProvider(LocalRvFeedback provides controller) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RvSnackbarHost(
                            controller = controller,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }

        composeRule.runOnIdle {
            controller.showSuccess("Link copied", actionLabel = "Undo") {
                undoInvoked = true
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("rv_snackbar_success").assertIsDisplayed()
        composeRule.onNodeWithText("Link copied").assertIsDisplayed()
        composeRule.onNodeWithTag("rv_snackbar_action").performClick()
        assertTrue("Undo callback must fire when snackbar action is tapped", undoInvoked)

        composeRule.runOnIdle {
            controller.showWarning("Low signal")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_snackbar_warning").assertIsDisplayed()

        composeRule.runOnIdle {
            controller.showError("Invalid token")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_snackbar_error").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun rvSnackbarHost_respectsBottomNavAndInsetsClearance() {
        lateinit var controller: com.ridervoice.ui.components.RvFeedbackController
        composeRule.setContent {
            RiderVoiceTheme {
                controller = rememberRvFeedbackController()
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .testTag("fake_bottom_dock")
                        )
                    }
                ) { shellPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = shellPadding.calculateBottomPadding())
                    ) {
                        RvSnackbarHost(
                            controller = controller,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }

        composeRule.runOnIdle { controller.showInfo("Saved locally") }
        composeRule.waitForIdle()

        val snackbarBounds = composeRule.onNodeWithTag("rv_snackbar_info").getBoundsInRoot()
        val dockBounds = composeRule.onNodeWithTag("fake_bottom_dock").getBoundsInRoot()
        assertTrue(
            "Snackbar bottom (${snackbarBounds.bottom}) must sit cleanly above bottom dock top (${dockBounds.top})",
            snackbarBounds.bottom <= dockBounds.top - 10.dp
        )
    }

    @Test
    fun rvSnackbarHost_suppressesOutputOnSosRoute() {
        lateinit var controller: com.ridervoice.ui.components.RvFeedbackController
        composeRule.setContent {
            RiderVoiceTheme {
                controller = rememberRvFeedbackController()
                Box(modifier = Modifier.fillMaxSize()) {
                    RvSnackbarHost(
                        controller = controller,
                        suppressed = true,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        composeRule.runOnIdle { controller.showError("Should stay hidden during SOS") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rv_snackbar_host").assertDoesNotExist()
        composeRule.onNodeWithText("Should stay hidden during SOS").assertDoesNotExist()
    }
}
