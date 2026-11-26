package com.ridervoice.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Returns the active UI [Locale] from Compose [LocalConfiguration], falling back cleanly to
 * [Locale.getDefault] when no locale list entries are configured.
 */
@Composable
fun rememberAppLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        val locales = configuration.locales
        if (!locales.isEmpty) locales[0] ?: Locale.getDefault() else Locale.getDefault()
    }
}

/**
 * Centralized, cached, locale-aware date, time, and numeric formatters backed by `java.time`.
 * Formatters are cached by `(pattern/style, locale)` so `LazyColumn` item composition performs
 * zero `SimpleDateFormat` or formatter allocations per row.
 */
object RvDateFormat {
    private val patternCache = ConcurrentHashMap<String, DateTimeFormatter>()
    private val timeStyleCache = ConcurrentHashMap<String, DateTimeFormatter>()

    private fun patternFormatter(pattern: String, locale: Locale): DateTimeFormatter {
        val key = "$pattern|${locale.toLanguageTag()}"
        return patternCache.getOrPut(key) {
            DateTimeFormatter.ofPattern(pattern, locale)
        }
    }

    private fun shortTimeFormatter(locale: Locale): DateTimeFormatter {
        val key = "SHORT_TIME|${locale.toLanguageTag()}"
        return timeStyleCache.getOrPut(key) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        }
    }

    /** Formats a stable locale-independent year-month bucket key for filtering (e.g. `2026-11`). */
    fun monthKey(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): YearMonth =
        YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

    /**
     * Formats `"MMMM yyyy"` in [locale]. Uppercases only for English (`"en"`), preserving proper
     * script casing and vowel signs in Hindi, Marathi, and other locales.
     */
    fun formatMonthYear(
        epochMillis: Long,
        locale: Locale,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        val formatted = patternFormatter("MMMM yyyy", locale).format(zoned)
        return if (locale.language.equals("en", ignoreCase = true)) {
            formatted.uppercase(locale)
        } else {
            formatted
        }
    }

    /** Formats a compact localized ride timestamp (e.g. `"Thu, 26 Nov · 10:30 AM"`). */
    fun formatShortDateTime(
        epochMillis: Long,
        locale: Locale,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        return patternFormatter("EEE, d MMM · hh:mm a", locale).format(zoned)
    }

    /** Formats a full localized ride timestamp with year (e.g. `"Thu, 26 Nov 2026 · 10:30 AM"`). */
    fun formatFullDateTime(
        epochMillis: Long,
        locale: Locale,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        return patternFormatter("EEE, d MMM yyyy · hh:mm a", locale).format(zoned)
    }

    /** Formats only the time of day using [FormatStyle.SHORT] in [locale]. */
    fun formatTimeOnly(
        epochMillis: Long,
        locale: Locale,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        return shortTimeFormatter(locale).format(zoned)
    }

    /** Formats a numeric metric with fixed [decimals] respecting [locale] decimal symbols. */
    fun formatDecimal(
        value: Number,
        decimals: Int,
        locale: Locale = Locale.getDefault()
    ): String {
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            isGroupingUsed = false
        }
        return nf.format(value.toDouble())
    }

    /** Formats an `HH:mm:ss` elapsed duration using [locale]. */
    fun formatDurationHms(
        hours: Long,
        mins: Long,
        secs: Long,
        locale: Locale = Locale.getDefault()
    ): String = String.format(locale, "%02d:%02d:%02d", hours, mins, secs)

    /** Parses an ISO-8601 instant or offset timestamp to epoch milliseconds using `java.time`. */
    fun parseIsoInstantEpochMillis(isoString: String): Long? = try {
        Instant.parse(isoString).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(isoString).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
