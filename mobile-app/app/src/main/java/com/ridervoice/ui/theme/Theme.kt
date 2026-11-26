package com.ridervoice.ui.theme

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag

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
    val activity = if (view.isInEditMode) null else view.context.findActivity() as? ComponentActivity
    DisposableEffect(activity, isDark) {
        activity?.applyRiderVoiceWindowChrome(isDark)
        onDispose {}
    }

    MaterialTheme(colorScheme = target, typography = Typography) {
        Surface(
            color = Paper,
            contentColor = Ink,
            modifier = Modifier.testTag("rv_root_backdrop")
        ) {
            content()
        }
    }
}
