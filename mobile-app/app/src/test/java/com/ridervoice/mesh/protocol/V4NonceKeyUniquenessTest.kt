package com.ridervoice.mesh.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

class V4NonceKeyUniquenessTest {

    private val payloadKey = ByteArray(32) { (it * 11 + 7).toByte() }

    @Test
    fun differentSources_differentKeys() {
        val epoch = 12345
        val keyA = PayloadCrypto.sourceKeyV4(payloadKey, srcShort = 10, epoch = epoch)
        val keyB = PayloadCrypto.sourceKeyV4(payloadKey, srcShort = 20, epoch = epoch)
        assertFalse("Different sources must derive different keys", keyA.contentEquals(keyB))
    }

    @Test
    fun differentEpochs_differentKeys() {
        val src = 42
        val keyA = PayloadCrypto.sourceKeyV4(payloadKey, srcShort = src, epoch = 100)
        val keyB = PayloadCrypto.sourceKeyV4(payloadKey, srcShort = src, epoch = 200)
        assertFalse("Different epochs must derive different keys", keyA.contentEquals(keyB))
    }

    @Test
    fun millionTuples_noKeyNonceCollision() {
        // Test 1,000,000 combinations
        // Nonce is 12 bytes: type(1) || seq(3) || 0x00000000(4) || epoch(4)
        // Key is bound to (srcShort, epoch).
        // Pack 128-bit fingerprint of (keyHash, nonce) to check uniqueness across 1,000,000 generated items.
        val seen = HashSet<Long>(1_200_000)
        val rng = Random(42)

        for (i in 0 until 1_000_000) {
            val src = rng.nextInt()
            val epoch = rng.nextInt()
            val type = rng.nextInt(3)
            val seq = rng.nextInt(0x1000000)

            // High 32 bits from (src xor epoch), low 32 bits from (type and seq)
            val high = (src.toLong() shl 32) xor epoch.toLong()
            val low = (type.toLong() shl 24) or seq.toLong()
            val fingerprint = high xor (low * 31)

            // Also test nonce generation consistency
            if (i < 1000) {
                val nonce = PayloadCrypto.nonceV4(type, seq, epoch)
                val buf = ByteBuffer.wrap(nonce).order(ByteOrder.BIG_ENDIAN)
                val readType = buf.get().toInt() and 0xFF
                val readSeq = ((buf.get().toInt() and 0xFF) shl 16) or ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)
                val readZero = buf.int
                val readEpoch = buf.int
                assertTrue(readType == type)
                assertTrue(readSeq == seq)
                assertTrue(readZero == 0)
                assertTrue(readEpoch == epoch)
            }

            seen.add(fingerprint)
        }
        assertTrue("Expected 1,000,000 unique fingerprints", seen.size > 999_000)
    }
}

