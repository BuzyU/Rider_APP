package com.ridervoice.mesh.topology

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Topology CONTROL payload codecs using high-bit Byte-0 discriminators (`0x81`, `0x82`),
 * leaving legacy `ConvoyStateCodec` (`EXT_VERSION = 0x01`) 100% untouched.
 */
object MeshControlExtension {
    const val DISCRIMINATOR_CONVOY_STATE: Byte = 0x01
    const val DISCRIMINATOR_POSITION: Byte = 0x81.toByte()
    const val DISCRIMINATOR_GO_ANNOUNCE: Byte = 0x82.toByte()

    const val POSITION_SIZE_BYTES = 15
    const val GO_ANNOUNCE_SIZE_BYTES = 14
    const val GO_TAG_BYTES = 8

    data class GoAnnounce(
        val electionEpoch: Int,
        val electedGoTag: ByteArray,
        val electedRosterIdx: Int
    ) {
        init {
            require(electedGoTag.size == GO_TAG_BYTES) { "electedGoTag must be $GO_TAG_BYTES bytes" }
            require(electedRosterIdx in 0..255) { "electedRosterIdx must fit uint8" }
        }

        /** Deterministic winner comparison: higher epoch -> lower rosterIdx -> lower unsigned tag. */
        fun beats(other: GoAnnounce): Boolean {
            if (electionEpoch != other.electionEpoch) return electionEpoch > other.electionEpoch
            if (electedRosterIdx != other.electedRosterIdx) return electedRosterIdx < other.electedRosterIdx
            for (i in 0 until GO_TAG_BYTES) {
                val a = electedGoTag[i].toInt() and 0xFF
                val b = other.electedGoTag[i].toInt() and 0xFF
                if (a != b) return a < b
            }
            return false
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GoAnnounce) return false
            return electionEpoch == other.electionEpoch &&
                electedRosterIdx == other.electedRosterIdx &&
                electedGoTag.contentEquals(other.electedGoTag)
        }

        override fun hashCode(): Int {
            var res = electionEpoch
            res = 31 * res + electedGoTag.contentHashCode()
            res = 31 * res + electedRosterIdx
            return res
        }
    }

    fun isTopologyControlPayload(bytes: ByteArray): Boolean {
        val first = bytes.firstOrNull() ?: return false
        return first == DISCRIMINATOR_POSITION || first == DISCRIMINATOR_GO_ANNOUNCE
    }

    fun encodePosition(fix: PositionFix): ByteArray {
        val clampedAcc = fix.accuracyDecimeters.coerceIn(0, PositionFix.UNKNOWN_ACCURACY_DECIMETERS)
        return ByteBuffer.allocate(POSITION_SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(DISCRIMINATOR_POSITION)
            .putInt(fix.timestampSec)
            .putInt(fix.latE6)
            .putInt(fix.lonE6)
            .putShort(clampedAcc.toShort())
            .array()
    }

    fun decodePosition(bytes: ByteArray, memberId: String): PositionFix? {
        if (bytes.size != POSITION_SIZE_BYTES || bytes[0] != DISCRIMINATOR_POSITION) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        bb.get() // discriminator
        val ts = bb.int
        val latE6 = bb.int
        val lonE6 = bb.int
        val acc = bb.short.toInt() and 0xFFFF
        return PositionFix(
            memberId = memberId,
            timestampSec = ts,
            latE6 = latE6,
            lonE6 = lonE6,
            accuracyDecimeters = acc
        )
    }

    fun encodeGoAnnounce(announce: GoAnnounce): ByteArray {
        return ByteBuffer.allocate(GO_ANNOUNCE_SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(DISCRIMINATOR_GO_ANNOUNCE)
            .putInt(announce.electionEpoch)
            .put(announce.electedGoTag)
            .put(announce.electedRosterIdx.toByte())
            .array()
    }

    fun decodeGoAnnounce(bytes: ByteArray): GoAnnounce? {
        if (bytes.size != GO_ANNOUNCE_SIZE_BYTES || bytes[0] != DISCRIMINATOR_GO_ANNOUNCE) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        bb.get() // discriminator
        val epoch = bb.int
        val tag = ByteArray(GO_TAG_BYTES)
        bb.get(tag)
        val rosterIdx = bb.get().toInt() and 0xFF
        return GoAnnounce(
            electionEpoch = epoch,
            electedGoTag = tag,
            electedRosterIdx = rosterIdx
        )
    }
}
