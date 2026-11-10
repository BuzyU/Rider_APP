package com.ridervoice.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class SourceKeyRejectsNonUuidTest {

    @Test(expected = IllegalArgumentException::class)
    fun nonUuidSourceId_throwsIllegalArgumentException() {
        val payloadKey = ByteArray(32) { 0x42 }
        PayloadCrypto.sourceKey(payloadKey, "not-a-valid-uuid")
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySourceId_throwsIllegalArgumentException() {
        val payloadKey = ByteArray(32) { 0x42 }
        PayloadCrypto.sourceKey(payloadKey, "")
    }

    @Test
    fun validUuidSourceId_derivesSourceKeySuccessfully() {
        val payloadKey = ByteArray(32) { 0x42 }
        val validUuid = UUID.randomUUID().toString()
        val key = PayloadCrypto.sourceKey(payloadKey, validUuid)
        assertEquals(32, key.size)
    }
}

