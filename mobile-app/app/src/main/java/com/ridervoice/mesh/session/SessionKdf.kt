package com.ridervoice.mesh.session

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Slow key derivation function (PBKDF2-HMAC-SHA256) for offline mesh sessions.
 *
 * Derives an 80-byte master secret from the normalized pairing code:
 * - Bytes 0..15: 16-byte UUID session ID (with version 4 and variant bits set).
 * - Bytes 16..47: 32-byte hop-by-hop HMAC-SHA256 authentication key.
 * - Bytes 48..79: 32-byte AES-256-GCM payload encryption key.
 */
object SessionKdf {
    const val DEFAULT_ITERATIONS = 120_000
    private val SALT = "rvmesh/v2/session".toByteArray(StandardCharsets.UTF_8)

    class Derived(val sessionId: String, val authKey: ByteArray, val payloadKey: ByteArray)

    fun derive(normalizedCode: String, iterations: Int = DEFAULT_ITERATIONS): Derived {
        val spec = PBEKeySpec(normalizedCode.toCharArray(), SALT, iterations, 80 * 8)
        val out = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val id = out.copyOfRange(0, 16)
        id[6] = ((id[6].toInt() and 0x0f) or 0x40).toByte()
        id[8] = ((id[8].toInt() and 0x3f) or 0x80).toByte()
        val bb = ByteBuffer.wrap(id)
        val sessionId = UUID(bb.long, bb.long).toString()
        val authKey = out.copyOfRange(16, 48)
        val payloadKey = out.copyOfRange(48, 80)
        return Derived(sessionId, authKey, payloadKey)
    }
}
