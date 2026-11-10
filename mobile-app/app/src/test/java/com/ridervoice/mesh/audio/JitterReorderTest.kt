package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterReorderTest {

    @Test
    fun outOfOrderPackets_areReorderedCorrectly() {
        var time = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-reorder",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { time }
        )

        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x77 }

        // Enqueue out of order: 2, 0, 1
        buffer.enqueue(2, dummyPcm)
        buffer.enqueue(0, dummyPcm)
        buffer.enqueue(1, dummyPcm)

        assertTrue(buffer.isPrimed())

        assertEquals(0, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
        assertEquals(1, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
        assertEquals(2, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
    }

    @Test
    fun latePacketsBehindExpectedSequence_areDropped() {
        var time = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-late",
            targetDelayFrames = 2,
            maxQueueFrames = 8,
            clock = { time }
        )

        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x88.toByte() }

        buffer.enqueue(10, dummyPcm)
        buffer.enqueue(11, dummyPcm)
        assertTrue(buffer.isPrimed())

        assertEquals(10, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
        assertEquals(11, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)

        // Frame 9 arrives late (expected is now 12)
        buffer.enqueue(9, dummyPcm)
        assertEquals(1L, buffer.lateDroppedCount)
        assertEquals(0, buffer.depthFrames)
    }
}

