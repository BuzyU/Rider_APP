package com.ridervoice.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

object ThemeState {
    var isDarkTheme by mutableStateOf(false)
        private set

    fun set(dark: Boolean) { isDarkTheme = dark }
}

internal object LightPalette {
    val Background = Color(0xFFEAE6DF)
    val Surface = Color(0xFFF6F4EE)
    val SurfaceAlt = Color(0xFFDED9CE)
    val Border = Color(0xFFC2BCB0)
    val TextPrimary = Color(0xFF16191D)
    val TextSecondary = Color(0xFF5A626E)

    val AccentAmber = Color(0xFFC75C00)
    val AccentAmberContainer = Color(0xFFFBE3CB)
    val VhfTeal = Color(0xFF007D8C)
    val SignalGreen = Color(0xFF1E7E34)
    val StandbyOchre = Color(0xFFA87000)
    val EmergencyRed = Color(0xFFC62828)
    val EmergencyContainer = Color(0xFFFCD8D7)
    val AccentViolet = Color(0xFF5B21B6)
}

internal object DarkPalette {
    val Background = Color(0xFF101316)
    val Surface = Color(0xFF171B20)
    val SurfaceAlt = Color(0xFF20252D)
    val Border = Color(0xFF2E353E)
    val TextPrimary = Color(0xFFF2F4F7)
    val TextSecondary = Color(0xFF8E98A5)

    val AccentAmber = Color(0xFFFF9D2E)
    val AccentAmberContainer = Color(0xFF3D260D)
    val VhfTeal = Color(0xFF26C6DA)
    val SignalGreen = Color(0xFF4ADE80)
    val StandbyOchre = Color(0xFFFFC107)
    val EmergencyRed = Color(0xFFFF453A)
    val EmergencyContainer = Color(0xFF401212)
    val AccentViolet = Color(0xFF818CF8)
}

val GraphiteBase: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Background else LightPalette.Background
val DarkSlate: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Surface else LightPalette.Surface
val Gunmetal: Color get() = if (ThemeState.isDarkTheme) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt
val GraphiteSurface: Color get() = if (ThemeState.isDarkTheme) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt
val BorderColor: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Border else LightPalette.Border
val TextPrimary: Color get() = if (ThemeState.isDarkTheme) DarkPalette.TextPrimary else LightPalette.TextPrimary
val TextSecondary: Color get() = if (ThemeState.isDarkTheme) DarkPalette.TextSecondary else LightPalette.TextSecondary

val NeonOrange: Color get() = if (ThemeState.isDarkTheme) DarkPalette.AccentAmber else LightPalette.AccentAmber
val ElectricCyan: Color get() = if (ThemeState.isDarkTheme) DarkPalette.VhfTeal else LightPalette.VhfTeal
val NeonViolet: Color get() = if (ThemeState.isDarkTheme) DarkPalette.AccentViolet else LightPalette.AccentViolet
val TechGreen: Color get() = if (ThemeState.isDarkTheme) DarkPalette.SignalGreen else LightPalette.SignalGreen

val SuccessGreen: Color get() = if (ThemeState.isDarkTheme) DarkPalette.SignalGreen else LightPalette.SignalGreen
val AlertRed: Color get() = if (ThemeState.isDarkTheme) DarkPalette.EmergencyRed else LightPalette.EmergencyRed
val WarningAmber: Color get() = if (ThemeState.isDarkTheme) DarkPalette.StandbyOchre else LightPalette.StandbyOchre

val AmberContainer: Color get() = if (ThemeState.isDarkTheme) DarkPalette.AccentAmberContainer else LightPalette.AccentAmberContainer
val HazardContainer: Color get() = if (ThemeState.isDarkTheme) DarkPalette.EmergencyContainer else LightPalette.EmergencyContainer
