package com.ridervoice.mesh.audit

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.session.SessionKdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies that protocol documentation and constants do not drift from Kotlin source code truth.
 */
class ProtocolConstantsDocSyncTest {

    @Test
    fun pbkdf2IterationsConstant_is120k() {
        assertEquals(120_000, SessionKdf.DEFAULT_ITERATIONS)
    }

    @Test
    fun dupCacheMaxEntries_is60k() {
        assertEquals(60_000, MeshConfig.DUP_CACHE_MAX_ENTRIES)
    }

    @Test
    fun ttlConstants_are5() {
        assertEquals(5, MeshConfig.DEFAULT_TTL)
        assertEquals(5, MeshConfig.MAX_HOPS)
    }

    @Test
    fun protocolDocumentation_matchesPbkdf2Iterations() {
        // Resolve project root docs directory across different test execution working directories
        val candidates = listOf(
            File("../../docs"),
            File("../docs"),
            File("docs"),
            File("../../docs/protocol/mesh-v2.md").parentFile?.parentFile
        )
        val docsDir = candidates.firstOrNull { it?.exists() == true && File(it, "protocol/mesh-v2.md").exists() }
            ?: throw IllegalStateException("Could not locate docs directory. Checked: ${candidates.map { it?.absolutePath }}")

        val meshV2Doc = File(docsDir, "protocol/mesh-v2.md").readText()
        assertFalse("mesh-v2.md must not reference obsolete 100,000 iterations", meshV2Doc.contains("100,000"))
        assertFalse("mesh-v2.md must not reference obsolete 100_000 iterations", meshV2Doc.contains("100_000"))
        assertTrue("mesh-v2.md must reference 120,000 iterations", meshV2Doc.contains("120,000") || meshV2Doc.contains("120_000"))
        assertTrue(
            "mesh-v2.md must document protocol version ${MeshPacketCodec.VERSION}",
            meshV2Doc.contains("0x0${MeshPacketCodec.VERSION}") ||
                meshV2Doc.contains("Version: ${MeshPacketCodec.VERSION}.0.0") ||
                meshV2Doc.contains("Protocol version (`0x0${MeshPacketCodec.VERSION}`)")
        )
        assertTrue(
            "mesh-v2.md must document header length ${MeshPacketCodec.HEADER_LEN} bytes",
            meshV2Doc.contains("${MeshPacketCodec.HEADER_LEN} Bytes Total") ||
                meshV2Doc.contains("${MeshPacketCodec.HEADER_LEN} (Header)")
        )

        val threatModelDoc = File(docsDir, "security/mesh-threat-model.md").readText()
        assertFalse("mesh-threat-model.md must not reference obsolete 100,000 iterations", threatModelDoc.contains("100,000"))
        assertTrue("mesh-threat-model.md must reference 120,000 iterations", threatModelDoc.contains("120,000"))

        val decisionsDoc = File(docsDir, "implementation/mesh/DECISIONS.md").readText()
        assertTrue("DECISIONS.md must document 120,000 iterations", decisionsDoc.contains("120,000"))
    }
}

