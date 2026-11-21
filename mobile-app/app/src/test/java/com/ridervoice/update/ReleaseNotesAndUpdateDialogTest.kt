package com.ridervoice.update

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.ridervoice.ui.components.UpdateDialog
import com.ridervoice.ui.theme.RiderVoiceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the upgraded App Update system:
 * - Category-wise ReleaseNotesParser (strips #, -, *, backticks, WP-### codes, groups cleanly).
 * - Wide, spacious UpdateDialog card width (no cramped default AlertDialog margins).
 * - Category cards rendered visibly in UpToDate & UpdateAvailable dialogs.
 * - Unknown total download size shows MB downloaded rather than "0% / 0.0 MB".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class ReleaseNotesAndUpdateDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun releaseNotesParser_stripsRawMarkdownAndGroupsByCategory() {
        val raw = """
            ### RiderVoice v0.0.3.21
            ## New Features
            - **Offline Mesh Backup**: Added per-ride pairing codes (WP-155)
            ## Bug Fixes
            - Eliminated layout shifts and text clipping across room controls (WP-157, WP-164)
            - Restored synchronous active-ride persistence (WP-158)
            ## Improvements
            - Improved hands-free VOX speech tracking for continuous talking (#201)
            ```
            SHA-256: 0123456789abcdef
            ```
        """.trimIndent()

        val sections = ReleaseNotesParser.parse(raw)
        val byCat = sections.associateBy { it.category }

        assertTrue("Must have WHATS_NEW section", byCat.containsKey(ReleaseNoteCategory.WHATS_NEW))
        assertTrue("Must have IMPROVEMENTS section", byCat.containsKey(ReleaseNoteCategory.IMPROVEMENTS))
        assertTrue("Must have BUG_FIXES section", byCat.containsKey(ReleaseNoteCategory.BUG_FIXES))
        assertEquals(2, byCat[ReleaseNoteCategory.BUG_FIXES]!!.items.size)

        val allText = sections.flatMap { it.items }.joinToString("\n")
        assertFalse("Must not contain raw #", allText.contains("#"))
        assertFalse("Must not contain raw *", allText.contains("*"))
        assertFalse("Must not contain raw `", allText.contains("`"))
        assertFalse("Must not contain internal WP-157 code", allText.contains("WP-157"))
        assertFalse("Must not contain SHA-256 block", allText.contains("SHA-256"))
    }

    @Test
    fun releaseNotesParser_autoCategorizesUnheadedBulletsCleanly() {
        val sections = ReleaseNotesParser.parse(UpdateManager.DEFAULT_V00320_NOTES)
        assertTrue("Should produce multiple organized categories", sections.size >= 3)
        assertTrue(sections.any { it.category == ReleaseNoteCategory.WHATS_NEW })
        assertTrue(sections.any { it.category == ReleaseNoteCategory.RELIABILITY })
        assertTrue(sections.any { it.category == ReleaseNoteCategory.NOTES })
    }

    @Test
    fun updateDialog_upToDate_rendersCategorizedCardsAndWideLayout() {
        composeRule.setContent {
            RiderVoiceTheme {
                UpdateDialog(
                    uiState = UpdateUiState.UpToDate(
                        currentVersion = "0.0.3.20",
                        releaseNotes = """
                            - Added offline mesh backup
                            - Fixed audio cutoff crash (WP-157)
                            - Improved battery efficiency
                        """.trimIndent()
                    ),
                    onConfirmDownload = {},
                    onStartDownload = {},
                    onCancelDownload = {},
                    onInstallUpdate = { _, _ -> },
                    onOpenSettings = {},
                    onRetry = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        val card = composeRule.onNodeWithTag("update_dialog_card", useUnmergedTree = true)
        card.assertIsDisplayed()
        val bounds = card.getBoundsInRoot()
        val width = bounds.right - bounds.left
        assertTrue("Dialog card should use sleek 16dp margins (>= 360dp on 411dp screen), was $width", width >= 360.dp)

        composeRule.onNodeWithTag("release_category_whats_new", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("release_category_bug_fixes", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("release_category_improvements", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Fixed audio cutoff crash", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("You are currently running the latest version", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun updateDialog_downloadingWithUnknownTotalSize_showsDownloadedMbInsteadOfZeroPercent() {
        val info = AppReleaseInfo(
            versionName = "0.0.3.21",
            tagName = "v0.0.3.21",
            apkFileName = "Rider_APP-v0.0.3.21.apk",
            downloadUrl = "https://example.com/app.apk",
            apkSizeBytes = 0L,
            releaseNotes = "",
            publishedAt = "2026-11-21",
            sha256Checksum = "a".repeat(64)
        )
        composeRule.setContent {
            RiderVoiceTheme {
                UpdateDialog(
                    uiState = UpdateUiState.Downloading(
                        releaseInfo = info,
                        downloadedBytes = 5L * 1024L * 1024L,
                        totalBytes = 0L,
                        progressPercent = 0,
                        speedText = "2.4 MB/s"
                    ),
                    onConfirmDownload = {},
                    onStartDownload = {},
                    onCancelDownload = {},
                    onInstallUpdate = { _, _ -> },
                    onOpenSettings = {},
                    onRetry = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("5.0 MB downloaded", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("2.4 MB/s", useUnmergedTree = true).assertIsDisplayed()
    }
}
