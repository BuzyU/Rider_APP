package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterGapJumpTest {

    @Test
    fun gapJump_resyncsImmediatelyWithoutTickByTickConcealment() {
        val gaps = listOf(10, 100, 1000)
        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x55 }

        for (gap in gaps) {
            var time = 1_000L
            val buffer = SourceJitterBuffer(
                sourceId = "test-node",
                targetDelayFrames = 3,
                maxQueueFrames = 8,
                clock = { time }
            )

            // Prime with frames 0, 1, 2
            buffer.enqueue(0, dummyPcm)
            buffer.enqueue(1, dummyPcm)
            buffer.enqueue(2, dummyPcm)
            assertTrue("Buffer must be primed", buffer.isPrimed())

            // Read frames 0, 1, 2
            assertEquals(0, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
            assertEquals(1, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
            assertEquals(2, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)

            // Huge gap: frames 3 to gap-1 are lost. Sender resumes at sequence `gap`
            val resumeSeq = gap
            buffer.enqueue(resumeSeq, dummyPcm)
            buffer.enqueue(resumeSeq + 1, dummyPcm)
            buffer.enqueue(resumeSeq + 2, dummyPcm)

            // First poll encounters gap >= targetDelayFrames -> emits 1 PLC frame and jumps nextExpectedSeq to head
            val p1 = buffer.pollFrame()
            assertTrue("Must emit one concealed frame on gap jump", p1 is PlayoutFrame.Concealed)

            // Second poll plays the audio from resumeSeq
            val p2 = buffer.pollFrame()
            assertTrue("Second frame must be audio at resumeSeq", p2 is PlayoutFrame.Audio && p2.sequence == resumeSeq)

            assertTrue(
                "Depth must not exceed target + 1 after gap jump (gap=$gap)",
                buffer.depthFrames <= buffer.targetFrames + 1
            )
        }
    }
}

