package com.ridervoice.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Named Architecture Rules Enforcing Structural Boundaries (B20).
 *
 * Rules:
 *  1. Online code may not import com.ridervoice.mesh outside approved bridge files.
 *  2. Frozen LiveKit contract files must never import com.ridervoice.mesh.
 *  3. Offline mesh code may not import LiveKitManager or online transport outside transport/LiveKitVoiceTransport.
 *  4. Production manifest must not declare unapproved Wi-Fi permissions.
 *  5. Debug receiver must require android.permission.DUMP.
 */
class ArchitectureRulesTest {

    private val srcRoot: File = listOf(
        File("src/main/java/com/ridervoice"),
        File("app/src/main/java/com/ridervoice"),
        File("mobile-app/app/src/main/java/com/ridervoice")
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
    fun rule1_onlineCodeDoesNotImportMesh() {
        val offenders = kotlinFiles(srcRoot)
            .filter { !it.startsWith(meshDir) && it.name !in meshImportAllowlist }
            .filter { f -> importsOf(f).any { it.startsWith("import com.ridervoice.mesh") } }
            .map { it.relativeTo(srcRoot).path }
        assertTrue("Unreviewed files import mesh: $offenders", offenders.isEmpty())
    }

    @Test
    fun rule2_frozenOnlineFilesNeverImportMesh() {
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
    fun rule3_meshCodeDoesNotImportOnlineTransportOutsideAdapters() {
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

    @Test
    fun rule4_manifestDeclaresOnlyApprovedWifiPermissions() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("mobile-app/app/src/main/AndroidManifest.xml")
        ).first { it.exists() }
        val manifest = manifestFile.readText()
        assertFalse("CHANGE_WIFI_STATE must be removed from manifest", manifest.contains("android.permission.CHANGE_WIFI_STATE"))
        assertFalse("NEARBY_WIFI_DEVICES must be removed from manifest", manifest.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue("ACCESS_WIFI_STATE must be retained", manifest.contains("android.permission.ACCESS_WIFI_STATE"))
        assertTrue("CHANGE_WIFI_MULTICAST_STATE must be retained", manifest.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE"))
    }

    @Test
    fun rule5_debugManifestGuardsReceiverWithDumpPermission() {
        val debugManifestFile = listOf(
            File("src/debug/AndroidManifest.xml"),
            File("app/src/debug/AndroidManifest.xml"),
            File("mobile-app/app/src/debug/AndroidManifest.xml")
        ).first { it.exists() }
        val debugManifest = debugManifestFile.readText()
        assertTrue(
            "Debug receiver must be guarded with android:permission=\"android.permission.DUMP\"",
            debugManifest.contains("android:permission=\"android.permission.DUMP\"")
        )
    }
}
