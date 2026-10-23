package com.ridervoice.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxCaptureEvaluatorTest {

    @Test
    fun forcedAdmModeAlwaysReturnsAdm() {
        val evaluator = VoxCaptureEvaluator(VoxCaptureMode.ADM)
        assertEquals("adm", evaluator.onSecondTick(0))
        assertEquals("adm", evaluator.onSecondTick(50))
    }

    @Test
    fun forcedOwnModeAlwaysReturnsOwn() {
        val evaluator = VoxCaptureEvaluator(VoxCaptureMode.OWN)
        assertEquals("own", evaluator.onSecondTick(50))
        assertEquals("own", evaluator.onSecondTick(0))
    }

    @Test
    fun autoModeRequires3ConsecutiveSecondsOf40FpsToSwitchToAdm() {
        val evaluator = VoxCaptureEvaluator(VoxCaptureMode.AUTO)
        assertEquals("own", evaluator.activePath)

        // Second 1: 45 frames
        assertEquals("own", evaluator.onSecondTick(45))

        // Second 2: 42 frames
        assertEquals("own", evaluator.onSecondTick(42))

        // Second 3: 50 frames -> switches to adm
        assertEquals("adm", evaluator.onSecondTick(50))
        assertEquals("adm", evaluator.activePath)

        // Second 4: stays adm if frames continue
        assertEquals("adm", evaluator.onSecondTick(48))

        // Second 5: frames drop below 40 -> falls back to own immediately
        assertEquals("own", evaluator.onSecondTick(20))
        assertEquals("own", evaluator.activePath)
    }
}
