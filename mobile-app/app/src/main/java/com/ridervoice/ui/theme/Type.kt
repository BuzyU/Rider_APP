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

val fontBarlowCondensed = GoogleFont("Barlow Condensed")
val fontSpaceMono = GoogleFont("Space Mono")
val fontInter = GoogleFont("Inter")

val BarlowCondensedFamily = FontFamily(
    Font(googleFont = fontBarlowCondensed, fontProvider = provider, weight = FontWeight.Black),
    Font(googleFont = fontBarlowCondensed, fontProvider = provider, weight = FontWeight.ExtraBold),
    Font(googleFont = fontBarlowCondensed, fontProvider = provider, weight = FontWeight.Bold)
)

// Backwards compatibility alias for OrbitronFamily
val OrbitronFamily = BarlowCondensedFamily

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
        fontFamily = BarlowCondensedFamily,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = 2.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = 1.5.sp
    ),
    titleMedium = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 1.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 1.0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    )
)

