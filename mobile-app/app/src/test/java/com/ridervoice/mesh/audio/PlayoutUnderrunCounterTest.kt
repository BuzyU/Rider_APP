package com.ridervoice.mesh.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayoutUnderrunCounterTest {

    @Test
    fun playback_readsTrackUnderrunCount_fromSinkPeriodically() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var fakeUnderruns = 0
        val sink = object : AudioTrackSink {
            override val state: Int = android.media.AudioTrack.STATE_INITIALIZED
            override val underrunCount: Int get() = fakeUnderruns
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
        assertEquals(0L, playback.trackUnderrunCount)

        // Simulate sink reporting hardware underruns
        fakeUnderruns = 5

        // Advance by 1200ms of simulated monotonic time
        currentNanoTime += 1_200_000_000L
        testScheduler.advanceTimeBy(1200L)

        assertEquals("Playback must sample sink underrun count after 1 second", 5L, playback.trackUnderrunCount)
        playback.stop()
    }
}

