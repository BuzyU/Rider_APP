package com.ridervoice.mesh.session

import com.ridervoice.mesh.MeshConfig
import java.security.SecureRandom

/**
 * Standalone offline session. No backend, Firebase, Internet or LiveKit involvement.
 *
 * The sessionId, authentication key, and payload encryption key are all derived from the pairing
 * code via PBKDF2-HMAC-SHA256 (SessionKdf). The code is 16 characters from Crockford Base32 (80 bits)
 * formatted in groups of 4 (e.g. K7QX-M2PA-9TDE-4WZH).
 */
class OfflineSession private constructor(
    val sessionId: String,
    val pairingCode: String,
    private val key: ByteArray,
    private val pKey: ByteArray,
    val createdAt: Long,
    val expiresAt: Long,
    val maxDevices: Int = MeshConfig.MAX_DEVICES
) {
    fun isExpired(now: Long): Boolean = now >= expiresAt

    /** 32-byte HMAC key derived from the pairing code. Never sent over the wire. */
    fun authKey(): ByteArray = key.copyOf()

    /** 32-byte AES-256 payload encryption key derived from the pairing code. Never sent over the wire. */
    fun payloadKey(): ByteArray = pKey.copyOf()

    override fun toString(): String =
        "OfflineSession(id=$sessionId, expiresAt=$expiresAt)"

    override fun equals(other: Any?): Boolean =
        other is OfflineSession &&
            sessionId == other.sessionId &&
            pairingCode == other.pairingCode &&
            key.contentEquals(other.key) &&
            pKey.contentEquals(other.pKey) &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt &&
            maxDevices == other.maxDevices

    override fun hashCode(): Int = sessionId.hashCode()

    companion object {
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  // 32 symbols Crockford Base32
        const val CODE_LENGTH = 16
        private val BOOTSTRAP_KEY = "rvmesh/v2/online-room-bootstrap".toByteArray(Charsets.UTF_8)

        fun create(
            now: Long,
            random: SecureRandom = SecureRandom(),
            iterations: Int = SessionKdf.DEFAULT_ITERATIONS
        ): OfflineSession {
            val code = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            return fromCode(code, now, iterations) ?: error("Failed to create session from generated code")
        }

        /** Returns null if the code is malformed. Local expiry is relative to when this device joined. */
        fun fromCode(
            rawCode: String,
            now: Long,
            iterations: Int = SessionKdf.DEFAULT_ITERATIONS
        ): OfflineSession? {
            val code = normalize(rawCode) ?: return null
            val derived = SessionKdf.derive(code, iterations)
            return OfflineSession(
                sessionId = derived.sessionId,
                pairingCode = code,
                key = derived.authKey,
                pKey = derived.payloadKey,
                createdAt = now,
                expiresAt = now + MeshConfig.SESSION_LIFETIME_MS
            )
        }

        /**
         * Deterministically derives a shared 16-character pairing code and [OfflineSession]
         * from an online convoy's canonical room identifier (e.g. lkRoomName or roomId).
         */
        fun fromOnlineRoom(
            canonicalRoomKey: String,
            now: Long,
            iterations: Int = SessionKdf.DEFAULT_ITERATIONS
        ): OfflineSession? {
            val clean = canonicalRoomKey.trim().lowercase()
            if (clean.isBlank()) return null
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(BOOTSTRAP_KEY, "HmacSHA256"))
            val digest = mac.doFinal(clean.toByteArray(Charsets.UTF_8))
            // Encode first 80 bits (10 bytes) into 16 Crockford Base32 chars (5 bits each)
            var buffer = 0L
            var bitsLeft = 0
            val sb = StringBuilder(CODE_LENGTH)
            for (i in 0 until 10) {
                buffer = (buffer shl 8) or (digest[i].toInt() and 0xFF).toLong()
                bitsLeft += 8
                while (bitsLeft >= 5 && sb.length < CODE_LENGTH) {
                    bitsLeft -= 5
                    val idx = ((buffer ushr bitsLeft) and 0x1FL).toInt()
                    sb.append(ALPHABET[idx])
                }
            }
            return fromCode(sb.toString(), now, iterations)
        }

        fun normalize(raw: String): String? {
            val code = raw.trim().uppercase()
                .replace("-", "")
                .replace(" ", "")
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1')
            if (code.length != CODE_LENGTH) return null
            return if (code.all { it in ALPHABET }) code else null
        }

        fun format(code: String): String = code.chunked(4).joinToString("-")
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

    /** Non-mutating admission check for pre-AEAD validation. */
    @Synchronized
    fun canAdmit(deviceId: String): Admission {
        val now = clock()
        if (session.isExpired(now)) return Admission.REJECTED_EXPIRED
        purgeStale(now)
        if (lastSeen.containsKey(deviceId)) {
            return Admission.ALREADY_MEMBER
        }
        if (lastSeen.size >= session.maxDevices) return Admission.REJECTED_FULL
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
