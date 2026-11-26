package com.ridervoice.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP-U11 Repo Hygiene Guard:
 * - Master design spec must live outside the repo (not in docs/RIDERVOICE_UI_REDESIGN_MASTER_SPEC.md).
 * - Verified zero-reference dead UI code (ProfileDrawer.kt, VoiceControls.kt, RvActionButton.kt,
 *   and SleekActionRow in HomeScreen.kt) must stay removed.
 * - Drawable inventory must contain only the accepted hero photo drawable.
 */
class RepoHygieneTest {

    private fun locateRepoRoot(): File {
        val candidates = listOf(
            File("../.."),
            File(".."),
            File(".")
        )
        return candidates.firstOrNull { File(it, "mobile-app/app/src/main").isDirectory }
            ?: error("Could not locate repository root")
    }

    @Test
    fun masterRedesignSpecIsNotCommittedInRepo() {
        val root = locateRepoRoot()
        val specFile = File(root, "docs/RIDERVOICE_UI_REDESIGN_MASTER_SPEC.md")
        assertFalse(
            "docs/RIDERVOICE_UI_REDESIGN_MASTER_SPEC.md should be moved out of the repo",
            specFile.exists()
        )
    }

    @Test
    fun verifiedZeroReferenceDeadUiFilesAreRemoved() {
        val root = locateRepoRoot()
        val uiRoot = File(root, "mobile-app/app/src/main/java/com/ridervoice/ui")
        assertFalse("Unused ProfileDrawer.kt must be removed", File(uiRoot, "components/ProfileDrawer.kt").exists())
        assertFalse("Unused VoiceControls.kt must be removed", File(uiRoot, "components/VoiceControls.kt").exists())
        assertFalse("Unused RvActionButton.kt must be removed", File(uiRoot, "components/RvActionButton.kt").exists())

        val homeText = File(uiRoot, "screens/HomeScreen.kt").readText()
        assertFalse("Unused SleekActionRow must be removed from HomeScreen.kt", homeText.contains("fun SleekActionRow("))
    }

    @Test
    fun drawableInventoryMatchesAcceptedList() {
        val root = locateRepoRoot()
        val resDir = File(root, "mobile-app/app/src/main/res")
        val drawables = resDir.walkTopDown()
            .filter { it.isFile && (it.parentFile?.name?.startsWith("drawable") == true) }
            .map { "${it.parentFile?.name}/${it.name}" }
            .sorted()
            .toList()
        val expectedDrawables = listOf(
            "drawable-xhdpi/hero_create_ride.webp",
            "drawable-xxhdpi/hero_create_ride.webp"
        )
        assertTrue(
            "Expected only $expectedDrawables in res/drawable*, found: $drawables",
            drawables == expectedDrawables
        )
    }
}
