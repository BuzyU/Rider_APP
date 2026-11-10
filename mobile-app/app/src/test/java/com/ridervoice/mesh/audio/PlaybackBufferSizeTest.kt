package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferSizeTest {

    @Test
    fun calculateAudioTrackBufferSize_respectsMinimumTwoFrames() {
        // BYTES_PER_FRAME = 640. 2 * 640 = 1280.
        val twoFrames = MeshConfig.BYTES_PER_FRAME * 2
        assertEquals(1280, twoFrames)

        // When minBuf is smaller than 2 frames, clamp to 2 frames
        assertEquals(twoFrames, calculateAudioTrackBufferSize(0))
        assertEquals(twoFrames, calculateAudioTrackBufferSize(500))
        assertEquals(twoFrames, calculateAudioTrackBufferSize(1000))
        assertEquals(twoFrames, calculateAudioTrackBufferSize(twoFrames))

        // When minBuf is larger than 2 frames, respect hardware minBuf
        assertEquals(2048, calculateAudioTrackBufferSize(2048))
        assertEquals(4096, calculateAudioTrackBufferSize(4096))
    }
}

