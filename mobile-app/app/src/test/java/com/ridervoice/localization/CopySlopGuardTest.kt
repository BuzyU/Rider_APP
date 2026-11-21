package com.ridervoice.localization

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WP-U3 Copy Cleanup Guard:
 * Enforces plain, rider-first terminology aligned with docs/UX_COPY.md and prevents
 * marketing taglines, tactical/military/radio roleplay jargon, and tactical symbol names
 * from remaining or being reintroduced in non-frozen UI code and string resources.
 */
class CopySlopGuardTest {

    private val bannedStringTerms = listOf(
        "stay connected",
        "ride united",
        "group voice intercom",
        "convoy lead",
        "callsign",
        "call-sign",
        "transceiver",
        "firmware",
        "ota transmission",
        "operator dossier",
        "tactical convoy",
        "tactical intercom",
        "emergency beacon",
        "integrity audit",
        "squad frequencies",
        "station profile"
    )

    private val bannedSymbolNames = listOf(
        "TacticalButton",
        "TacticalRadarCanvas"
    )

    private val frozenUiRelPaths = setOf(
        "ui/screens/room/RoomScreen.kt",
        "ui/screens/room/AudioRouteSheet.kt",
        "ui/screens/room/AudioVuMeter.kt",
        "ui/screens/room/DiagnosticsDialog.kt",
        "ui/screens/room/LeaveRideDialog.kt",
        "ui/screens/room/SosAlertBanner.kt"
    )

    private fun locateMainDir(): File {
        val candidates = listOf(
            File("src/main"),
            File("app/src/main"),
            File("mobile-app/app/src/main")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main directory")
    }

    @Test
    fun stringsXmlContainsNoMarketingOrTacticalJargon() {
        val stringsXml = File(locateMainDir(), "res/values/strings.xml")
        assertTrue("strings.xml must exist", stringsXml.exists())

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsXml)
        doc.documentElement.normalize()
        val nodes = doc.getElementsByTagName("string")

        val violations = mutableListOf<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val key = el.getAttribute("name")
            val value = el.textContent
            val lower = value.lowercase()
            for (term in bannedStringTerms) {
                if (lower.contains(term)) {
                    violations += "$key contains banned term '$term': \"$value\""
                }
            }
        }

        assertTrue(
            "Found banned marketing/tactical copy in res/values/strings.xml:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun nonFrozenUiHasNoTacticalSymbolsOrRemovedHeroEyebrows() {
        val mainDir = locateMainDir()
        val javaRoot = File(mainDir, "java/com/ridervoice")
        val uiDirs = listOf(
            File(javaRoot, "ui"),
            File(javaRoot, "mesh/ui")
        ).filter { it.isDirectory }

        val violations = mutableListOf<String>()
        for (dir in uiDirs) {
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val rel = file.relativeTo(javaRoot).path.replace('\\', '/')
                if (rel in frozenUiRelPaths) return@forEach
                val text = file.readText()
                for (symbol in bannedSymbolNames) {
                    if (text.contains(symbol)) {
                        violations += "$rel references banned tactical symbol '$symbol'"
                    }
                }
            }
        }

        val splash = File(javaRoot, "ui/screens/SplashScreen.kt").readText()
        if (splash.contains("splash_eyebrow_badge") || splash.contains("splash_legacy_tagline")) {
            violations += "SplashScreen.kt must not render marketing eyebrow badge or legacy tagline"
        }

        val login = File(javaRoot, "ui/screens/LoginScreen.kt").readText()
        if (login.contains("splash_eyebrow_badge") || login.contains("splash_legacy_tagline")) {
            violations += "LoginScreen.kt must not render GROUP VOICE INTERCOM eyebrow or legacy tagline"
        }

        val home = File(javaRoot, "ui/screens/HomeScreen.kt").readText()
        if (home.contains("home_hero_eyebrow")) {
            violations += "HomeScreen.kt must not render CONVOY LEAD hero eyebrow"
        }

        assertTrue(
            "Found banned tactical symbols or removed marketing eyebrows:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
