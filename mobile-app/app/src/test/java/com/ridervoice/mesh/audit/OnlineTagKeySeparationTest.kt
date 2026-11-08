package com.ridervoice.mesh.audit

import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class OnlineTagKeySeparationTest {

    @Before
    fun setUp() {
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = true
            override val warmStandbyEnabled = true
            override val settingsFlow = MutableStateFlow(MeshSettingsSnapshot(true, true, true))
        })
    }

    @After
    fun tearDown() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun tagDiffersFromPlainHmac() {
        val payloadKey = ByteArray(32) { it.toByte() }
        val id = "user-alice-123"

        val tag = PayloadCrypto.computeOnlineIdTag(payloadKey, id)

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(payloadKey, "HmacSHA256"))
        }
        val plainBytes = mac.doFinal(id.toByteArray(Charsets.UTF_8)).copyOf(8)
        val plainHmacHex = PayloadCrypto.bytesToHex(plainBytes)

        assertNotEquals("Online ID tag must use HKDF-derived subkey and differ from plain HMAC(payloadKey, id)", plainHmacHex, tag)
    }

    @Test
    fun tagChangesIfInfoLabelChanges() {
        val payloadKey = ByteArray(32) { (it + 5).toByte() }
        val id = "user-bob-456"

        val defaultTag = PayloadCrypto.computeOnlineIdTag(payloadKey, id)

        // Derive with info = "other" instead of "tag"
        val hmacFunc = { salt: ByteArray, data: ByteArray ->
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(data)
        }
        val prk = hmacFunc("rvmesh/v3/online-id".toByteArray(Charsets.UTF_8), payloadKey)
        val altKey = hmacFunc(prk, "other".toByteArray(Charsets.UTF_8) + byteArrayOf(1)).copyOf(32)
        val altTagBytes = hmacFunc(altKey, id.toByteArray(Charsets.UTF_8)).copyOf(8)
        val altTag = PayloadCrypto.bytesToHex(altTagBytes)

        assertNotEquals("Tag must change when HKDF info label changes", altTag, defaultTag)
    }

    @Test
    fun tagIsAlways16HexCharacters() {
        val payloadKey = ByteArray(32) { 0xFF.toByte() }
        val tag = PayloadCrypto.computeOnlineIdTag(payloadKey, "charlie")

        assertEquals(16, tag.length)
        assertTrue("Tag must only contain lowercase hex characters", tag.all { it in "0123456789abcdef" })
    }

    @Test
    fun lateAssignedSupplier_isHonoured() = runBlocking {
        val session = OfflineSession.create(10_000L)
        val fakeLink = mockk<MeshLink>(relaxed = true)

        val identity = MeshIdentity("00000000-0000-0000-0000-000000000001", "Alice")
        val transport = OfflineMeshVoiceTransport(
            audioOwnershipArbiter = mockk(relaxed = true),
            linkFactory = { fakeLink },
            identityProvider = { identity },
            clock = { 1000L }
        )

        // Initial supplier is null
        transport.onlineIdentitySupplier = { null }
        transport.start(VoiceSession.Offline(session))

        // Late-assign supplier
        transport.onlineIdentitySupplier = { "late-online-rider" }

        val fEngine = transport.lastForwardingEngineForTest
        assertNotNull("Forwarding engine must be initialized", fEngine)

        val helloPacket = fEngine!!.originateHello()
        assertNotNull("HELLO packet must be generated", helloPacket)

        val plainHello = com.ridervoice.mesh.protocol.PayloadCrypto.open(
            key = PayloadCrypto.sourceKey(session.payloadKey(), identity.deviceId),
            nonce = PayloadCrypto.nonce(helloPacket!!.epoch, helloPacket.sequence, helloPacket.type.code),
            aad = PayloadCrypto.aad(
                sessionId = session.sessionId,
                sourceId = identity.deviceId,
                typeCode = helloPacket.type.code,
                epoch = helloPacket.epoch,
                sequence = helloPacket.sequence,
                captureTimestamp = 1000L,
                audioFormat = com.ridervoice.mesh.protocol.AudioFormatId.PCM16_16K_MONO
            ),
            sealed = helloPacket.payload
        )
        assertNotNull("Decryption must succeed", plainHello)

        val hello = HelloCodec.decode(plainHello!!)
        assertNotNull("HELLO payload must decode", hello)

        val expectedTag = PayloadCrypto.computeOnlineIdTag(session.payloadKey(), "late-online-rider")
        assertEquals("Late-assigned onlineIdentitySupplier must be honoured in originateHello", expectedTag, hello!!.onlineIdTag)

        transport.stop()
        transport.close()
    }
}

