package com.ridervoice.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringResourceParityTest {

    private data class ParsedStrings(
        val stringMap: Map<String, String>,
        val pluralMap: Map<String, List<String>>
    )

    private fun parseStringsXml(file: File): ParsedStrings {
        assertTrue("File ${file.absolutePath} must exist", file.exists())
        val dbf = DocumentBuilderFactory.newInstance()
        val doc = dbf.newDocumentBuilder().parse(file)
        doc.documentElement.normalize()

        val strings = mutableMapOf<String, String>()
        val stringNodes = doc.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            val name = element.getAttribute("name")
            val text = element.textContent
            strings[name] = text
        }

        val plurals = mutableMapOf<String, List<String>>()
        val pluralNodes = doc.getElementsByTagName("plurals")
        for (i in 0 until pluralNodes.length) {
            val element = pluralNodes.item(i) as Element
            val name = element.getAttribute("name")
            val items = mutableListOf<String>()
            val itemNodes = element.getElementsByTagName("item")
            for (j in 0 until itemNodes.length) {
                items.add(itemNodes.item(j).textContent)
            }
            plurals[name] = items
        }

        return ParsedStrings(strings, plurals)
    }

    private fun extractPlaceholders(text: String): List<String> {
        val regex = Regex("%(\\d+\\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]")
        return regex.findAll(text).map { it.value }.toList()
    }

    private fun locateResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("mobile-app/app/src/main/res")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find res directory in candidates: $candidates")
    }

    @Test
    fun testStringKeyParity_betweenLocales() {
        val resDir = locateResDir()
        val defaultXml = File(resDir, "values/strings.xml")
        val hiXml = File(resDir, "values-hi/strings.xml")
        val mrXml = File(resDir, "values-mr/strings.xml")

        val defaultData = parseStringsXml(defaultXml)
        val hiData = parseStringsXml(hiXml)
        val mrData = parseStringsXml(mrXml)

        // 1. Check all string keys match
        val defaultKeys = defaultData.stringMap.keys
        val hiKeys = hiData.stringMap.keys
        val mrKeys = mrData.stringMap.keys

        val missingInHi = defaultKeys - hiKeys
        val extraInHi = hiKeys - defaultKeys
        assertTrue("Missing in Hindi: $missingInHi; Extra in Hindi: $extraInHi", missingInHi.isEmpty() && extraInHi.isEmpty())

        val missingInMr = defaultKeys - mrKeys
        val extraInMr = mrKeys - defaultKeys
        assertTrue("Missing in Marathi: $missingInMr; Extra in Marathi: $extraInMr", missingInMr.isEmpty() && extraInMr.isEmpty())

        // 2. Check plural keys match
        assertEquals("Plurals in Hindi must match default", defaultData.pluralMap.keys, hiData.pluralMap.keys)
        assertEquals("Plurals in Marathi must match default", defaultData.pluralMap.keys, mrData.pluralMap.keys)

        // 3. Check placeholder signatures match
        for (key in defaultKeys) {
            val defPlaceholders = extractPlaceholders(defaultData.stringMap[key]!!)
            val hiPlaceholders = extractPlaceholders(hiData.stringMap[key]!!)
            val mrPlaceholders = extractPlaceholders(mrData.stringMap[key]!!)

            assertEquals("Placeholder mismatch for '$key' in Hindi", defPlaceholders.size, hiPlaceholders.size)
            assertEquals("Placeholder mismatch for '$key' in Marathi", defPlaceholders.size, mrPlaceholders.size)
        }
    }
}
