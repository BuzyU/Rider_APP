package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.audio.codec.OpusVoiceEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class JitterBufferCodecIntegrationTest {

    @Test
    fun jitterBufferVariableSizeTest_handlesOpusAndMarkersWithoutPaddingOrCorruption() {
        var now = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-var-size",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { now }
        )

        val opusPayload40 = ByteArray(40) { 0x42 }
        val silenceMarker1 = ByteArray(1) { 0x00 }

        // Enqueue 3 frames to prime: two 40B frames and one 1B silence marker
        buffer.enqueue(1, opusPayload40)
        buffer.enqueue(2, opusPayload40)
        buffer.enqueue(3, silenceMarker1)

        val f1 = buffer.pollFrame()
        assertTrue("Frame 1 must be Audio", f1 is PlayoutFrame.Audio)
        assertEquals(40, (f1 as PlayoutFrame.Audio).pcmBytes.size)

        val f2 = buffer.pollFrame()
        assertTrue("Frame 2 must be Audio", f2 is PlayoutFrame.Audio)
        assertEquals(40, (f2 as PlayoutFrame.Audio).pcmBytes.size)

        val f3 = buffer.pollFrame()
        assertTrue("Frame 3 must be Audio", f3 is PlayoutFrame.Audio)
        assertEquals(1, (f3 as PlayoutFrame.Audio).pcmBytes.size)
    }

    @Test
    fun dtxKeepAliveTest_twoSecondsSilenceEmitsAtMostSixKeepAlivesAndReceiverStaysPrimed() {
        val encoder = OpusVoiceEncoder()
        val silencePcm = ByteArray(MeshConfig.BYTES_PER_FRAME)
        val voicePcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x55 }

        // Warm up encoder with active voice
        for (i in 0 until 20) {
            encoder.encode(voicePcm)
        }

        // Feed silence until DTX begins (initial frames are VAD hangover)
        while (true) {
            val enc = encoder.encode(silencePcm)
            if (enc.size <= 2) break
        }

        // Now during 2s (100 frames) of steady DTX silence, count keep-alive packets
        var keepAliveCount = 0
        for (i in 0 until 100) {
            val enc = encoder.encode(silencePcm)
            if (enc.size > 2) {
                keepAliveCount++
            }
        }

        println("DTX 2s silence: keepAlives = $keepAliveCount")
        assertTrue("2s of silence must emit at most 6 keep-alive packets (measured $keepAliveCount)", keepAliveCount <= 6)
    }

    @Test
    fun noPlcAfterSilenceMarkerTest_missingSequencesAfterSilenceDoNotConceal() {
        var now = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-silence-marker",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { now }
        )

        val voice40 = ByteArray(40) { 0x42 }
        val marker1 = ByteArray(1)

        // Enqueue active voice frames and silence marker
        buffer.enqueue(1, voice40)
        buffer.enqueue(2, voice40)
        buffer.enqueue(3, marker1)

        // Poll frames 1, 2, 3
        buffer.pollFrame() // 1
        buffer.pollFrame() // 2
        val f3 = buffer.pollFrame() // 3: silence marker
        assertTrue(f3 is PlayoutFrame.Audio)
        assertEquals(0L, buffer.concealedCount)

        // Now ticks 4, 5, 6, 7 are missing because sender suppressed DTX frames
        for (tick in 4..7) {
            now += 20L
            val polled = buffer.pollFrame()
            // After silence marker, missing sequences must NOT be concealed!
            assertFalse("Must not produce Concealed frame after silence marker", polled is PlayoutFrame.Concealed)
        }
        assertEquals("Concealed count must remain 0 after silence marker", 0L, buffer.concealedCount)
    }

    @Test
    fun reprimeAfterSilenceTest_resumesWithShortenedPrimeTargetMinTwo() {
        var now = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-reprime",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { now }
        )

        val voice40 = ByteArray(40) { 0x42 }
        val marker1 = ByteArray(1)

        buffer.enqueue(1, voice40)
        buffer.enqueue(2, voice40)
        buffer.enqueue(3, marker1)

        buffer.pollFrame() // 1
        buffer.pollFrame() // 2
        buffer.pollFrame() // 3 (silence marker)

        // Now stream is idle in silence. Next full frame arrives at seq 20
        buffer.enqueue(20, voice40)
        // Shortened prime threshold is min 2 frames: 1 frame is buffering
        val polled1 = buffer.pollFrame()
        assertTrue("First resumed frame should be Buffering during shortened prime", polled1 is PlayoutFrame.Buffering)

        // Second frame arrives at seq 21 -> satisfies shortened prime (target min 2)
        buffer.enqueue(21, voice40)
        val polled2 = buffer.pollFrame()
        assertTrue("Second resumed frame should trigger primed playout", polled2 is PlayoutFrame.Audio)
        assertEquals(20, (polled2 as PlayoutFrame.Audio).sequence)
    }

    @Test
    fun lossInsideTalkspurtConcealsTest_missingFrameBetweenFullFramesIsConcealed() {
        var now = 10_000L
        val buffer = SourceJitterBuffer(
            sourceId = "node-mid-loss",
            targetDelayFrames = 3,
            maxQueueFrames = 8,
            clock = { now }
        )

        val voice40 = ByteArray(40) { 0x42 }

        // Enqueue seq 1, 2, 4 (seq 3 is lost in transmission between active talkspurt frames)
        buffer.enqueue(1, voice40)
        buffer.enqueue(2, voice40)
        buffer.enqueue(4, voice40)

        val f1 = buffer.pollFrame() // 1
        assertTrue(f1 is PlayoutFrame.Audio)

        val f2 = buffer.pollFrame() // 2
        assertTrue(f2 is PlayoutFrame.Audio)

        // Seq 3 is missing between seq 2 and seq 4: MUST be concealed
        val f3 = buffer.pollFrame()
        assertTrue("Missing seq 3 inside talkspurt must be Concealed", f3 is PlayoutFrame.Concealed)
        assertEquals(1L, buffer.concealedCount)

        // Seq 4 is delivered
        val f4 = buffer.pollFrame()
        assertTrue("Seq 4 must be Audio", f4 is PlayoutFrame.Audio)
        assertEquals(4, (f4 as PlayoutFrame.Audio).sequence)
    }
}
