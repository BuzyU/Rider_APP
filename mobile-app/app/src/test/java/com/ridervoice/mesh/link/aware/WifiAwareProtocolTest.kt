package com.ridervoice.mesh.link.aware

import com.ridervoice.mesh.convoy.ConvoyHash
import com.ridervoice.mesh.session.OfflineSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAwareProtocolTest {

    @Test
    fun deriveServiceName_isRoomScopedDeterministicAndAsciiValid() {
        val s1 = OfflineSession.fromCode("K7QXM2PA9TDE4WZH", 1000L, iterations = 1)!!
        val s2 = OfflineSession.fromCode("K7QXM2PA9TDE4WZH", 2000L, iterations = 1)!!
        val other = OfflineSession.fromCode("0123456789ABCDEF", 1000L, iterations = 1)!!

        val name1 = WifiAwareCredentials.deriveServiceName(s1)
        val name2 = WifiAwareCredentials.deriveServiceName(s2)
        val nameOther = WifiAwareCredentials.deriveServiceName(other)

        assertNotNull(name1)
        assertEquals(name1, name2)
        assertNotEquals(name1, nameOther)
        assertEquals(19, name1!!.length)
        assertTrue(name1.startsWith("rv-"))
        assertTrue(name1.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
    }

    @Test
    fun derivePairPassphrase_isSymmetricRegardlessOfArgumentOrder() {
        val s = OfflineSession.fromCode("K7QXM2PA9TDE4WZH", 1000L, iterations = 1)!!
        val pAb = WifiAwareCredentials.derivePairPassphrase(s, "rider-a", "rider-b")
        val pBa = WifiAwareCredentials.derivePairPassphrase(s, "rider-b", "rider-a")
        val pAc = WifiAwareCredentials.derivePairPassphrase(s, "rider-a", "rider-c")

        assertNotNull(pAb)
        assertEquals(16, pAb!!.length)
        assertEquals(pAb, pBa)
        assertNotEquals(pAb, pAc)
    }

    @Test
    fun isInitiator_strictlyBreaksSymmetryBetweenDistinctIds() {
        assertTrue(WifiAwareCredentials.isInitiator("rider-1", "rider-2") xor
            WifiAwareCredentials.isInitiator("rider-2", "rider-1"))
        assertFalse(WifiAwareCredentials.isInitiator("same-id", "same-id"))
    }

    @Test
    fun awareDiscoveryBeacon_encodeDecodeRoundTripAndMalformedRejection() {
        val uuid = ConvoyHash.stableSessionUuid("room-1")
        val memberTag = ConvoyHash.computeProposerTag(uuid, "r2")
        val rootTag = ConvoyHash.computeProposerTag(uuid, "r1")
        val beacon = AwareDiscoveryBeacon(
            rosterIdx = 1,
            flags = AwareDiscoveryBeacon.FLAG_HAS_GPS or AwareDiscoveryBeacon.FLAG_P2P_FALLBACK_READY,
            activeNdpCount = 2,
            maxNdpSessions = 4,
            memberTag = memberTag,
            componentRootTag = rootTag,
            latE6 = 18_520_123,
            lonE6 = 73_850_456,
            connectedMask = 0b000101,
            reachableMask = 0b011111
        )
        val encoded = beacon.encode()
        assertEquals(AwareDiscoveryBeacon.ENCODED_SIZE_BYTES, encoded.size)
        val decoded = AwareDiscoveryBeacon.decode(encoded)
        assertEquals(beacon, decoded)
        assertTrue(decoded!!.hasValidGps)
        assertTrue(decoded.isP2pFallbackReady)
        assertFalse(decoded.isOfflineVoiceActive)

        assertNull(AwareDiscoveryBeacon.decode(encoded.copyOf(31)))
        val badVer = encoded.copyOf().also { it[0] = 9 }
        assertNull(AwareDiscoveryBeacon.decode(badVer))
    }

    @Test
    fun awareSignalingMessages_allTypesRoundTripAndRejectMalformed() {
        val tag = ByteArray(8) { (it + 1).toByte() }
        val msgs = listOf(
            AwareSignalingMessage.ConnectReq(tag, senderRosterIdx = 2, udpPort = 45123, epoch = 7),
            AwareSignalingMessage.ConnectAck(tag, senderRosterIdx = 3, udpPort = 48900, epoch = 7),
            AwareSignalingMessage.ConnectReject(tag, reasonCode = AwareSignalingMessage.REASON_FULL),
            AwareSignalingMessage.LinkNudge(tag, senderRosterIdx = 4),
            AwareSignalingMessage.LinkTeardown(tag, reasonCode = AwareSignalingMessage.REASON_REPLACED)
        )
        for (m in msgs) {
            val bytes = m.encode()
            assertEquals(m, AwareSignalingMessage.decode(bytes))
        }
        assertNull(AwareSignalingMessage.decode(ByteArray(0)))
        assertNull(AwareSignalingMessage.decode(byteArrayOf(0x7F)))
        assertNull(AwareSignalingMessage.decode(msgs[0].encode().copyOf(5)))
    }

    @Test
    fun memberIdentityExtension0x83_roundTripsCleanlyAndPreservesConvoyIsolation() {
        val tag = ByteArray(8) { (it + 10).toByte() }
        val identity = com.ridervoice.mesh.topology.MeshControlExtension.MemberIdentity(
            rosterIdx = 3,
            memberTag = tag
        )
        val encoded = com.ridervoice.mesh.topology.MeshControlExtension.encodeMemberIdentity(identity)
        assertTrue(com.ridervoice.mesh.topology.MeshControlExtension.isTopologyControlPayload(encoded))
        assertEquals(identity, com.ridervoice.mesh.topology.MeshControlExtension.decodeMemberIdentity(encoded))
        assertEquals(
            com.ridervoice.mesh.convoy.ConvoyStateDecodeResult.UnsupportedVersion(
                com.ridervoice.mesh.topology.MeshControlExtension.DISCRIMINATOR_MEMBER_IDENTITY.toInt() and 0xFF
            ),
            com.ridervoice.mesh.convoy.ConvoyStateCodec.decode(encoded, 5)
        )
    }
}
