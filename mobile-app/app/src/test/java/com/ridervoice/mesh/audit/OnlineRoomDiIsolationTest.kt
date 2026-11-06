package com.ridervoice.mesh.audit

import com.ridervoice.mesh.OfflineMeshGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnlineRoomDiIsolationTest {

    @Test
    fun navGraph_activeRideRoute_branchesOnBuildGate() {
        val navGraphFile = File("src/main/java/com/ridervoice/navigation/NavGraph.kt")
        val content = if (navGraphFile.exists()) {
            navGraphFile.readText()
        } else {
            File("mobile-app/app/src/main/java/com/ridervoice/navigation/NavGraph.kt").readText()
        }

        // Must check OfflineMeshGate.isBuildGateOpen() before composing OfflineAwareRoomHost
        assertTrue(
            "NavGraph.kt must branch on OfflineMeshGate.isBuildGateOpen() for ACTIVE_RIDE_HUD route",
            content.contains("if (OfflineMeshGate.isBuildGateOpen())") ||
            content.contains("if (com.ridervoice.mesh.OfflineMeshGate.isBuildGateOpen())")
        )

        // Must fall back to RoomScreen directly when build gate is closed
        assertTrue(
            "NavGraph.kt must fallback to RoomScreen when build gate is closed",
            content.contains("RoomScreen(")
        )
    }

    @Test
    fun meshActiveOverlay_consumesAllPointerEvents() {
        val meshControlsFile = File("src/main/java/com/ridervoice/mesh/ui/MeshControls.kt")
        val content = if (meshControlsFile.exists()) {
            meshControlsFile.readText()
        } else {
            File("mobile-app/app/src/main/java/com/ridervoice/mesh/ui/MeshControls.kt").readText()
        }

        // MeshActiveOverlay must consume pointer inputs via pointerInput
        val overlaySection = content.substringAfter("fun MeshActiveOverlay")
            .substringBefore("fun MeshControls")

        assertTrue(
            "MeshActiveOverlay must have pointerInput consumer to block touches to underlying screen",
            overlaySection.contains("pointerInput") &&
            overlaySection.contains("awaitPointerEventScope") &&
            overlaySection.contains("consume()")
        )
    }
}

