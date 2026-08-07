package com.ridervoice.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = NeonOrange, secondary = ElectricCyan, tertiary = NeonViolet,
    background = DarkPalette.Background, surface = DarkPalette.Surface,
    onPrimary = DarkPalette.Background, onSecondary = DarkPalette.Background, onTertiary = DarkPalette.Background,
    onBackground = DarkPalette.TextPrimary, onSurface = DarkPalette.TextPrimary,
    error = AlertRed, onError = DarkPalette.TextPrimary
)

private val LightScheme = lightColorScheme(
    primary = NeonOrange, secondary = ElectricCyan, tertiary = NeonViolet,
    background = LightPalette.Background, surface = LightPalette.Surface,
    onPrimary = LightPalette.Surface, onSecondary = LightPalette.Surface, onTertiary = LightPalette.Surface,
    onBackground = LightPalette.TextPrimary, onSurface = LightPalette.TextPrimary,
    error = AlertRed, onError = LightPalette.Surface
)

@Composable
fun RiderVoiceTheme(content: @Composable () -> Unit) {
    val isDark = ThemeState.isDarkTheme
    val target = if (isDark) DarkScheme else LightScheme

    // Smooth color crossfade instead of an instant, jarring flip
    val animatedBg by animateColorAsState(target.background, tween(300), label = "bg")

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = animatedBg.toArgb()
            window.navigationBarColor = animatedBg.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(colorScheme = target, typography = Typography, content = content)
}
