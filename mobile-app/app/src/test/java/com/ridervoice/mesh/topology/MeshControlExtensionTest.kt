package com.ridervoice.mesh.topology

import com.ridervoice.mesh.convoy.ConvoyStateCodec
import com.ridervoice.mesh.convoy.ConvoyStateDecodeResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshControlExtensionTest {

    @Test
    fun position_roundTripExact15Bytes() {
        val fix = PositionFix(
            memberId = "rider-1",
            timestampSec = 1_732_800_123,
            latE6 = 18_520_430,
            lonE6 = 73_856_744,
            accuracyDecimeters = 45
        )
        val wire = MeshControlExtension.encodePosition(fix)
        assertEquals(MeshControlExtension.POSITION_SIZE_BYTES, wire.size)
        assertEquals(MeshControlExtension.DISCRIMINATOR_POSITION, wire[0])
        assertTrue(MeshControlExtension.isTopologyControlPayload(wire))

        val decoded = MeshControlExtension.decodePosition(wire, "rider-1")
        assertEquals(fix, decoded)
    }

    @Test
    fun goAnnounce_roundTripExact14Bytes() {
        val tag = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80.toByte())
        val announce = MeshControlExtension.GoAnnounce(
            electionEpoch = 7,
            electedGoTag = tag,
            electedRosterIdx = 2
        )
        val wire = MeshControlExtension.encodeGoAnnounce(announce)
        assertEquals(MeshControlExtension.GO_ANNOUNCE_SIZE_BYTES, wire.size)
        assertEquals(MeshControlExtension.DISCRIMINATOR_GO_ANNOUNCE, wire[0])
        assertTrue(MeshControlExtension.isTopologyControlPayload(wire))

        val decoded = MeshControlExtension.decodeGoAnnounce(wire)
        assertNotNull(decoded)
        assertEquals(7, decoded!!.electionEpoch)
        assertEquals(2, decoded.electedRosterIdx)
        assertArrayEquals(tag, decoded.electedGoTag)
    }

    @Test
    fun legacyConvoyCodec_returnsUnsupportedVersionCleanlyForTopologyBytes() {
        val posWire = MeshControlExtension.encodePosition(
            PositionFix("rider-1", 100, 18_520_000, 73_856_000, 20)
        )
        val goWire = MeshControlExtension.encodeGoAnnounce(
            MeshControlExtension.GoAnnounce(1, ByteArray(8) { 1 }, 0)
        )

        val posRes = ConvoyStateCodec.decode(posWire, 3)
        val goRes = ConvoyStateCodec.decode(goWire, 3)

        assertTrue(posRes is ConvoyStateDecodeResult.UnsupportedVersion)
        assertTrue(goRes is ConvoyStateDecodeResult.UnsupportedVersion)
        assertFalse(MeshControlExtension.isTopologyControlPayload(byteArrayOf(0x01, 0x00)))
    }
}
