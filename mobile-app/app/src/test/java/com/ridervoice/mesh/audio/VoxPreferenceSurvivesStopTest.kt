package com.ridervoice.mesh.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoxPreferenceSurvivesStopTest {

    @Test
    fun reset_preservesVoxPreference_unlessExplicitlyRequested() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var captureApplied = false
        val controller = MicControllerImpl(
            audioEnabled = { true },
            applyCapture = { captureApplied = it },
            scope = scope,
            initialVoxEnabled = false
        )

        // User turns VOX ON
        controller.setVoxEnabled(true)
        assertTrue("VOX should be enabled", controller.voxEnabled.value)

        // User activates mic / PTT / armed
        controller.setMicToggled(true)
        controller.setPttPressed(true)
        controller.setVoxArmed(true)
        assertTrue(controller.micToggleOn.value)
        assertTrue(controller.pttHeld.value)
        assertTrue(controller.voxArmed.value)

        // Transport stops and calls reset()
        controller.reset()

        // Toggle, PTT, armed must be reset
        assertFalse("Toggle must be reset", controller.micToggleOn.value)
        assertFalse("PTT must be reset", controller.pttHeld.value)
        assertFalse("Armed must be reset", controller.voxArmed.value)

        // User's VOX preference must survive transport stop
        assertTrue("User's VOX preference must survive transport reset()", controller.voxEnabled.value)

        // Explicit reset clears VOX preference
        controller.reset(resetVoxPreference = true)
        assertFalse("VOX preference should be cleared when resetVoxPreference = true", controller.voxEnabled.value)
    }
}

