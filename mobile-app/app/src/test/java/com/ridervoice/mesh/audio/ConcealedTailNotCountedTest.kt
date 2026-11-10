package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConcealedTailNotCountedTest {

    @Test
    fun talkspurtTailConcealment_doesNotIncrementConcealedMidTalkspurt() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sink = object : AudioTrackSink {
            override val state: Int = android.media.AudioTrack.STATE_INITIALIZED
            override fun play() {}
            override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = sizeInBytes
            override fun stop() {}
            override fun release() {}
        }

        val jitterManager = MultiSourceJitterBufferManager(clock = { testScheduler.currentTime })
        var currentNanoTime = 1_000_000_000L
        val playback = OfflineAudioPlayback(
            jitterManager = jitterManager,
            gateAllowed = { true },
            dispatcher = testDispatcher,
            audioTrackFactory = { sink },
            nanoClock = { currentNanoTime }
        )

        assertTrue(playback.start())
        val dummyPcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x11 }

        // Send frames 1..3 to prime the buffer
        jitterManager.onVoicePacket("speaker-A", 1, dummyPcm)
        jitterManager.onVoicePacket("speaker-A", 2, dummyPcm)
        jitterManager.onVoicePacket("speaker-A", 3, dummyPcm)

        // Advance playout tick by tick
        for (i in 1..3) {
            currentNanoTime += 20_000_000L
            testScheduler.advanceTimeBy(20L)
        }

        // Tail of talkspurt: 3 ticks elapse with queue empty (consecutive missing = 1..3)
        for (i in 1..4) {
            currentNanoTime += 20_000_000L
            testScheduler.advanceTimeBy(20L)
        }

        // Tail misses should NOT be counted as mid-talkspurt concealments
        assertEquals("Concealed frames at tail of talkspurt must not increment concealedMidTalkspurt", 0L, playback.concealedMidTalkspurt)

        // Now simulate mid-talkspurt packet loss: send frames 10 and 12, omitting frame 11
        jitterManager.onVoicePacket("speaker-A", 10, dummyPcm)
        jitterManager.onVoicePacket("speaker-A", 12, dummyPcm)
        // Send frame 13 to meet the target threshold (3 frames: 10, 12, 13)
        jitterManager.onVoicePacket("speaker-A", 13, dummyPcm)

        // Play frame 10
        currentNanoTime += 20_000_000L
        testScheduler.advanceTimeBy(20L)

        // Playout tick for missing frame 11 while frames 12 & 13 are queued
        currentNanoTime += 20_000_000L
        testScheduler.advanceTimeBy(20L)

        assertEquals("True mid-talkspurt missing frame with subsequent frames queued must increment concealedMidTalkspurt", 1L, playback.concealedMidTalkspurt)

        playback.stop()
    }
}

