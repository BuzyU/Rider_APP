package com.ridervoice.utils

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Resolves the local emergency phone number for the rider's current country.
 *
 * Country detection order (most to least reliable while travelling):
 *  1. Network country (the country the phone is physically registered in, handles roaming)
 *  2. SIM country
 *  3. Device locale region
 *
 * If the country is unknown or not listed, [DEFAULT_NUMBER] (112) is used. 112 is the GSM
 * standard and is redirected to the local emergency service by mobile networks in most countries.
 */
object EmergencyNumbers {

    const val DEFAULT_NUMBER = "112"

    data class Info(val countryIso: String?, val number: String)

    /**
     * General emergency number (ambulance / road accident first) per ISO-3166 alpha-2 country.
     * Only countries that do NOT use 112 are listed; everything else falls back to 112
     * (EU members, India, Russia, Ukraine, Turkey, Indonesia, Nigeria, South Africa, etc.).
     */
    private val NUMBERS: Map<String, String> = mapOf(
        // North & Central America
        "US" to "911", "CA" to "911", "MX" to "911", "PR" to "911", "DO" to "911",
        "PA" to "911", "CR" to "911", "SV" to "911", "GT" to "911", "HN" to "911",
        "NI" to "118", "JM" to "119", "CU" to "106",
        // South America
        "AR" to "911", "CO" to "123", "EC" to "911", "UY" to "911", "VE" to "911",
        "BR" to "192", "CL" to "131", "PE" to "105", "BO" to "110", "PY" to "911",
        // UK & Oceania
        "GB" to "999", "GG" to "999", "JE" to "999", "IM" to "999", "GI" to "999",
        "AU" to "000", "NZ" to "111", "FJ" to "911",
        // South Asia
        "BD" to "999", "LK" to "119", "NP" to "102", "PK" to "1122", "BT" to "112",
        "MV" to "102", "AF" to "112",
        // East & South-East Asia
        "JP" to "119", "KR" to "119", "CN" to "120", "TW" to "119", "HK" to "999",
        "MO" to "999", "SG" to "995", "MY" to "999", "TH" to "1669", "PH" to "911",
        "VN" to "115", "KH" to "119", "LA" to "195", "MM" to "192", "BN" to "991",
        "MN" to "103",
        // Middle East
        "AE" to "998", "SA" to "997", "QA" to "999", "KW" to "112", "BH" to "999",
        "OM" to "9999", "JO" to "911", "LB" to "140", "IL" to "101", "IR" to "115",
        "IQ" to "122", "SY" to "110", "YE" to "191", "PS" to "101",
        // Africa
        "EG" to "123", "KE" to "999", "UG" to "999", "TZ" to "112", "ET" to "907",
        "GH" to "112", "MA" to "150", "DZ" to "14", "TN" to "190", "SN" to "15",
        "ZW" to "994", "ZM" to "991", "BW" to "997", "NA" to "211111", "MU" to "114",
        // Europe (non-112 or non-EU specifics)
        "CH" to "144", "NO" to "113", "BY" to "103", "MD" to "112", "RS" to "194",
        "BA" to "124", "AL" to "127", "MK" to "194", "ME" to "124", "XK" to "112",
        "GE" to "112", "AM" to "103", "AZ" to "103",
        // Central Asia
        "KZ" to "103", "UZ" to "103", "KG" to "103", "TJ" to "03", "TM" to "03"
    )

    fun numberForCountry(countryIso: String?): String {
        val iso = countryIso?.trim()?.uppercase(Locale.ROOT)
        if (iso.isNullOrEmpty()) return DEFAULT_NUMBER
        return NUMBERS[iso] ?: DEFAULT_NUMBER
    }

    fun resolve(context: Context): Info {
        val iso = detectCountryIso(context)
        return Info(iso, numberForCountry(iso))
    }

    private fun detectCountryIso(context: Context): String? {
        val tm = runCatching {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull()

        val network = runCatching { tm?.networkCountryIso }.getOrNull()
        if (!network.isNullOrBlank()) return network.uppercase(Locale.ROOT)

        val sim = runCatching { tm?.simCountryIso }.getOrNull()
        if (!sim.isNullOrBlank()) return sim.uppercase(Locale.ROOT)

        val locale = Locale.getDefault().country
        return locale.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
    }
}
