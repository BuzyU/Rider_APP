package com.ridervoice.update

import org.junit.Assert.*
import org.junit.Test

class AppVersionTest {

    @Test
    fun testSameVersionNotNewer() {
        val installed = AppVersion.parse("1.0.0")
        val latest = AppVersion.parse("1.0.0")
        assertEquals(0, installed.compareTo(latest))
        assertFalse(latest.isNewerThan(installed))
    }

    @Test
    fun testUpdateAvailableSimple() {
        val installed = AppVersion.parse("1.0.0")
        val latest = AppVersion.parse("1.1.0")
        assertTrue(latest.isNewerThan(installed))
        assertFalse(installed.isNewerThan(latest))
    }

    @Test
    fun testSemanticOrderingTenVersusNine() {

        val installed = AppVersion.parse("1.9.0")
        val latest = AppVersion.parse("1.10.0")
        assertTrue("1.10.0 must be newer than 1.9.0", latest.isNewerThan(installed))
        assertFalse(installed.isNewerThan(latest))
    }

    @Test
    fun testMultiComponentVersions() {
        val base = AppVersion.parse("0.1.3")
        val patch4 = AppVersion.parse("0.1.3.4")
        assertTrue("0.1.3.4 must be newer than 0.1.3", patch4.isNewerThan(base))
        assertFalse(base.isNewerThan(patch4))
    }

    @Test
    fun testMixedLengthComponents() {
        val shortVer = AppVersion.parse("1.3")
        val longVer = AppVersion.parse("1.3.4")
        assertTrue("1.3.4 must be newer than 1.3", longVer.isNewerThan(shortVer))
    }

    @Test
    fun testMajorVersionJump() {
        val v1 = AppVersion.parse("1.3.4")
        val v2 = AppVersion.parse("2.0.0")
        assertTrue(v2.isNewerThan(v1))
    }

    @Test
    fun testPrefixVHandling() {
        val vPrefixed = AppVersion.parse("v1.3.4")
        val nonPrefixed = AppVersion.parse("1.3.4")
        val vDotPrefixed = AppVersion.parse("V.1.3.4")
        assertEquals(0, vPrefixed.compareTo(nonPrefixed))
        assertEquals(0, vDotPrefixed.compareTo(nonPrefixed))
    }

    @Test
    fun testPreReleaseSuffixes() {
        val release = AppVersion.parse("1.3.4")
        val beta = AppVersion.parse("1.3.4-beta1")
        assertEquals(0, release.compareTo(beta))
    }

    @Test
    fun testV0033Bump() {
        val prevVersion = AppVersion.parse("0.0.3.2")
        val newVersion = AppVersion.parse("V0.0.3.3")
        assertTrue("V0.0.3.3 must be recognized as newer than 0.0.3.2", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.3").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.3").compareTo(newVersion))
    }

    @Test
    fun testV0034Bump() {
        val prevVersion = AppVersion.parse("0.0.3.3")
        val newVersion = AppVersion.parse("V0.0.3.4")
        assertTrue("V0.0.3.4 must be recognized as newer than 0.0.3.3", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.4").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.4").compareTo(newVersion))
    }

    @Test
    fun testV0035Bump() {
        val prevVersion = AppVersion.parse("0.0.3.4")
        val newVersion = AppVersion.parse("V0.0.3.5")
        assertTrue("V0.0.3.5 must be recognized as newer than 0.0.3.4", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.5").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.5").compareTo(newVersion))
    }

    @Test
    fun testV0036Bump() {
        val prevVersion = AppVersion.parse("0.0.3.5")
        val newVersion = AppVersion.parse("V0.0.3.6")
        assertTrue("V0.0.3.6 must be recognized as newer than 0.0.3.5", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.6").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.6").compareTo(newVersion))
    }

    @Test
    fun testV00311Bump() {
        val prevVersion = AppVersion.parse("0.0.3.10")
        val newVersion = AppVersion.parse("V0.0.3.11")
        assertTrue("V0.0.3.11 must be recognized as newer than 0.0.3.10", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.11").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.11").compareTo(newVersion))
    }

    @Test
    fun testV00312Bump() {
        val prevVersion = AppVersion.parse("0.0.3.11")
        val newVersion = AppVersion.parse("V0.0.3.12")
        assertTrue("V0.0.3.12 must be recognized as newer than 0.0.3.11", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.12").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.12").compareTo(newVersion))
    }

    @Test
    fun testV00313Bump() {
        val prevVersion = AppVersion.parse("0.0.3.12")
        val newVersion = AppVersion.parse("V0.0.3.13")
        assertTrue("V0.0.3.13 must be recognized as newer than 0.0.3.12", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.13").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.13").compareTo(newVersion))
    }

    @Test
    fun testV00314Bump() {
        val prevVersion = AppVersion.parse("0.0.3.13")
        val newVersion = AppVersion.parse("V0.0.3.14")
        assertTrue("V0.0.3.14 must be recognized as newer than 0.0.3.13", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.14").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.14").compareTo(newVersion))
    }

    @Test
    fun testV00315Bump() {
        val prevVersion = AppVersion.parse("0.0.3.14")
        val newVersion = AppVersion.parse("V0.0.3.15")
        assertTrue("V0.0.3.15 must be recognized as newer than 0.0.3.14", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.15").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.15").compareTo(newVersion))
    }

    @Test
    fun testV00316Bump() {
        val prevVersion = AppVersion.parse("0.0.3.15")
        val newVersion = AppVersion.parse("V0.0.3.16")
        assertTrue("V0.0.3.16 must be recognized as newer than 0.0.3.15", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.16").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.16").compareTo(newVersion))
    }

    @Test
    fun testV00317Bump() {
        val prevVersion = AppVersion.parse("0.0.3.16")
        val newVersion = AppVersion.parse("V0.0.3.17")
        assertTrue("V0.0.3.17 must be recognized as newer than 0.0.3.16", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.17").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.17").compareTo(newVersion))
    }

    @Test
    fun testV00318Bump() {
        val prevVersion = AppVersion.parse("0.0.3.17")
        val newVersion = AppVersion.parse("V0.0.3.18")
        assertTrue("V0.0.3.18 must be recognized as newer than 0.0.3.17", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.18").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.18").compareTo(newVersion))
    }

    @Test
    fun testV00319Bump() {
        val prevVersion = AppVersion.parse("0.0.3.18")
        val newVersion = AppVersion.parse("V0.0.3.19")
        assertTrue("V0.0.3.19 must be recognized as newer than 0.0.3.18", newVersion.isNewerThan(prevVersion))
        assertEquals(0, AppVersion.parse("v0.0.3.19").compareTo(newVersion))
        assertEquals(0, AppVersion.parse("0.0.3.19").compareTo(newVersion))
    }

    @Test
    fun testV000315Normalization() {
        val normalized = AppVersion.parse("V0.0.0.3.15")
        val standard = AppVersion.parse("v0.0.3.15")
        val prevVersion = AppVersion.parse("0.0.3.14")
        assertEquals(0, normalized.compareTo(standard))
        assertTrue("V0.0.0.3.15 normalized must be recognized as newer than 0.0.3.14", normalized.isNewerThan(prevVersion))
    }
}
