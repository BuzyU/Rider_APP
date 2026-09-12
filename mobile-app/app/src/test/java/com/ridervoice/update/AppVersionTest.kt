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
        assertEquals(0, vPrefixed.compareTo(nonPrefixed))
    }

    @Test
    fun testPreReleaseSuffixes() {
        val release = AppVersion.parse("1.3.4")
        val beta = AppVersion.parse("1.3.4-beta1")
        assertEquals(0, release.compareTo(beta))
    }
}
