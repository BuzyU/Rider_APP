package com.ridervoice.mesh.session

import com.ridervoice.mesh.MeshConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Standalone offline session. No backend, Firebase, Internet or LiveKit involvement.
 *
 * The sessionId and the authentication key are both derived from the pairing code, so a joining
 * device needs only the code (typed or via QR). The code is 10 characters from a 32-symbol alphabet
 * (50 bits) rather than a 6-digit code: the code is also the HMAC secret, and 6 digits could be
 * brute-forced offline from a single captured packet.
 */
data class OfflineSession(
    val sessionId: String,
    val pairingCode: String,
    val createdAt: Long,
    val expiresAt: Long,
    val maxDevices: Int = MeshConfig.MAX_DEVICES
) {
    fun isExpired(now: Long): Boolean = now >= expiresAt

    /** 32-byte HMAC key derived from the pairing code. Never sent over the wire. */
    fun authKey(): ByteArray = deriveKey(pairingCode)

    companion object {
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789!"  // 32 symbols, no 0/O/1/I/L
        const val CODE_LENGTH = 10

        fun create(now: Long, random: SecureRandom = SecureRandom()): OfflineSession {
            val code = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            return fromCode(code, now) ?: OfflineSession(
                sessionId = sessionIdFor(code),
                pairingCode = code,
                createdAt = now,
                expiresAt = now + MeshConfig.SESSION_LIFETIME_MS
            )
        }

        /** Returns null if the code is malformed. Local expiry is relative to when this device joined. */
        fun fromCode(rawCode: String, now: Long): OfflineSession? {
            val code = normalize(rawCode) ?: return null
            return OfflineSession(
                sessionId = sessionIdFor(code),
                pairingCode = code,
                createdAt = now,
                expiresAt = now + MeshConfig.SESSION_LIFETIME_MS
            )
        }

        fun normalize(raw: String): String? {
            val code = raw.trim().uppercase().replace("-", "").replace(" ", "")
            if (code.length != CODE_LENGTH) return null
            return if (code.all { it in ALPHABET }) code else null
        }

        fun sessionIdFor(code: String): String =
            UUID.nameUUIDFromBytes("rvmesh:session:$code".toByteArray(StandardCharsets.UTF_8)).toString()

        fun deriveKey(code: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("rvmesh:key:$code".toByteArray(StandardCharsets.UTF_8))
    }
}

/** Tracks session membership and enforces the device cap (the 7th distinct device is rejected). */
class SessionManager(
    val session: OfflineSession,
    private val selfId: String,
    private val clock: () -> Long
) {
    private val lastSeen = LinkedHashMap<String, Long>().apply { put(selfId, clock()) }

    enum class Admission { ADMITTED, ALREADY_MEMBER, REJECTED_FULL, REJECTED_EXPIRED }

    @Synchronized
    fun admit(deviceId: String): Admission {
        val now = clock()
        if (session.isExpired(now)) return Admission.REJECTED_EXPIRED
        purgeStale(now)
        if (lastSeen.containsKey(deviceId)) {
            lastSeen[deviceId] = now
            return Admission.ALREADY_MEMBER
        }
        if (lastSeen.size >= session.maxDevices) return Admission.REJECTED_FULL
        lastSeen[deviceId] = now
        return Admission.ADMITTED
    }

    @Synchronized
    fun touch(deviceId: String) { if (lastSeen.containsKey(deviceId)) lastSeen[deviceId] = clock() }

    @Synchronized
    fun remove(deviceId: String) { if (deviceId != selfId) lastSeen.remove(deviceId) }

    @Synchronized
    fun members(): Set<String> { purgeStale(clock()); return lastSeen.keys.toSet() }

    @Synchronized
    fun isMember(deviceId: String): Boolean { purgeStale(clock()); return lastSeen.containsKey(deviceId) }

    /** Leaving clears all remote membership state. */
    @Synchronized
    fun clear() { lastSeen.clear(); lastSeen[selfId] = clock() }

    private fun purgeStale(now: Long) {
        val it = lastSeen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key != selfId && now - e.value > MeshConfig.MEMBER_TIMEOUT_MS) it.remove()
        }
    }
}

