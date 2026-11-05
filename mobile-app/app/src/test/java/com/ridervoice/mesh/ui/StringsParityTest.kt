package com.ridervoice.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringsParityTest {

    private fun findProjectRoot(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            if (File(dir, "src/main/res/values/strings.xml").exists()) {
                return dir
            }
            if (File(dir, "mobile-app/app/src/main/res/values/strings.xml").exists()) {
                return File(dir, "mobile-app/app")
            }
            val parent = dir.parentFile ?: break
            dir = parent
        }
        throw IllegalStateException("Could not find project directory with res/values/strings.xml")
    }

    private fun extractMeshKeys(file: File): Set<String> {
        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = db.parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        val keys = mutableSetOf<String>()
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i) as Element
            val name = node.getAttribute("name")
            if (name.startsWith("mesh_")) {
                keys.add(name)
            }
        }
        return keys
    }

    @Test
    fun meshStringKeysHaveExactParityAcrossEnglishHindiAndMarathi() {
        val root = findProjectRoot()
        val enFile = File(root, "src/main/res/values/strings.xml")
        val hiFile = File(root, "src/main/res/values-hi/strings.xml")
        val mrFile = File(root, "src/main/res/values-mr/strings.xml")

        assertTrue("English strings file exists", enFile.exists())
        assertTrue("Hindi strings file exists", hiFile.exists())
        assertTrue("Marathi strings file exists", mrFile.exists())

        val enKeys = extractMeshKeys(enFile)
        val hiKeys = extractMeshKeys(hiFile)
        val mrKeys = extractMeshKeys(mrFile)

        assertTrue("Should have non-empty mesh keys", enKeys.isNotEmpty())

        val missingInHi = enKeys - hiKeys
        val missingInMr = enKeys - mrKeys
        val extraInHi = hiKeys - enKeys
        val extraInMr = mrKeys - enKeys

        assertEquals("Missing mesh keys in values-hi: $missingInHi", emptySet<String>(), missingInHi)
        assertEquals("Missing mesh keys in values-mr: $missingInMr", emptySet<String>(), missingInMr)
        assertEquals("Extra mesh keys in values-hi: $extraInHi", emptySet<String>(), extraInHi)
        assertEquals("Extra mesh keys in values-mr: $extraInMr", emptySet<String>(), extraInMr)
        assertEquals(enKeys.size, hiKeys.size)
        assertEquals(enKeys.size, mrKeys.size)
    }

    @Test
    fun allRequiredMeshSettingsKeysPresentAcrossLocales() {
        val root = findProjectRoot()
        val enFile = File(root, "src/main/res/values/strings.xml")
        val hiFile = File(root, "src/main/res/values-hi/strings.xml")
        val mrFile = File(root, "src/main/res/values-mr/strings.xml")

        val enKeys = extractMeshKeys(enFile)
        val hiKeys = extractMeshKeys(hiFile)
        val mrKeys = extractMeshKeys(mrFile)

        val requiredKeys = setOf(
            "mesh_settings_section_header",
            "mesh_settings_disabled",
            "mesh_settings_open_lobby",
            "mesh_settings_pair_backup_code",
            "mesh_settings_pair_code_action",
            "mesh_backup_not_ready",
            "mesh_fallback_reason_no_backup_code",
            "mesh_fallback_reason_no_riders_nearby",
            "mesh_fallback_reason_handoff_failed"
        )

        for (key in requiredKeys) {
            assertTrue("Key $key missing from English strings", enKeys.contains(key))
            assertTrue("Key $key missing from Hindi strings", hiKeys.contains(key))
            assertTrue("Key $key missing from Marathi strings", mrKeys.contains(key))
        }
    }

    @Test
    fun noHexColorLiteralsInMeshUiOutsideMeshColors() {
        val root = findProjectRoot()
        val uiDir = File(root, "src/main/java/com/ridervoice/mesh/ui")
        assertTrue("UI dir must exist", uiDir.exists() && uiDir.isDirectory)

        val offendingLines = mutableListOf<String>()
        uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (file.name != "MeshColors.kt") {
                file.readLines().forEachIndexed { index, line ->
                    if (line.contains("Color(0x", ignoreCase = true)) {
                        offendingLines.add("${file.name}:${index + 1}: $line")
                    }
                }
            }
        }

        assertTrue("Found raw Color(0x... literals outside MeshColors.kt:\n${offendingLines.joinToString("\n")}", offendingLines.isEmpty())
    }
}
