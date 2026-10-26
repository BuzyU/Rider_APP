package com.ridervoice.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class EmergencyNumbersTest {

    @Test
    fun usesLocalNumbersForKnownCountries() {
        assertEquals("911", EmergencyNumbers.numberForCountry("US"))
        assertEquals("999", EmergencyNumbers.numberForCountry("GB"))
        assertEquals("000", EmergencyNumbers.numberForCountry("AU"))
        assertEquals("119", EmergencyNumbers.numberForCountry("JP"))
        assertEquals("112", EmergencyNumbers.numberForCountry("IN"))
    }

    @Test
    fun isCaseAndWhitespaceInsensitive() {
        assertEquals("911", EmergencyNumbers.numberForCountry(" us "))
    }

    @Test
    fun fallsBackTo112WhenUnknown() {
        assertEquals("112", EmergencyNumbers.numberForCountry(null))
        assertEquals("112", EmergencyNumbers.numberForCountry(""))
        assertEquals("112", EmergencyNumbers.numberForCountry("ZZ"))
        assertEquals("112", EmergencyNumbers.numberForCountry("DE"))
        assertTrue(EmergencyNumbers.isFallback(null))
        assertTrue(EmergencyNumbers.isFallback(""))
        assertTrue(EmergencyNumbers.isFallback("ZZ"))
        assertFalse(EmergencyNumbers.isFallback("US"))
        assertFalse(EmergencyNumbers.isFallback("GB"))
    }

    @Test
    fun overridePrecedenceOverLocale() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            // Manual override for GB should take precedence over US locale
            val res = EmergencyNumbers.resolve(context = null, overrideIso = "GB")
            assertEquals("999", res.number)
            assertEquals("GB", res.countryIso)
            assertEquals("USER_OVERRIDE", res.source)
            assertFalse(res.isFallback)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun localeMappingWhenNoOverride() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val resUS = EmergencyNumbers.resolve(context = null, overrideIso = null)
            assertEquals("911", resUS.number)
            assertEquals("US", resUS.countryIso)
            assertEquals("DEVICE_LOCALE", resUS.source)
            assertFalse(resUS.isFallback)

            Locale.setDefault(Locale.UK)
            val resUK = EmergencyNumbers.resolve(context = null, overrideIso = null)
            assertEquals("999", resUK.number)
            assertEquals("GB", resUK.countryIso)
            assertEquals("DEVICE_LOCALE", resUK.source)
            assertFalse(resUK.isFallback)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun unknownRegionFallbackResolution() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            val res = EmergencyNumbers.resolve(context = null, overrideIso = null)
            assertEquals("112", res.number)
            assertTrue(res.isFallback)
            assertEquals("FALLBACK", res.source)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun telephonyManagerStrictlyForbiddenInSource() {
        // Prevent Play Protect toll fraud false-positive triggers
        val sourceCandidates = listOf(
            File("src/main/java/com/ridervoice/utils/EmergencyNumbers.kt"),
            File("app/src/main/java/com/ridervoice/utils/EmergencyNumbers.kt"),
            File("mobile-app/app/src/main/java/com/ridervoice/utils/EmergencyNumbers.kt")
        )
        val file = sourceCandidates.firstOrNull { it.exists() }
            ?: File(".").walkTopDown().firstOrNull { it.name == "EmergencyNumbers.kt" && it.path.contains("src${File.separator}main") }

        assertTrue("EmergencyNumbers.kt source file must exist", file != null && file.exists())
        val content = file!!.readText()
        assertFalse("EmergencyNumbers.kt must NOT reference TelephonyManager to prevent Play Protect toll fraud flags", content.contains("TelephonyManager"))
        assertFalse("EmergencyNumbers.kt must NOT import android.telephony.*", content.contains("android.telephony"))
    }
}
