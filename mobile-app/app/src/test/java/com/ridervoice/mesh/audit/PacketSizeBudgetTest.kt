package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.MeshPacketCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketSizeBudgetTest {

    @Test
    fun voicePacketSizeBudget_matchesConstants() {
        assertEquals("Protocol v3 header length must be 75 bytes", 75, MeshPacketCodec.HEADER_LEN)
        assertEquals("Outer HMAC authentication tag must be 16 bytes", 16, MeshPacketCodec.TAG_LEN)
        assertEquals("PCM frame size must be 640 bytes", 640, MeshConfig.BYTES_PER_FRAME)
        assertEquals("Encrypted voice payload must be 656 bytes", 656, MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES)

        val totalVoiceDatagramBytes = MeshPacketCodec.HEADER_LEN + MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES + MeshPacketCodec.TAG_LEN
        assertEquals("Total voice datagram must be exactly 747 bytes", 747, totalVoiceDatagramBytes)
        assertEquals("Max voice datagram budget must be 800 bytes", 800, MeshConfig.MAX_VOICE_DATAGRAM_BYTES)

        println("Voice packet size budget: $totalVoiceDatagramBytes bytes (max budget ${MeshConfig.MAX_VOICE_DATAGRAM_BYTES})")
    }
}

