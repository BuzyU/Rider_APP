package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterDuplicateTest {

    @Test
    fun duplicatePackets_areIgnoredWithoutQueueDuplication() {
        var time = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-dup",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { time }
        )

        val dummyPcm1 = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x01 }
        val dummyPcm2 = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x02 }

        buffer.enqueue(1, dummyPcm1)
        buffer.enqueue(1, dummyPcm2) // duplicate sequence 1
        assertEquals("Queue depth should be 1 despite duplicate enqueue", 1, buffer.depthFrames)

        buffer.enqueue(2, dummyPcm1)
        buffer.enqueue(3, dummyPcm1)
        assertTrue(buffer.isPrimed())

        assertEquals(1, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
        assertEquals(2, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
        assertEquals(3, (buffer.pollFrame() as PlayoutFrame.Audio).sequence)
        assertTrue(buffer.pollFrame() is PlayoutFrame.Concealed)
    }
}

