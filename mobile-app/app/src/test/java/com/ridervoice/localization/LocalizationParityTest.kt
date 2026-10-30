package com.ridervoice.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationParityTest {

    private fun extractStringKeys(file: File): Set<String> {
        assertTrue("Resource file must exist: ${file.absolutePath}", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        val keys = mutableSetOf<String>()
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i) as Element
            val name = node.getAttribute("name")
            if (name.isNotBlank()) {
                keys.add(name)
            }
        }
        return keys
    }

    @Test
    fun testStringParityAcrossLocales() {
        // Locate res directories from project root
        val userDir = System.getProperty("user.dir") ?: "."
        val baseRes = File(userDir, "src/main/res")
        val defaultStrings = File(baseRes, "values/strings.xml")
        val hiStrings = File(baseRes, "values-hi/strings.xml")
        val mrStrings = File(baseRes, "values-mr/strings.xml")

        val enKeys = extractStringKeys(defaultStrings)
        val hiKeys = extractStringKeys(hiStrings)
        val mrKeys = extractStringKeys(mrStrings)

        assertTrue("Default strings must not be empty", enKeys.isNotEmpty())

        val missingInHi = enKeys - hiKeys
        val extraInHi = hiKeys - enKeys
        assertEquals("Missing keys in values-hi: $missingInHi", emptySet<String>(), missingInHi)
        assertEquals("Extra keys in values-hi: $extraInHi", emptySet<String>(), extraInHi)

        val missingInMr = enKeys - mrKeys
        val extraInMr = mrKeys - enKeys
        assertEquals("Missing keys in values-mr: $missingInMr", emptySet<String>(), missingInMr)
        assertEquals("Extra keys in values-mr: $extraInMr", emptySet<String>(), extraInMr)
    }
}
