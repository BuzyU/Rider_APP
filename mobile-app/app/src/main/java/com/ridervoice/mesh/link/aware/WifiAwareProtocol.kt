package com.ridervoice.mesh.link.aware

import com.ridervoice.mesh.convoy.ConvoyHash
import com.ridervoice.mesh.session.OfflineSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure Kotlin credentials, discovery beacon (32 bytes), and L2 signaling message codecs
 * for Wi-Fi Aware (NAN) Bus/Chain link establishment.
 */
object WifiAwareCredentials {
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val HEX = "0123456789abcdef"

    /**
     * Derives the per-room Wi-Fi Aware service name (<= 19 ASCII chars `[a-z0-9-]`),
     * isolating discovery to members holding the room's `OfflineSession`.
     */
    fun deriveServiceName(session: OfflineSession): String? {
        if (session.isClosed) return null
        val authKeyBytes = try {
            session.authKey()
        } catch (_: IllegalStateException) {
            return null
        }
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(authKeyBytes, "HmacSHA256"))
        }
        val digest = mac.doFinal("RV-AWARE-SVC-V1|${session.sessionId}".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(19)
        sb.append("rv-")
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4])
            sb.append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Derives a symmetric, orientation-independent 16-char Base62 WPA2 passphrase for the
     * Wi-Fi Aware Data Path (NDP) between [memberA] and [memberB].
     */
    fun derivePairPassphrase(
        session: OfflineSession,
        memberA: String,
        memberB: String
    ): String? {
        if (session.isClosed) return null
        val authKeyBytes = try {
            session.authKey()
        } catch (_: IllegalStateException) {
            return null
        }
        val normA = ConvoyHash.normalizeNfcString(memberA)
        val normB = ConvoyHash.normalizeNfcString(memberB)
        val cmp = ConvoyHash.compareUtf8Bytes(
            normA.toByteArray(Charsets.UTF_8),
            normB.toByteArray(Charsets.UTF_8)
        )
        val first = if (cmp <= 0) normA else normB
        val second = if (cmp <= 0) normB else normA
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(authKeyBytes, "HmacSHA256"))
        }
        val digest = mac.doFinal(
            "RV-AWARE-PSK-V1|${session.sessionId}|$first|$second".toByteArray(Charsets.UTF_8)
        )
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            val b = digest[i].toInt() and 0xFF
            sb.append(BASE62[b % BASE62.length])
        }
        return sb.toString()
    }

    /**
     * Strictly breaks symmetric NDP initiation races: for any pair `{A, B}`,
     * only the member with the lexicographically smaller UTF-8 ID acts as NDP Initiator (Subscriber).
     */
    fun isInitiator(localMemberId: String, peerMemberId: String): Boolean {
        val normLocal = ConvoyHash.normalizeNfcString(localMemberId)
        val normPeer = ConvoyHash.normalizeNfcString(peerMemberId)
        return ConvoyHash.compareUtf8Bytes(
            normLocal.toByteArray(Charsets.UTF_8),
            normPeer.toByteArray(Charsets.UTF_8)
        ) < 0
    }
}

/**
 * Fixed 32-byte Wi-Fi Aware discovery beacon advertised in `PublishConfig.serviceSpecificInfo`,
 * well below the 255-byte minimum `WifiAwareCharacteristics.maxServiceSpecificInfoLength`.
 */
data class AwareDiscoveryBeacon(
    val version: Byte = VERSION_V1,
    val rosterIdx: Int,
    val flags: Int,
    val activeNdpCount: Int,
    val maxNdpSessions: Int,
    val memberTag: ByteArray,
    val componentRootTag: ByteArray,
    val latE6: Int,
    val lonE6: Int,
    val connectedMask: Int,
    val reachableMask: Int
) {
    init {
        require(memberTag.size == TAG_BYTES) { "memberTag must be $TAG_BYTES bytes" }
        require(componentRootTag.size == TAG_BYTES) { "componentRootTag must be $TAG_BYTES bytes" }
        require(rosterIdx in 0..0xFF) { "rosterIdx must fit uint8" }
        require(flags in 0..0xFF) { "flags must fit uint8" }
        require(activeNdpCount in 0..15) { "activeNdpCount must fit uint4" }
        require(maxNdpSessions in 0..15) { "maxNdpSessions must fit uint4" }
        require(connectedMask in 0..0xFFFF) { "connectedMask must fit uint16" }
        require(reachableMask in 0..0xFFFF) { "reachableMask must fit uint16" }
    }

    val hasValidGps: Boolean get() = (flags and FLAG_HAS_GPS) != 0
    val isOfflineVoiceActive: Boolean get() = (flags and FLAG_OFFLINE_VOICE) != 0
    val isP2pFallbackReady: Boolean get() = (flags and FLAG_P2P_FALLBACK_READY) != 0

    fun encode(): ByteArray {
        val packedCounts = ((activeNdpCount and 0x0F) shl 4) or (maxNdpSessions and 0x0F)
        return ByteBuffer.allocate(ENCODED_SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(version)
            .put(rosterIdx.toByte())
            .put(flags.toByte())
            .put(packedCounts.toByte())
            .put(memberTag)
            .put(componentRootTag)
            .putInt(latE6)
            .putInt(lonE6)
            .putShort(connectedMask.toShort())
            .putShort(reachableMask.toShort())
            .array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AwareDiscoveryBeacon) return false
        return version == other.version &&
            rosterIdx == other.rosterIdx &&
            flags == other.flags &&
            activeNdpCount == other.activeNdpCount &&
            maxNdpSessions == other.maxNdpSessions &&
            memberTag.contentEquals(other.memberTag) &&
            componentRootTag.contentEquals(other.componentRootTag) &&
            latE6 == other.latE6 &&
            lonE6 == other.lonE6 &&
            connectedMask == other.connectedMask &&
            reachableMask == other.reachableMask
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + rosterIdx
        result = 31 * result + flags
        result = 31 * result + activeNdpCount
        result = 31 * result + maxNdpSessions
        result = 31 * result + memberTag.contentHashCode()
        result = 31 * result + componentRootTag.contentHashCode()
        result = 31 * result + latE6
        result = 31 * result + lonE6
        result = 31 * result + connectedMask
        result = 31 * result + reachableMask
        return result
    }

    companion object {
        const val VERSION_V1: Byte = 1
        const val ENCODED_SIZE_BYTES = 32
        const val TAG_BYTES = 8

        const val FLAG_HAS_GPS = 0x01
        const val FLAG_OFFLINE_VOICE = 0x02
        const val FLAG_P2P_FALLBACK_READY = 0x04

        fun decode(bytes: ByteArray): AwareDiscoveryBeacon? {
            if (bytes.size != ENCODED_SIZE_BYTES) return null
            if (bytes[0] != VERSION_V1) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val version = bb.get()
            val rosterIdx = bb.get().toInt() and 0xFF
            val flags = bb.get().toInt() and 0xFF
            val packedCounts = bb.get().toInt() and 0xFF
            val activeNdp = (packedCounts ushr 4) and 0x0F
            val maxNdps = packedCounts and 0x0F
            val memberTag = ByteArray(TAG_BYTES)
            bb.get(memberTag)
            val componentRootTag = ByteArray(TAG_BYTES)
            bb.get(componentRootTag)
            val latE6 = bb.int
            val lonE6 = bb.int
            val connectedMask = bb.short.toInt() and 0xFFFF
            val reachableMask = bb.short.toInt() and 0xFFFF
            return AwareDiscoveryBeacon(
                version = version,
                rosterIdx = rosterIdx,
                flags = flags,
                activeNdpCount = activeNdp,
                maxNdpSessions = maxNdps,
                memberTag = memberTag,
                componentRootTag = componentRootTag,
                latE6 = latE6,
                lonE6 = lonE6,
                connectedMask = connectedMask,
                reachableMask = reachableMask
            )
        }
    }
}

/**
 * Compact L2 signaling frames exchanged over `DiscoverySession.sendMessage()` (all <= 16 bytes).
 */
sealed class AwareSignalingMessage {
    abstract val senderMemberTag: ByteArray
    abstract fun encode(): ByteArray

    data class ConnectReq(
        override val senderMemberTag: ByteArray,
        val senderRosterIdx: Int,
        val udpPort: Int,
        val epoch: Int
    ) : AwareSignalingMessage() {
        init {
            require(senderMemberTag.size == TAG_BYTES)
            require(senderRosterIdx in 0..0xFF)
            require(udpPort in 0..0xFFFF)
        }

        override fun encode(): ByteArray = ByteBuffer.allocate(CONNECT_MSG_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_CONNECT_REQ)
            .put(senderMemberTag)
            .put(senderRosterIdx.toByte())
            .putShort(udpPort.toShort())
            .putInt(epoch)
            .array()

        override fun equals(other: Any?): Boolean =
            other is ConnectReq &&
                senderMemberTag.contentEquals(other.senderMemberTag) &&
                senderRosterIdx == other.senderRosterIdx &&
                udpPort == other.udpPort &&
                epoch == other.epoch

        override fun hashCode(): Int {
            var r = senderMemberTag.contentHashCode()
            r = 31 * r + senderRosterIdx
            r = 31 * r + udpPort
            r = 31 * r + epoch
            return r
        }
    }

    data class ConnectAck(
        override val senderMemberTag: ByteArray,
        val senderRosterIdx: Int,
        val udpPort: Int,
        val epoch: Int
    ) : AwareSignalingMessage() {
        init {
            require(senderMemberTag.size == TAG_BYTES)
            require(senderRosterIdx in 0..0xFF)
            require(udpPort in 0..0xFFFF)
        }

        override fun encode(): ByteArray = ByteBuffer.allocate(CONNECT_MSG_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_CONNECT_ACK)
            .put(senderMemberTag)
            .put(senderRosterIdx.toByte())
            .putShort(udpPort.toShort())
            .putInt(epoch)
            .array()

        override fun equals(other: Any?): Boolean =
            other is ConnectAck &&
                senderMemberTag.contentEquals(other.senderMemberTag) &&
                senderRosterIdx == other.senderRosterIdx &&
                udpPort == other.udpPort &&
                epoch == other.epoch

        override fun hashCode(): Int {
            var r = senderMemberTag.contentHashCode()
            r = 31 * r + senderRosterIdx
            r = 31 * r + udpPort
            r = 31 * r + epoch
            return r
        }
    }

    data class ConnectReject(
        override val senderMemberTag: ByteArray,
        val reasonCode: Int
    ) : AwareSignalingMessage() {
        init {
            require(senderMemberTag.size == TAG_BYTES)
            require(reasonCode in 0..0xFF)
        }

        override fun encode(): ByteArray = ByteBuffer.allocate(SHORT_MSG_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_CONNECT_REJECT)
            .put(senderMemberTag)
            .put(reasonCode.toByte())
            .array()

        override fun equals(other: Any?): Boolean =
            other is ConnectReject &&
                senderMemberTag.contentEquals(other.senderMemberTag) &&
                reasonCode == other.reasonCode

        override fun hashCode(): Int = 31 * senderMemberTag.contentHashCode() + reasonCode
    }

    data class LinkNudge(
        override val senderMemberTag: ByteArray,
        val senderRosterIdx: Int
    ) : AwareSignalingMessage() {
        init {
            require(senderMemberTag.size == TAG_BYTES)
            require(senderRosterIdx in 0..0xFF)
        }

        override fun encode(): ByteArray = ByteBuffer.allocate(SHORT_MSG_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_LINK_NUDGE)
            .put(senderMemberTag)
            .put(senderRosterIdx.toByte())
            .array()

        override fun equals(other: Any?): Boolean =
            other is LinkNudge &&
                senderMemberTag.contentEquals(other.senderMemberTag) &&
                senderRosterIdx == other.senderRosterIdx

        override fun hashCode(): Int = 31 * senderMemberTag.contentHashCode() + senderRosterIdx
    }

    data class LinkTeardown(
        override val senderMemberTag: ByteArray,
        val reasonCode: Int
    ) : AwareSignalingMessage() {
        init {
            require(senderMemberTag.size == TAG_BYTES)
            require(reasonCode in 0..0xFF)
        }

        override fun encode(): ByteArray = ByteBuffer.allocate(SHORT_MSG_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_LINK_TEARDOWN)
            .put(senderMemberTag)
            .put(reasonCode.toByte())
            .array()

        override fun equals(other: Any?): Boolean =
            other is LinkTeardown &&
                senderMemberTag.contentEquals(other.senderMemberTag) &&
                reasonCode == other.reasonCode

        override fun hashCode(): Int = 31 * senderMemberTag.contentHashCode() + reasonCode
    }

    companion object {
        const val TAG_BYTES = 8
        const val CONNECT_MSG_BYTES = 16
        const val SHORT_MSG_BYTES = 10

        const val TYPE_CONNECT_REQ: Byte = 0x01
        const val TYPE_CONNECT_ACK: Byte = 0x02
        const val TYPE_CONNECT_REJECT: Byte = 0x03
        const val TYPE_LINK_NUDGE: Byte = 0x04
        const val TYPE_LINK_TEARDOWN: Byte = 0x05

        const val REASON_FULL = 1
        const val REASON_NOT_NEEDED = 2
        const val REASON_UNKNOWN_PEER = 3
        const val REASON_REPLACED = 4
        const val REASON_ZOMBIE = 5

        fun decode(bytes: ByteArray): AwareSignalingMessage? {
            if (bytes.isEmpty()) return null
            return when (bytes[0]) {
                TYPE_CONNECT_REQ, TYPE_CONNECT_ACK -> {
                    if (bytes.size != CONNECT_MSG_BYTES) return null
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    val type = bb.get()
                    val tag = ByteArray(TAG_BYTES)
                    bb.get(tag)
                    val rosterIdx = bb.get().toInt() and 0xFF
                    val port = bb.short.toInt() and 0xFFFF
                    val epoch = bb.int
                    if (type == TYPE_CONNECT_REQ) {
                        ConnectReq(tag, rosterIdx, port, epoch)
                    } else {
                        ConnectAck(tag, rosterIdx, port, epoch)
                    }
                }
                TYPE_CONNECT_REJECT -> {
                    if (bytes.size != SHORT_MSG_BYTES) return null
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    bb.get()
                    val tag = ByteArray(TAG_BYTES)
                    bb.get(tag)
                    val reason = bb.get().toInt() and 0xFF
                    ConnectReject(tag, reason)
                }
                TYPE_LINK_NUDGE -> {
                    if (bytes.size != SHORT_MSG_BYTES) return null
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    bb.get()
                    val tag = ByteArray(TAG_BYTES)
                    bb.get(tag)
                    val rosterIdx = bb.get().toInt() and 0xFF
                    LinkNudge(tag, rosterIdx)
                }
                TYPE_LINK_TEARDOWN -> {
                    if (bytes.size != SHORT_MSG_BYTES) return null
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    bb.get()
                    val tag = ByteArray(TAG_BYTES)
                    bb.get(tag)
                    val reason = bb.get().toInt() and 0xFF
                    LinkTeardown(tag, reason)
                }
                else -> null
            }
        }
    }
}
