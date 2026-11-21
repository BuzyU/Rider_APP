package com.ridervoice.mesh.ui

import androidx.compose.ui.graphics.Color
import com.ridervoice.ui.theme.*

/**
 * Centralized theme-aware Roadside Paper color tokens for Offline Mesh UI components.
 * Delegates to the app's unified Roadside Paper Light / Warm Charcoal Dark palettes
 * (zero blue, cyan, indigo, violet, or neon).
 */
object MeshColors {
    // Surfaces and backgrounds
    val BackgroundDark: Color get() = Paper
    val SurfaceDark: Color get() = PaperRaised
    val SurfaceElevated: Color get() = PaperSunken
    val CardBackground: Color get() = PaperRaised
    val LobbyBackground: Color get() = Paper
    val LobbyCard: Color get() = PaperRaised
    val LobbyInput: Color get() = PaperSunken
    val LobbyBorder: Color get() = InkHairline
    val LobbyBorderUnfocused: Color get() = InkHairline

    // Status and state indicators (icon + Ink weight; Pop for active speaking/PTT; Brick for error/leave)
    val Emerald: Color get() = Pop
    val EmeraldDark: Color get() = PopContainer
    val EmeraldLight: Color get() = Pop
    val EmeraldActive: Color get() = Pop

    val Amber: Color get() = Pop
    val AmberYellow: Color get() = InkSecondary
    val AmberDark: Color get() = PopContainer
    val AmberLight: Color get() = Ink

    val Cyan: Color get() = Ink
    val Red: Color get() = Brick
    val RedDark: Color get() = BrickContainer
    val RedCrimson: Color get() = Brick
    val RedLight: Color get() = Brick

    val Slate: Color get() = InkSecondary
    val SlateDark: Color get() = InkHairline
    val SlateMuted: Color get() = InkSecondary
    val SlateLight: Color get() = Ink
    val Indigo: Color get() = InkSecondary

    // Lobby & text tokens
    val NeonOrange: Color get() = Pop
    val ElectricGreen: Color get() = Pop
    val AlertRed: Color get() = Brick
    val TextPrimary: Color get() = Ink
    val TextSecondary: Color get() = InkSecondary
    val TextOnAccent: Color get() = PaperOnPop
    val TextOnBrick: Color get() = PaperOnBrick
}
