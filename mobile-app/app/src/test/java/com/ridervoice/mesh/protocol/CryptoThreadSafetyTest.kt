package com.ridervoice.mesh.protocol

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec

class CryptoThreadSafetyTest {

    @Test
    fun concurrentEncryptionAndTagging_isThreadSafeAcrossThreads() {
        val numThreads = 8
        val iterationsPerThread = 50
        val latch = CountDownLatch(numThreads)
        val failureFlag = AtomicBoolean(false)
        var failureMessage = ""

        val threads = (0 until numThreads).map { threadIdx ->
            Thread {
                try {
                    val key = ByteArray(32) { (threadIdx * 31 + it).toByte() }
                    val keySpec = SecretKeySpec(key, "AES")
                    val authKey = ByteArray(32) { (threadIdx * 17 + it).toByte() }
                    val sessId = UUID.randomUUID()
                    val srcId = UUID.randomUUID()

                    for (seq in 0 until iterationsPerThread) {
                        val plain = ByteArray(MeshConfig.BYTES_PER_FRAME) { ((threadIdx + seq + it) and 0xFF).toByte() }
                        val nonce = PayloadCrypto.nonce(epoch = threadIdx, sequence = seq, typeCode = MeshPacketType.VOICE.code)
                        val aad = PayloadCrypto.aad(
                            sessionHi = sessId.mostSignificantBits,
                            sessionLo = sessId.leastSignificantBits,
                            sourceHi = srcId.mostSignificantBits,
                            sourceLo = srcId.leastSignificantBits,
                            typeCode = MeshPacketType.VOICE.code,
                            epoch = threadIdx,
                            sequence = seq,
                            captureTimestamp = 5_000L + seq * 20L,
                            audioFormat = AudioFormatId.PCM16_16K_MONO
                        )

                        // 1. ThreadLocal Cipher seal & open
                        val sealed = PayloadCrypto.seal(keySpec, nonce, aad, plain)
                        val opened = PayloadCrypto.open(keySpec, nonce, aad, sealed)
                        if (!plain.contentEquals(opened)) {
                            failureFlag.set(true)
                            failureMessage = "Thread $threadIdx failed open at seq $seq"
                            break
                        }

                        // 2. ThreadLocal HMAC encode & decode
                        val packet = MeshPacket(
                            type = MeshPacketType.VOICE,
                            sessionId = sessId.toString(),
                            sourceId = srcId.toString(),
                            sequence = seq,
                            captureTimestamp = 5_000L + seq * 20L,
                            ttl = 5,
                            hopCount = 0,
                            previousHopId = srcId.toString(),
                            audioFormat = AudioFormatId.PCM16_16K_MONO,
                            epoch = threadIdx,
                            payload = sealed,
                            sessionHi = sessId.mostSignificantBits,
                            sessionLo = sessId.leastSignificantBits,
                            sourceHi = srcId.mostSignificantBits,
                            sourceLo = srcId.leastSignificantBits,
                            prevHopHi = srcId.mostSignificantBits,
                            prevHopLo = srcId.leastSignificantBits
                        )
                        val encoded = MeshPacketCodec.encode(packet, authKey)
                        val decoded = MeshPacketCodec.decode(encoded, authKey)
                        if (decoded !is DecodeResult.Ok || !decoded.packet.payload.contentEquals(sealed)) {
                            failureFlag.set(true)
                            failureMessage = "Thread $threadIdx failed decode at seq $seq"
                            break
                        }
                    }
                } catch (t: Throwable) {
                    failureFlag.set(true)
                    failureMessage = "Thread $threadIdx threw: ${t.message}"
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        val completed = latch.await(10, TimeUnit.SECONDS)
        assertTrue("All crypto threads must complete within timeout", completed)
        assertTrue("Concurrent crypto operations must not fail: $failureMessage", !failureFlag.get())
    }
}

