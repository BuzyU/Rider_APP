package com.ridervoice.ui.theme

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RvDateFormatTest {

    // Fixed instant: 2026-11-26T05:00:00Z -> 2026-11-26 10:30:00 AM IST
    private val fixedEpochMs = 1795669200_000L
    private val istZone = ZoneId.of("Asia/Kolkata")

    private fun resolveSourceRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/java/com/ridervoice"),
            File(cwd, "app/src/main/java/com/ridervoice"),
            File(cwd, "mobile-app/app/src/main/java/com/ridervoice")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Unable to locate com/ridervoice source root from $cwd")
    }

    @Test
    fun formatMonthYear_uppercasesOnlyEnglish_andLocalizesHindiAndMarathi() {
        val en = RvDateFormat.formatMonthYear(fixedEpochMs, Locale.ENGLISH, istZone)
        val hi = RvDateFormat.formatMonthYear(fixedEpochMs, Locale("hi", "IN"), istZone)
        val mr = RvDateFormat.formatMonthYear(fixedEpochMs, Locale("mr", "IN"), istZone)

        assertTrue("English month header should contain uppercase year/month, got: $en", en == en.uppercase(Locale.ENGLISH))
        assertTrue("English month header should include 2026, got: $en", en.contains("2026"))
        assertTrue("Hindi month header should be non-blank and differ from English uppercase: $hi", hi.isNotBlank() && hi != en)
        assertTrue("Marathi month header should be non-blank and differ from English uppercase: $mr", mr.isNotBlank() && mr != en)
        assertFalse("Hindi month header must not have Latin month text", hi.contains("NOVEMBER", ignoreCase = true))
        assertFalse("Marathi month header must not have Latin month text", mr.contains("NOVEMBER", ignoreCase = true))
    }

    @Test
    fun formatDateTimeAndTimeOnly_produceLocalizedNonEmptyOutputAcrossEnHiMr() {
        for (locale in listOf(Locale.ENGLISH, Locale("hi", "IN"), Locale("mr", "IN"))) {
            val shortDt = RvDateFormat.formatShortDateTime(fixedEpochMs, locale, istZone)
            val fullDt = RvDateFormat.formatFullDateTime(fixedEpochMs, locale, istZone)
            val timeOnly = RvDateFormat.formatTimeOnly(fixedEpochMs, locale, istZone)
            val decimal = RvDateFormat.formatDecimal(12.5f, 1, locale)

            assertTrue("shortDt blank for $locale", shortDt.isNotBlank())
            assertTrue("fullDt blank for $locale", fullDt.isNotBlank() && fullDt.contains("2026"))
            assertTrue("timeOnly blank for $locale", timeOnly.isNotBlank())
            assertTrue("decimal blank for $locale", decimal.isNotBlank())
        }
        assertEquals("12.5", RvDateFormat.formatDecimal(12.5f, 1, Locale.ENGLISH))
        assertEquals(1795669200_000L, RvDateFormat.parseIsoInstantEpochMillis("2026-11-26T05:00:00Z"))
        assertNotNull(RvDateFormat.parseIsoInstantEpochMillis("2026-11-26T10:30:00+05:30"))
    }

    @Test
    fun nonFrozenUiAndViewModels_containNoSimpleDateFormatOrHardcodedLocaleUs() {
        val root = resolveSourceRoot()
        val frozenAllowlist = setOf(
            "ui/screens/RoomScreen.kt",
            "ui/screens/room/AudioRouteSheet.kt",
            "ui/screens/room/AudioVuMeter.kt",
            "ui/screens/room/DiagnosticsDialog.kt",
            "ui/screens/room/LeaveRideDialog.kt",
            "ui/screens/room/SosAlertBanner.kt"
        )
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (!(rel.startsWith("ui/") || rel.startsWith("mesh/ui/"))) return@forEach
                if (rel in frozenAllowlist) return@forEach

                file.readLines().forEachIndexed { idx, line ->
                    if (line.contains("SimpleDateFormat(") || line.contains("Locale.US")) {
                        violations += "$rel:${idx + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "Found SimpleDateFormat or Locale.US in non-frozen UI/ViewModel files:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
