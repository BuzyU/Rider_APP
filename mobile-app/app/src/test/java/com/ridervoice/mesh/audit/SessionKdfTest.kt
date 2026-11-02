package com.ridervoice.mesh.audit

import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionKdf
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T19 regression suite for D19: Session secret redesign & slow KDF.
 */
class SessionKdfTest {

    @Test
    fun sameCodeProducesIdenticalSessionIdAndKeys() {
        val code = "K7QX-M2PA-9TDE-4WZH"
        val derived1 = SessionKdf.derive(OfflineSession.normalize(code)!!, iterations = 100)
        val derived2 = SessionKdf.derive(OfflineSession.normalize(code)!!, iterations = 100)

        assertEquals(derived1.sessionId, derived2.sessionId)
        assertArrayEquals(derived1.authKey, derived2.authKey)
        assertArrayEquals(derived1.payloadKey, derived2.payloadKey)
    }

    @Test
    fun oneSymbolDifferenceProducesCompletelyDifferentSession() {
        val codeA = OfflineSession.normalize("K7QX-M2PA-9TDE-4WZH")!!
        val codeB = OfflineSession.normalize("K7QX-M2PA-9TDE-4WZJ")!! // H -> J

        val derivedA = SessionKdf.derive(codeA, iterations = 100)
        val derivedB = SessionKdf.derive(codeB, iterations = 100)

        assertNotEquals(derivedA.sessionId, derivedB.sessionId)
        assertFalse(derivedA.authKey.contentEquals(derivedB.authKey))
        assertFalse(derivedA.payloadKey.contentEquals(derivedB.payloadKey))
    }

    @Test
    fun normalizationHandlesCaseDashesSpacesAndConfusableCharacters() {
        // 'O' -> '0', 'I' -> '1', 'L' -> '1'
        val rawStandard = "K7QX-M2PA-9TDE-4WZH"
        val rawConfusable = "  k7qx m2pa 9tde 4wzh  "
        val rawWithOandI = "K7OX-M2PA-9TDE-4WZH" // 'O' at index 2
        val expectedWith0 = "K70X-M2PA-9TDE-4WZH"

        val normStandard = OfflineSession.normalize(rawStandard)
        val normConfusable = OfflineSession.normalize(rawConfusable)
        assertEquals(normStandard, normConfusable)

        val normO = OfflineSession.normalize(rawWithOandI)
        val norm0 = OfflineSession.normalize(expectedWith0)
        assertEquals(norm0, normO)

        val rawWithL = "L7QX-M2PA-9TDE-4WZH"
        val rawWith1 = "17QX-M2PA-9TDE-4WZH"
        assertEquals(OfflineSession.normalize(rawWith1), OfflineSession.normalize(rawWithL))
    }

    @Test
    fun oldOracleIsGone_sessionIdIsNotMd5OfCode() {
        val code = OfflineSession.normalize("K7QX-M2PA-9TDE-4WZH")!!
        val derived = SessionKdf.derive(code, iterations = 100)

        val oldMd5Id = UUID.nameUUIDFromBytes("rvmesh:session:$code".toByteArray(StandardCharsets.UTF_8)).toString()
        assertNotEquals("Session ID must not be the old MD5-based UUID oracle", oldMd5Id, derived.sessionId)
    }

    @Test
    fun sessionToStringNeverLeaksCodeOrKeyBytes() {
        val now = 1_000_000L
        val session = OfflineSession.create(now, iterations = 100)
        val repr = session.toString()

        assertTrue(repr.contains(session.sessionId))
        assertTrue(repr.contains(session.expiresAt.toString()))
        assertFalse("toString must not contain pairing code", repr.contains(session.pairingCode))
        assertFalse("toString must not contain authKey", repr.contains(session.authKey().toString()))
    }

    @Test
    fun wrongSessionKeyRejectsPacketEvenIfSessionIdMatches() {
        val now = 1_000_000L
        val sessionA = OfflineSession.create(now, iterations = 100)
        val sessionB = OfflineSession.create(now, iterations = 100)

        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = sessionA.sessionId, // Attacker copies session A's ID
            sourceId = UUID.randomUUID().toString(),
            sequence = 1,
            captureTimestamp = now,
            ttl = 5,
            hopCount = 0,
            previousHopId = UUID.randomUUID().toString(),
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = 1234,
            payload = ByteArray(640) { 1 }
        )

        // Encoded with session A's auth key
        val wire = MeshPacketCodec.encode(packet, sessionA.authKey())

        // Decoded with session B's auth key
        val decodeResult = MeshPacketCodec.decode(wire, sessionB.authKey())
        assertEquals(DecodeResult.Invalid(DecodeResult.Reason.BAD_TAG), decodeResult)
    }

    @Test
    fun fromOnlineRoomDerivesIdenticalSessionAcrossDevices() {
        val now = 1_000_000L
        val session1 = OfflineSession.fromOnlineRoom("convoy_abc_123", now, iterations = 100)
        val session2 = OfflineSession.fromOnlineRoom("convoy_abc_123", now, iterations = 100)
        assertNotNull(session1)
        assertNotNull(session2)
        assertEquals(session1!!.sessionId, session2!!.sessionId)
        assertEquals(session1.pairingCode, session2.pairingCode)
        assertArrayEquals(session1.authKey(), session2.authKey())
        assertArrayEquals(session1.payloadKey(), session2.payloadKey())

        val sessionOther = OfflineSession.fromOnlineRoom("convoy_xyz_999", now, iterations = 100)
        assertNotNull(sessionOther)
        assertNotEquals(session1.sessionId, sessionOther!!.sessionId)
        assertNotEquals(session1.pairingCode, sessionOther.pairingCode)
    }
}
