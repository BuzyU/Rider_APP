package com.ridervoice.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxCaptureEvaluatorTest {

    @Test
    fun initialPathDefaultsToAdm() {
        val evaluator = VoxCaptureEvaluator(VoxCaptureMode.AUTO)
        assertEquals("adm", evaluator.activePath)
        assertEquals(0, evaluator.pathSwitchCount)
    }

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
    fun steadyFramesKeepAdmWithoutPathFlapping() {
        val evaluator = VoxCaptureEvaluator(VoxCaptureMode.AUTO, initialPath = "adm")
        evaluator.onAdmFrameReceived(1000L)

        // 10 steady seconds: must stay in "adm" with 0 path switches
        for (i in 1..10) {
            val now = 1000L + i * 1000L
            evaluator.onAdmFrameReceived(now)
            assertEquals("adm", evaluator.onSecondTick(admFramesInLastSec = 50, isMicOpen = false, now = now))
        }
        assertEquals("adm", evaluator.activePath)
        assertEquals(0, evaluator.pathSwitchCount)
    }

    @Test
    fun speakingDoesNotDowngradePathEvenWhenMicIsOpenAndZeroClosedFrames() {
        val evaluator = VoxCaptureEvaluator(VoxCaptureMode.AUTO, initialPath = "adm")
        val baseTime = 10000L
        evaluator.onAdmFrameReceived(baseTime)

        // Rider speaks for 5 seconds (mic open)
        for (i in 1..5) {
            val now = baseTime + i * 1000L
            evaluator.onAdmFrameReceived(now)
            // Even if 0 frames arrived while closed (because mic was open the whole second)
            val path = evaluator.onSecondTick(admFramesInLastSec = 0, isMicOpen = true, now = now)
            assertEquals("adm", path)
        }
        assertEquals("adm", evaluator.activePath)
        assertEquals(0, evaluator.pathSwitchCount)
    }

    @Test
    fun frameLossWhileClosedDowngradesToOwnAfterTimeout() {
        val evaluator = VoxCaptureEvaluator(VoxCaptureMode.AUTO, initialPath = "adm")
        val baseTime = 10000L
        evaluator.onAdmFrameReceived(baseTime)

        // 1 second with no frames and mic closed -> ADM timed out (>1000ms)
        val now = baseTime + 1500L
        val path = evaluator.onSecondTick(admFramesInLastSec = 0, isMicOpen = false, now = now)
        assertEquals("own", path)
        assertEquals("own", evaluator.activePath)
        assertEquals(1, evaluator.pathSwitchCount)
    }

    @Test
    fun recoveryFromOwnRequires3ConsecutiveSecondsOfSteadyFrames() {
        val evaluator = VoxCaptureEvaluator(VoxCaptureMode.AUTO, initialPath = "own")
        assertEquals("own", evaluator.activePath)

        val t0 = 10000L

        // Sec 1: 45 frames
        evaluator.onAdmFrameReceived(t0 + 1000L)
        assertEquals("own", evaluator.onSecondTick(admFramesInLastSec = 45, isMicOpen = false, now = t0 + 1000L))

        // Sec 2: 48 frames
        evaluator.onAdmFrameReceived(t0 + 2000L)
        assertEquals("own", evaluator.onSecondTick(admFramesInLastSec = 48, isMicOpen = false, now = t0 + 2000L))

        // Sec 3: 50 frames -> switches to adm with hysteresis!
        evaluator.onAdmFrameReceived(t0 + 3000L)
        assertEquals("adm", evaluator.onSecondTick(admFramesInLastSec = 50, isMicOpen = false, now = t0 + 3000L))
        assertEquals("adm", evaluator.activePath)
        assertEquals(1, evaluator.pathSwitchCount)

        // Sec 4: stays adm
        evaluator.onAdmFrameReceived(t0 + 4000L)
        assertEquals("adm", evaluator.onSecondTick(admFramesInLastSec = 49, isMicOpen = false, now = t0 + 4000L))
        assertEquals("adm", evaluator.activePath)
        assertEquals(1, evaluator.pathSwitchCount)
    }
}
