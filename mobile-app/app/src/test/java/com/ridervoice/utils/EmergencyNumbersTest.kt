package com.ridervoice.utils

import org.junit.Assert.assertEquals
import org.junit.Test

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
    }
}
