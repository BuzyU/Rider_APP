package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPrefillTest {

    @Test
    fun start_prefillsOneSilenceFrameBeforeCallingPlay() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val eventLog = mutableListOf<String>()
        var prefillData: ByteArray? = null

        val sink = object : AudioTrackSink {
            override val state: Int = android.media.AudioTrack.STATE_INITIALIZED
            override fun play() {
                eventLog.add("play")
            }
            override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                if (eventLog.isEmpty()) {
                    prefillData = audioData.copyOfRange(offsetInBytes, offsetInBytes + sizeInBytes)
                }
                eventLog.add("write")
                return sizeInBytes
            }
            override fun stop() {
                eventLog.add("stop")
            }
            override fun release() {
                eventLog.add("release")
            }
        }

        val jitterManager = MultiSourceJitterBufferManager(clock = { testScheduler.currentTime })
        val playback = OfflineAudioPlayback(
            jitterManager = jitterManager,
            gateAllowed = { true },
            dispatcher = testDispatcher,
            audioTrackFactory = { sink },
            nanoClock = { 1_000_000_000L }
        )

        assertTrue(playback.start())
        testScheduler.runCurrent()

        // Verify write was called before play
        assertTrue("Log must start with write followed by play: $eventLog", eventLog.size >= 2)
        assertEquals("First operation on sink must be write", "write", eventLog[0])
        assertEquals("Second operation on sink must be play", "play", eventLog[1])

        // Verify prefill frame is silence of exactly BYTES_PER_FRAME
        val prefill = prefillData
        assertTrue("Prefill data must not be null", prefill != null)
        assertEquals("Prefill must be exactly BYTES_PER_FRAME", MeshConfig.BYTES_PER_FRAME, prefill!!.size)
        assertTrue("Prefill must be all zeroes (silence)", prefill.all { it == 0.toByte() })
        playback.stop()
    }
}

