package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterSequenceWrapTest {

    @Test
    fun sequenceWrapAround_intMaxToIntMin_ordersAndPlaysCorrectly() {
        var time = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-wrap",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { time }
        )

        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x12 }

        val startSeq = Int.MAX_VALUE - 1
        buffer.enqueue(startSeq, dummyPcm)
        buffer.enqueue(Int.MAX_VALUE, dummyPcm)
        buffer.enqueue(Int.MIN_VALUE, dummyPcm)

        assertTrue(buffer.isPrimed())

        val f1 = buffer.pollFrame() as PlayoutFrame.Audio
        assertEquals(Int.MAX_VALUE - 1, f1.sequence)

        val f2 = buffer.pollFrame() as PlayoutFrame.Audio
        assertEquals(Int.MAX_VALUE, f2.sequence)

        // Enqueue wrapped frame
        buffer.enqueue(Int.MIN_VALUE + 1, dummyPcm)

        val f3 = buffer.pollFrame() as PlayoutFrame.Audio
        assertEquals(Int.MIN_VALUE, f3.sequence)

        val f4 = buffer.pollFrame() as PlayoutFrame.Audio
        assertEquals(Int.MIN_VALUE + 1, f4.sequence)

        assertEquals("No late drops should occur during valid wrap", 0L, buffer.lateDroppedCount)
    }

    @Test
    fun seqDiff_wrapArithmetic_evaluatesCorrectly() {
        assertEquals(1, SourceJitterBuffer.seqDiff(Int.MIN_VALUE, Int.MAX_VALUE, 32))
        assertEquals(-1, SourceJitterBuffer.seqDiff(Int.MAX_VALUE, Int.MIN_VALUE, 32))
        assertEquals(2, SourceJitterBuffer.seqDiff(Int.MIN_VALUE + 1, Int.MAX_VALUE, 32))

        // 24-bit wrap check
        assertEquals(1, SourceJitterBuffer.seqDiff(0, 0xFFFFFF, 24))
        assertEquals(-1, SourceJitterBuffer.seqDiff(0xFFFFFF, 0, 24))
    }
}

