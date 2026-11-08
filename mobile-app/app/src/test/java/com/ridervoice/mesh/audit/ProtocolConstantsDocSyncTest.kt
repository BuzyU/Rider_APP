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

    private fun findRepoRoot(): File {
        val candidates = listOf(
            File("../../"),
            File("../"),
            File("."),
            File("../..")
        )
        return candidates.map { it.canonicalFile }.firstOrNull { File(it, "docs").isDirectory && File(it, "README.md").isFile }
            ?: throw IllegalStateException("Could not locate repository root. Checked: ${candidates.map { it.absolutePath }}")
    }

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
        val root = findRepoRoot()
        val docsDir = File(root, "docs")

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

    @Test
    fun documentationTruthAndForbiddenTerms() {
        val root = findRepoRoot()
        val filesToScan = mutableListOf<File>()

        val readme = File(root, "README.md")
        if (readme.exists()) filesToScan.add(readme)

        val codeDepth = File(root, "CODE_DEPTH_STRUCTURE.md")
        if (codeDepth.exists()) filesToScan.add(codeDepth)

        val docsDir = File(root, "docs")
        docsDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .filter { it.name != "RELEASE_NOTES.md" }
            .forEach { filesToScan.add(it) }

        val forbiddenPhrases = listOf(
            "100,000",
            "sliding-window replay",
            "sliding-window deduplication",
            "Relays never decrypt",
            "verify-test-refs",
            "DEFECT_REGISTER",
            "AtomicReference<RoutingSnapshot>",
            "OWNER_FLAGS",
            "protocol version (0x02)"
        )

        for (file in filesToScan) {
            val content = file.readText()
            for (phrase in forbiddenPhrases) {
                assertFalse(
                    "File ${file.relativeTo(root)} must not contain forbidden phrase '$phrase'",
                    content.contains(phrase)
                )
            }
        }

        // Header version check
        val expectedVersion = "${MeshPacketCodec.VERSION}.0.0"
        val threatModel = File(docsDir, "security/mesh-threat-model.md").readText()
        assertTrue(
            "mesh-threat-model.md version header must equal $expectedVersion",
            threatModel.contains("**Version:** $expectedVersion")
        )

        val testPlan = File(docsDir, "testing/mesh-test-plan.md").readText()
        assertTrue(
            "mesh-test-plan.md version header must equal $expectedVersion",
            testPlan.contains("**Version:** $expectedVersion")
        )
    }
}
