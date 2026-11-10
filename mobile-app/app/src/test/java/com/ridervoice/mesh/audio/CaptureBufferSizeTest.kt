package com.ridervoice.mesh.audio

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureBufferSizeTest {

    @Test
    fun calculateAudioRecordBufferSize_respectsMinimumFourFrames() {
        // BYTES_PER_FRAME = 640. 4 * 640 = 2560.
        val fourFrames = MeshConfig.BYTES_PER_FRAME * 4
        assertEquals(2560, fourFrames)

        // When minBuf is smaller than 4 frames, clamp to 4 frames
        assertEquals(fourFrames, calculateAudioRecordBufferSize(0))
        assertEquals(fourFrames, calculateAudioRecordBufferSize(1024))
        assertEquals(fourFrames, calculateAudioRecordBufferSize(2000))
        assertEquals(fourFrames, calculateAudioRecordBufferSize(fourFrames))

        // When minBuf is larger than 4 frames, respect hardware minBuf
        assertEquals(3000, calculateAudioRecordBufferSize(3000))
        assertEquals(8192, calculateAudioRecordBufferSize(8192))
    }
}

