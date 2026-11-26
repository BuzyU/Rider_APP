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

/**
 * Roadside Paper — Light Default Palette (Matches Stitch Field-Notebook Design System).
 * Base 1 (Paper): Bone #F5F1E8 / Paper #FFFDF8 / Sand #ECE5D6
 * Base 2 (Ink):   Carbon Ink #23201B / Secondary #6B645A / Tertiary #9A9286 / Hairline #E3DCCB
 * Primary Pop:    Ember Terracotta #9C3E1F (Container #F3DDD2, OnEmber #FFFDF8)
 * Moss Status:    #5F7355 (Tint #E1E8D9) — Active Fleet / Live Convoy / Completed pill only
 * Brick Hazard:   #B2352B (Tint #F4D9D5) — SOS / Leave / Delete / Error only
 * Strictly zero blue, cyan, violet, teal, or neon.
 */
internal object LightPalette {
    val Background = Color(0xFFF5F1E8)          // Bone Canvas
    val Surface = Color(0xFFFFFDF8)             // Paper Card
    val SurfaceAlt = Color(0xFFECE5D6)          // Sand Bento Tile
    val Border = Color(0xFFE3DCCB)              // Crisp Map Hairline
    val BorderStrong = Color(0xFF786F63)        // High-contrast interactive border (>= 3.9:1 on Paper/Raised/Sunken)
    val TextPrimary = Color(0xFF23201B)         // Carbon Ink (14.4:1 on Bone)
    val TextSecondary = Color(0xFF6B645A)       // Graphite Secondary (5.35:1 on Bone)
    val TextDisabled = Color(0xFF9A9286)        // Tertiary Ink

    val AccentAmber = Color(0xFF9C3E1F)         // Ember Primary (6.12:1 on Bone)
    val AccentAmberContainer = Color(0xFFF3DDD2)// Ember Pill Tint (5.16:1 with Ember)
    val OnAccent = Color(0xFFFFFDF8)            // Warm White on Ember (6.69:1)

    val CharcoalAction = Color(0xFF2B2722)      // Dark anchored icon/action pill
    val Moss = Color(0xFF4E6145)                // Moss Status (6.08:1 on Bone)
    val MossContainer = Color(0xFFE1E8D9)       // Moss Pill Tint (5.43:1 with Moss)
    val StatusOk = Moss
    val StatusOkContainer = MossContainer
    val StatusWarn = Color(0xFF7A5600)          // Warm Ochre Warn (5.90:1 on Bone)
    val StatusWarnContainer = Color(0xFFF4E8C1) // Ochre Container (5.43:1 with StatusWarn)

    val VhfTeal = Color(0xFF6B645A)             // Neutralized to InkSecondary (zero blue/cyan)
    val SignalGreen = Color(0xFF23201B)         // Neutralized to Ink
    val StandbyOchre = Color(0xFF23201B)        // Neutralized to Ink
    val EmergencyRed = Color(0xFFB2352B)        // Deep Brick (5.16:1 on Bone)
    val EmergencyContainer = Color(0xFFF4D9D5)  // Brick Tint (4.54:1 with Brick)
    val OnEmergency = Color(0xFFFFFDF8)         // Warm White on Brick (5.64:1)
    val AccentViolet = Color(0xFF6B645A)        // Neutralized to InkSecondary (zero violet)
}

/**
 * Roadside Paper Dark — Sleek Obsidian Carbon Dark Palette (blue-free, neon-free, WCAG AA+ verified).
 * Base 1 (Obsidian): Canvas #0E0E0F / Card #171718 / Elevated Tile #222224 / Hairline #2C2C2E
 * Base 2 (Alabaster): Ink #F4F3F0 / Secondary #A4A29D / Disabled #6B6965
 * Primary Pop:       Warm Ember #E47A4E (Container #2A1B14, OnEmber #101011)
 * Moss Status:       Fresh Sage #7BC492 (Container #16271C)
 * Brick Hazard:      Crisp Crimson #F2645A (Container #2E1516, OnBrick #101011)
 */
internal object DarkPalette {
    val Background = Color(0xFF0E0E0F)
    val Surface = Color(0xFF171718)
    val SurfaceAlt = Color(0xFF222224)
    val Border = Color(0xFF2C2C2E)
    val BorderStrong = Color(0xFF82807A)        // High-contrast interactive border (>= 4.0:1 on Dark Paper/Raised/Sunken)
    val TextPrimary = Color(0xFFF4F3F0)
    val TextSecondary = Color(0xFFA4A29D)
    val TextDisabled = Color(0xFF6B6965)

    val AccentAmber = Color(0xFFE47A4E)
    val AccentAmberContainer = Color(0xFF2A1B14)
    val OnAccent = Color(0xFF101011)

    val CharcoalAction = Color(0xFFF0EDE8)
    val Moss = Color(0xFF7BC492)
    val MossContainer = Color(0xFF16271C)
    val StatusOk = Moss
    val StatusOkContainer = MossContainer
    val StatusWarn = Color(0xFFE6B44C)          // Warm Ochre Warn (10.10:1 on Obsidian)
    val StatusWarnContainer = Color(0xFF2B210B) // Ochre Container (8.30:1 with StatusWarn)

    val VhfTeal = Color(0xFFA4A29D)
    val SignalGreen = Color(0xFFF4F3F0)
    val StandbyOchre = Color(0xFFF4F3F0)
    val EmergencyRed = Color(0xFFF2645A)
    val EmergencyContainer = Color(0xFF2E1516)
    val OnEmergency = Color(0xFF101011)
    val AccentViolet = Color(0xFFA4A29D)
}

// --- Roadside Paper Semantic Tokens ---
val Paper: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Background else LightPalette.Background
val PaperRaised: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Surface else LightPalette.Surface
val PaperSunken: Color get() = if (ThemeState.isDarkTheme) DarkPalette.SurfaceAlt else LightPalette.SurfaceAlt
val InkHairline: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Border else LightPalette.Border
val InkBorderStrong: Color get() = if (ThemeState.isDarkTheme) DarkPalette.BorderStrong else LightPalette.BorderStrong
val Ink: Color get() = if (ThemeState.isDarkTheme) DarkPalette.TextPrimary else LightPalette.TextPrimary
val InkSecondary: Color get() = if (ThemeState.isDarkTheme) DarkPalette.TextSecondary else LightPalette.TextSecondary
val InkDisabled: Color get() = if (ThemeState.isDarkTheme) DarkPalette.TextDisabled else LightPalette.TextDisabled

val Pop: Color get() = if (ThemeState.isDarkTheme) DarkPalette.AccentAmber else LightPalette.AccentAmber
val PopContainer: Color get() = if (ThemeState.isDarkTheme) DarkPalette.AccentAmberContainer else LightPalette.AccentAmberContainer
val PaperOnPop: Color get() = if (ThemeState.isDarkTheme) DarkPalette.OnAccent else LightPalette.OnAccent

val CharcoalSurface: Color get() = if (ThemeState.isDarkTheme) DarkPalette.CharcoalAction else LightPalette.CharcoalAction
val Moss: Color get() = if (ThemeState.isDarkTheme) DarkPalette.Moss else LightPalette.Moss
val MossContainer: Color get() = if (ThemeState.isDarkTheme) DarkPalette.MossContainer else LightPalette.MossContainer
val StatusOk: Color get() = if (ThemeState.isDarkTheme) DarkPalette.StatusOk else LightPalette.StatusOk
val StatusOkContainer: Color get() = if (ThemeState.isDarkTheme) DarkPalette.StatusOkContainer else LightPalette.StatusOkContainer
val StatusWarn: Color get() = if (ThemeState.isDarkTheme) DarkPalette.StatusWarn else LightPalette.StatusWarn
val StatusWarnContainer: Color get() = if (ThemeState.isDarkTheme) DarkPalette.StatusWarnContainer else LightPalette.StatusWarnContainer

// --- Hero Photo Scrim & On-Photo Overlay Constants (HomeScreen hero image) ---
val HeroPhotoScrimTop = Color(0xDE4A1E0E)
val HeroPhotoScrimMid = Color(0x9E3D1C10)
val HeroPhotoScrimBottom = Color(0x4D1E1510)
val HeroPhotoTextPrimary = Color(0xFFFFFDF8)
val HeroPhotoTextSecondary = Color(0xE0FFFDF8)
val HeroPhotoBadgeFill = Color(0xFFFFFDF8)
val HeroPhotoBadgeIcon = Color(0xFF23201B)

val Brick: Color get() = if (ThemeState.isDarkTheme) DarkPalette.EmergencyRed else LightPalette.EmergencyRed
val BrickContainer: Color get() = if (ThemeState.isDarkTheme) DarkPalette.EmergencyContainer else LightPalette.EmergencyContainer
val PaperOnBrick: Color get() = if (ThemeState.isDarkTheme) DarkPalette.OnEmergency else LightPalette.OnEmergency

/** Standard modal backdrop scrim overlay shared by full-window sheets and dialogs. */
val RvScrim: Color get() = Color(0x73000000)

/**
 * Legacy token aliases preserved strictly for frozen online room files (`RoomScreen.kt`,
 * `AudioRouteSheet.kt`, `AudioVuMeter.kt`, `DiagnosticsDialog.kt`, `LeaveRideDialog.kt`,
 * `SosAlertBanner.kt`) and backwards compatibility. All active non-frozen UI must use
 * the semantic tokens above (`Paper`, `PaperRaised`, `PaperSunken`, `Ink`, `InkSecondary`,
 * `InkHairline`, `InkBorderStrong`, `Pop`, `Moss`, `Brick`, `RvScrim`).
 */
val GraphiteBase: Color get() = Paper
val DarkSlate: Color get() = PaperRaised
val Gunmetal: Color get() = PaperSunken
val GraphiteSurface: Color get() = PaperSunken
val BorderColor: Color get() = InkHairline
val TextPrimary: Color get() = Ink
val TextSecondary: Color get() = InkSecondary
val TextMuted: Color get() = InkSecondary

val NeonOrange: Color get() = Pop
val ElectricCyan: Color get() = InkSecondary
val NeonViolet: Color get() = InkSecondary
val TechGreen: Color get() = Ink

val SuccessGreen: Color get() = Ink
val AlertRed: Color get() = Brick
val WarningAmber: Color get() = Ink

val AmberContainer: Color get() = PopContainer
val HazardContainer: Color get() = BrickContainer
