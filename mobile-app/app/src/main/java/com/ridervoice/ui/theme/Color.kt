package com.ridervoice.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for the active theme.
 * Default = LIGHT. Only changes via Settings — never reads the system theme.
 */
object ThemeState {
    var isDarkTheme by mutableStateOf(false)
        private set

    fun set(dark: Boolean) { isDarkTheme = dark }
}

internal object LightPalette {
    val Background = Color(0xFFF7F8FA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceAlt = Color(0xFFEDEFF2)
    val Border = Color(0xFFDDE1E6)
    val TextPrimary = Color(0xFF10151A)
    val TextSecondary = Color(0xFF5B6572)
}

internal object DarkPalette {
    val Background = Color(0xFF0E1116)  // Graphite
    val Surface = Color(0xFF151A20)     // Dark Slate
    val SurfaceAlt = Color(0xFF1D232B)  // Gunmetal
    val Border = Color(0xFF2B3138)      // Slate Gray
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA0AAB2)
}

// Theme-aware tokens — same identifiers your 21 screens already import.
val GraphiteBase: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Background else LightPalette.Background
val DarkSlate: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Surface else LightPalette.Surface
val Gunmetal: Color get() = if (ThemeState.isDarkTheme) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt
val GraphiteSurface: Color get() = if (ThemeState.isDarkTheme) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt
val BorderColor: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Border else LightPalette.Border
val TextPrimary: Color get() = if (ThemeState.isDarkTheme) DarkPalette.TextPrimary else LightPalette.TextPrimary
val TextSecondary: Color get() = if (ThemeState.isDarkTheme) DarkPalette.TextSecondary else LightPalette.TextSecondary

// Accents — identical in both themes (matches your swatch)
val NeonOrange = Color(0xFFFF8A00)
val ElectricCyan = Color(0xFF00B8D4)
val NeonViolet = Color(0xFF4FACFE)
val TechGreen = Color(0xFF00FF87)

val SuccessGreen = Color(0xFF22C55E)
val AlertRed = Color(0xFFFF4D4D)
val WarningAmber = Color(0xFFFFC107)
