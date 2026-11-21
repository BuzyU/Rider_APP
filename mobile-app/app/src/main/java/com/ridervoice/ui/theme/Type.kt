package com.ridervoice.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import com.ridervoice.R

val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val fontManrope = GoogleFont("Manrope")
val fontBarlowCondensed = GoogleFont("Barlow Condensed")
val fontSpaceMono = GoogleFont("Space Mono")
val fontInter = GoogleFont("Inter")

val ManropeFamily = FontFamily(
    Font(googleFont = fontManrope, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = fontManrope, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = fontManrope, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = fontManrope, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = fontManrope, fontProvider = provider, weight = FontWeight.ExtraBold)
)

val BarlowCondensedFamily = FontFamily(
    Font(googleFont = fontBarlowCondensed, fontProvider = provider, weight = FontWeight.Black),
    Font(googleFont = fontBarlowCondensed, fontProvider = provider, weight = FontWeight.ExtraBold),
    Font(googleFont = fontBarlowCondensed, fontProvider = provider, weight = FontWeight.Bold)
)

val OrbitronFamily = ManropeFamily

val SpaceMonoFamily = FontFamily(
    Font(googleFont = fontSpaceMono, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = fontSpaceMono, fontProvider = provider, weight = FontWeight.Normal)
)

val InterFamily = FontFamily(
    Font(googleFont = fontInter, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = fontInter, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = fontInter, fontProvider = provider, weight = FontWeight.Bold)
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)
