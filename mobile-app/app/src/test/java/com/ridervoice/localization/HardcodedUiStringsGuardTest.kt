package com.ridervoice.localization

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP-U2: Guard test scanning ui/ and mesh/ui/ for hardcoded user-facing string literals.
 * Fails whenever a new hardcoded user-facing literal is introduced outside the documented
 * allowlist at src/test/resources/hardcoded-strings-allowlist.txt.
 */
class HardcodedUiStringsGuardTest {

    private fun locateAppDir(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            if (File(dir, "src/main/java/com/ridervoice/ui").exists()) return dir
            if (File(dir, "mobile-app/app/src/main/java/com/ridervoice/ui").exists()) {
                return File(dir, "mobile-app/app")
            }
            dir = dir.parentFile ?: break
        }
        throw IllegalStateException("Could not locate app module dir")
    }

    private fun loadAllowlist(appDir: File): Pair<Set<String>, Set<Pair<String, String>>> {
        val file = File(appDir, "src/test/resources/hardcoded-strings-allowlist.txt")
        assertTrue("Allowlist file must exist at ${file.absolutePath}", file.exists())
        val wildcardFiles = mutableSetOf<String>()
        val exactEntries = mutableSetOf<Pair<String, String>>()
        for (raw in file.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val spec = line.substringBefore("|").trim()
            val colon = spec.indexOf(':')
            assertTrue("Malformed allowlist entry (expected file:literal | reason): $line", colon > 0)
            val reason = line.substringAfter("|", "").trim()
            assertTrue("Allowlist entry must include a reason after '|': $line", reason.isNotEmpty())
            val relPath = spec.substring(0, colon).trim()
            val literal = spec.substring(colon + 1).trim()
            if (literal == "*") {
                wildcardFiles.add(relPath)
            } else {
                exactEntries.add(relPath to literal)
            }
        }
        return wildcardFiles to exactEntries
    }

    @Test
    fun noNewHardcodedUserFacingStringsInUiAndMeshUi() {
        val appDir = locateAppDir()
        val (wildcardFiles, exactEntries) = loadAllowlist(appDir)
        val baseDir = File(appDir, "src/main/java/com/ridervoice")
        val roots = listOf(File(baseDir, "ui"), File(baseDir, "mesh/ui"))

        val pattern = Regex(
            """(?:\btext\s*=|\btitle\s*=|\bsubtitle\s*=|\blabel\s*=|\bcontentDescription\s*=|\bconfirmLabel\s*=|\bdismissLabel\s*=|\bactionText\s*=|\bText\(|makeText\([^,]+,\s*|showSnackbar\()\s*"([^"]+)""""
        )

        val violations = mutableListOf<String>()
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { !it.path.replace('\\', '/').contains("/viewmodels/") }
                .sortedBy { it.path }
                .forEach { file ->
                    val rel = file.relativeTo(baseDir).path.replace('\\', '/')
                    if (rel in wildcardFiles) return@forEach
                    file.readLines().forEachIndexed { idx, rawLine ->
                        val trimmed = rawLine.trim()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                        pattern.findAll(rawLine).forEach { m ->
                            val literal = m.groupValues[1]
                            if (literal.isBlank()) return@forEach
                            if ((rel to literal) !in exactEntries) {
                                violations.add("$rel:${idx + 1}: \"$literal\"")
                            }
                        }
                    }
                }
        }

        assertTrue(
            "Found ${violations.size} unallowlisted hardcoded user-facing UI string literal(s):\n" +
                violations.take(40).joinToString("\n") +
                if (violations.size > 40) "\n... and ${violations.size - 40} more" else "",
            violations.isEmpty()
        )
    }
}
