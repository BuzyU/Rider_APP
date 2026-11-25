package com.ridervoice.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

private val DarkScheme = darkColorScheme(
    primary = DarkPalette.AccentAmber,
    onPrimary = DarkPalette.OnAccent,
    primaryContainer = DarkPalette.AccentAmberContainer,
    onPrimaryContainer = DarkPalette.AccentAmber,
    secondary = DarkPalette.TextSecondary,
    onSecondary = DarkPalette.Background,
    tertiary = DarkPalette.TextSecondary,
    onTertiary = DarkPalette.Background,
    background = DarkPalette.Background,
    onBackground = DarkPalette.TextPrimary,
    surface = DarkPalette.Surface,
    onSurface = DarkPalette.TextPrimary,
    surfaceVariant = DarkPalette.SurfaceAlt,
    onSurfaceVariant = DarkPalette.TextSecondary,
    outline = DarkPalette.Border,
    error = DarkPalette.EmergencyRed,
    onError = DarkPalette.OnEmergency,
    errorContainer = DarkPalette.EmergencyContainer,
    onErrorContainer = DarkPalette.EmergencyRed
)

private val LightScheme = lightColorScheme(
    primary = LightPalette.AccentAmber,
    onPrimary = LightPalette.OnAccent,
    primaryContainer = LightPalette.AccentAmberContainer,
    onPrimaryContainer = LightPalette.AccentAmber,
    secondary = LightPalette.TextSecondary,
    onSecondary = LightPalette.Surface,
    tertiary = LightPalette.TextSecondary,
    onTertiary = LightPalette.Surface,
    background = LightPalette.Background,
    onBackground = LightPalette.TextPrimary,
    surface = LightPalette.Surface,
    onSurface = LightPalette.TextPrimary,
    surfaceVariant = LightPalette.SurfaceAlt,
    onSurfaceVariant = LightPalette.TextSecondary,
    outline = LightPalette.Border,
    error = LightPalette.EmergencyRed,
    onError = LightPalette.OnEmergency,
    errorContainer = LightPalette.EmergencyContainer,
    onErrorContainer = LightPalette.EmergencyRed
)

private fun android.content.Context.findActivity(): Activity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
fun RiderVoiceTheme(content: @Composable () -> Unit) {
    val isDark = ThemeState.isDarkTheme
    val target = if (isDark) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() as? ComponentActivity ?: return@SideEffect
            val barStyle = if (isDark) {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            }
            activity.enableEdgeToEdge(
                statusBarStyle = barStyle,
                navigationBarStyle = barStyle
            )
        }
    }

    MaterialTheme(colorScheme = target, typography = Typography, content = content)
}
