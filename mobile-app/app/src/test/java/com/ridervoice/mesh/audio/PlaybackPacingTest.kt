package com.ridervoice.mesh.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPacingTest {

    @Test
    fun blockingWrite_doesNotAccumulateDoubleDelay() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var blockingWriteOffsetNs = 0L
        var writeCount = 0

        val sink = object : AudioTrackSink {
            override val state: Int = android.media.AudioTrack.STATE_INITIALIZED
            override fun play() {}
            override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                writeCount++
                if (writeCount > 1) {
                    // Simulate blocking write in audio HAL taking 15 ms
                    blockingWriteOffsetNs += 15_000_000L
                }
                return sizeInBytes
            }
            override fun stop() {}
            override fun release() {}
        }

        val jitterManager = MultiSourceJitterBufferManager(clock = { testScheduler.currentTime })
        val playback = OfflineAudioPlayback(
            jitterManager = jitterManager,
            gateAllowed = { true },
            dispatcher = testDispatcher,
            audioTrackFactory = { sink },
            nanoClock = { 1_000_000_000L + (testScheduler.currentTime * 1_000_000L) + blockingWriteOffsetNs }
        )

        try {
            assertTrue(playback.start())
            testScheduler.runCurrent()
            assertEquals("Initial write count after start and first frame", 2, writeCount)

            testScheduler.advanceTimeBy(5L)
            testScheduler.runCurrent()
            assertEquals("Advancing by remaining 5 ms triggers next frame", 3, writeCount)

            testScheduler.advanceTimeBy(5L)
            testScheduler.runCurrent()
            assertEquals("Advancing by remaining 5 ms again triggers next frame", 4, writeCount)
        } finally {
            playback.stop()
            testScheduler.runCurrent()
        }
    }
}
