package com.ridervoice.mesh.protocol

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class V4FuzzTest {

    private val payloadKey = ByteArray(32) { (it * 7 + 1).toByte() }
    private val selfId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun tenThousandRandomArrays_neverThrow() {
        val rng = Random(12345)
        for (i in 0 until 10_000) {
            val len = rng.nextInt(300)
            val randomData = ByteArray(len)
            rng.nextBytes(randomData)

            val res = MeshPacketV4.decode(randomData, payloadKey)
            assertNotNull(res)
            // Random data without valid GCM tag must almost certainly be invalid
            if (len < MeshPacketV4.MIN_PACKET_LEN) {
                assertTrue(res is DecodeResult.Invalid)
            }
        }
    }

    @Test
    fun tenThousandMutatedValidPackets_neverThrow() {
        val rng = Random(67890)
        val validPacket = MeshPacket(
            type = MeshPacketType.VOICE,
            sourceId = selfId,
            previousHopId = selfId,
            sequence = 100,
            ttl = 5,
            hopCount = 0,
            audioFormat = AudioFormatId.OPUS_16K_MONO_20MS,
            epoch = 42,
            payload = ByteArray(40) { it.toByte() }
        )
        val wire = MeshPacketV4.encode(validPacket, payloadKey)

        for (i in 0 until 10_000) {
            val mutated = wire.copyOf()
            val mutations = rng.nextInt(5) + 1
            for (m in 0 until mutations) {
                val idx = rng.nextInt(mutated.size)
                mutated[idx] = (mutated[idx].toInt() xor (rng.nextInt(255) + 1)).toByte()
            }

            val res = MeshPacketV4.decode(mutated, payloadKey)
            assertNotNull("Mutated decode must never throw and always return DecodeResult", res)
        }
    }
}

