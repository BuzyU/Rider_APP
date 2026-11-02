package com.ridervoice.mesh

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level isolation guard.
 *  - Online code must never import the mesh package.
 *  - Mesh code must never import LiveKit SDK classes or the online LiveKitManager
 *    (Stages 1; the Stage 2 adapter will get an explicit, reviewed allowlist entry).
 */
class ImportIsolationTest {

    private val srcRoot: File = listOf(
        File("src/main/java/com/ridervoice"),
        File("app/src/main/java/com/ridervoice")
    ).first { it.isDirectory }

    private fun kotlinFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun importsOf(file: File): List<String> =
        file.readLines().map { it.trim() }.filter { it.startsWith("import ") }

    private val meshDir = File(srcRoot, "mesh")
    private val meshImportAllowlist = setOf(
        "App.kt",
        "MeshModule.kt",
        "NavGraph.kt",
        "Routes.kt",
        "SettingsScreen.kt",
        "MeshForegroundService.kt"
    )

    @Test
    fun onlineCodeDoesNotImportMesh() {
        val offenders = kotlinFiles(srcRoot)
            .filter { !it.startsWith(meshDir) && it.name !in meshImportAllowlist }
            .filter { f -> importsOf(f).any { it.startsWith("import com.ridervoice.mesh") } }
            .map { it.relativeTo(srcRoot).path }
        assertTrue("Unreviewed files import mesh: $offenders", offenders.isEmpty())
    }

    @Test
    fun frozenOnlineFilesNeverImportMesh() {
        val frozen = listOf(
            "network/LiveKitManager.kt",
            "state/RoomViewModel.kt",
            "services/VoiceForegroundService.kt",
            "ui/screens/RoomScreen.kt"
        )
        frozen.forEach { rel ->
            val f = File(srcRoot, rel)
            assertTrue("Frozen file must exist: $rel", f.exists())
            assertTrue("$rel must not import mesh", importsOf(f).none { it.startsWith("import com.ridervoice.mesh") })
        }
    }

    @Test
    fun meshCodeDoesNotImportOnlineTransport() {
        val forbidden = listOf(
            "import io.livekit",
            "import com.ridervoice.network.LiveKitManager",
            "import com.ridervoice.network.ApiService",
            "import com.ridervoice.services.VoiceForegroundService",
            "import com.ridervoice.state.RoomViewModel",
            "import com.ridervoice.state.ActiveRideRepository"
        )
        val allowedAdapters = setOf("LiveKitVoiceTransport.kt", "MeshAutoSwitchManager.kt")
        val offenders = if (!meshDir.isDirectory) emptyList() else kotlinFiles(meshDir)
            .filter { it.name !in allowedAdapters }
            .filter { f -> importsOf(f).any { imp -> forbidden.any { imp.startsWith(it) } } }
            .map { it.relativeTo(srcRoot).path }
        assertTrue("Mesh files import online transport: $offenders", offenders.isEmpty())
    }
}
