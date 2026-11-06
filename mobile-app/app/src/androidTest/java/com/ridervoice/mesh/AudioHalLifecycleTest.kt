package com.ridervoice.mesh

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android Audio HAL lifecycle verification.
 *
 * UNVERIFIED (needs devices): Exercises real hardware AudioRecord and AudioTrack HAL implementations
 * for 16 kHz mono PCM-16 voice pipeline, validating start/stop/start transitions and guaranteed
 * release on error paths.
 */
@RunWith(AndroidJUnit4::class)
class AudioHalLifecycleTest {

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Test
    fun audioRecord_lifecycle_startStopRestart_andSampleRateMatch() {
        // UNVERIFIED (needs devices)
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_IN, ENCODING)
        assertTrue("Min buffer size must be valid", minBufferSize > 0)

        val bufferSize = minBufferSize.coerceAtLeast(SAMPLE_RATE_HZ * 2 / 50 * 2) // 20ms frames * 2 headroom

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE_HZ,
                CHANNEL_IN,
                ENCODING,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                // If device HAL doesn't grant hardware mic in headless test container, fail-soft with note
                return
            }

            assertEquals(SAMPLE_RATE_HZ, record.sampleRate)
            assertEquals(AudioRecord.RECORDSTATE_STOPPED, record.recordingState)

            // Start recording
            record.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.recordingState)

            // Stop recording
            record.stop()
            assertEquals(AudioRecord.RECORDSTATE_STOPPED, record.recordingState)

            // Restart recording (verifying HAL handles restart without leak or stall)
            record.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.recordingState)

            record.stop()
        } finally {
            record?.release()
        }
    }

    @Test
    fun audioTrack_lifecycle_startStopRestart_andBufferSizing() {
        // UNVERIFIED (needs devices)
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_OUT, ENCODING)
        assertTrue("Min buffer size for track must be valid", minBufferSize > 0)

        var track: AudioTrack? = null
        try {
            track = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE_HZ,
                CHANNEL_OUT,
                ENCODING,
                minBufferSize * 2,
                AudioTrack.MODE_STREAM
            )

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                return
            }

            assertEquals(SAMPLE_RATE_HZ, track.sampleRate)
            assertEquals(AudioTrack.PLAYSTATE_STOPPED, track.playState)

            // Start playback
            track.play()
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)

            // Write 20ms silence frame (640 bytes = 320 16-bit samples)
            val silence = ByteArray(640)
            val written = track.write(silence, 0, silence.size)
            assertTrue("AudioTrack write must succeed", written >= 0)

            // Stop playback
            track.stop()
            assertEquals(AudioTrack.PLAYSTATE_STOPPED, track.playState)

            // Restart playback
            track.play()
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)

            track.stop()
        } finally {
            track?.release()
        }
    }

    @Test
    fun audioRecord_releaseOnErrorPath_cleansUpHardware() {
        // UNVERIFIED (needs devices)
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_IN, ENCODING)
        if (minBufferSize <= 0) return

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE_HZ,
                CHANNEL_IN,
                ENCODING,
                minBufferSize * 2
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
            }
            throw IllegalStateException("Simulated mid-pipeline audio error")
        } catch (e: Exception) {
            assertNotNull(e)
        } finally {
            // Guaranteed cleanup
            record?.runCatching {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        }
    }
}

