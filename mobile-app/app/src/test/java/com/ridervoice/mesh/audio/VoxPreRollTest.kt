package com.ridervoice.mesh.audio

import com.ridervoice.audio.VoxConfig
import com.ridervoice.audio.VoxDetector
import com.ridervoice.mesh.MeshConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class VoxPreRollTest {

    @Test
    fun voxGateOpening_prependsFourPreRollFrames() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var captureApplied = false
        val preRollList = mutableListOf<List<ByteArray>>()

        // VoxDetector configured with 60ms attack (3 frames)
        val detector = VoxDetector(
            cfg = VoxConfig(
                analysisMs = 20,
                attackMs = 60,
                holdMs = 900,
                openRatio = 2.0f,
                closeRatio = 1.2f,
                minFloor = 100f,
                initialFloor = 200f
            ),
            initialSampleRate = 16_000
        )

        val controller = MicControllerImpl(
            audioEnabled = { true },
            applyCapture = { captureApplied = it },
            voxDetector = detector,
            scope = scope,
            initialVoxEnabled = true
        )
        controller.onPreRollFrames = { preRollList.add(it) }

        // Send 4 silent frames (frames 1..4) with unique first bytes
        for (i in 1..4) {
            val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0 }
            pcm[0] = i.toByte()
            val transmit = controller.shouldTransmitPcmFrame(pcm, nowMs = 10_000L + i * 20L)
            assertFalse("Frames 1..4 must not transmit while gate closed", transmit)
        }

        assertEquals("No pre-roll should be emitted while gate is closed", 0, preRollList.size)

        // Now send loud frames (8 kHz square wave: +/-5000, well above 130 Hz HPF cutoff).
        // Attack is 60 ms (3 frames of 20 ms).
        // Loud Frame 1 (attack accumulated = 20ms < 60ms) -> gate still closed
        val loud1 = ByteArray(MeshConfig.BYTES_PER_FRAME)
        val bb1 = ByteBuffer.wrap(loud1).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until MeshConfig.BYTES_PER_FRAME / 2) {
            bb1.putShort(if (s % 2 == 0) 5000.toShort() else (-5000).toShort())
        }
        loud1[0] = 0x11
        assertFalse("Loud frame 1 (20ms attack) must not open gate yet", controller.shouldTransmitPcmFrame(loud1, nowMs = 10_100L))

        // Loud Frame 2 (attack accumulated = 40ms < 60ms) -> gate still closed
        val loud2 = ByteArray(MeshConfig.BYTES_PER_FRAME)
        val bb2 = ByteBuffer.wrap(loud2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until MeshConfig.BYTES_PER_FRAME / 2) {
            bb2.putShort(if (s % 2 == 0) 5000.toShort() else (-5000).toShort())
        }
        loud2[0] = 0x22
        assertFalse("Loud frame 2 (40ms attack) must not open gate yet", controller.shouldTransmitPcmFrame(loud2, nowMs = 10_120L))

        assertEquals("No pre-roll emitted during attack phase", 0, preRollList.size)

        // Loud Frame 3 (attack accumulated = 60ms >= 60ms) -> gate OPENS!
        val loud3 = ByteArray(MeshConfig.BYTES_PER_FRAME)
        val bb3 = ByteBuffer.wrap(loud3).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until MeshConfig.BYTES_PER_FRAME / 2) {
            bb3.putShort(if (s % 2 == 0) 5000.toShort() else (-5000).toShort())
        }
        loud3[0] = 0x33
        assertTrue("Loud frame 3 (60ms attack reached) must open gate", controller.shouldTransmitPcmFrame(loud3, nowMs = 10_140L))

        // Pre-roll buffer holds the 4 frames immediately preceding the opening frame (frames 3, 4, loud1, loud2)
        assertEquals("Pre-roll callback must be called exactly once on gate opening", 1, preRollList.size)
        val preRoll = preRollList[0]
        assertEquals("Pre-roll must contain exactly 4 frames", 4, preRoll.size)
        assertEquals("Pre-roll frame 0 marker", 3.toByte(), preRoll[0][0])
        assertEquals("Pre-roll frame 1 marker", 4.toByte(), preRoll[1][0])
        assertEquals("Pre-roll frame 2 marker", 0x11.toByte(), preRoll[2][0])
        assertEquals("Pre-roll frame 3 marker", 0x22.toByte(), preRoll[3][0])
    }
}
