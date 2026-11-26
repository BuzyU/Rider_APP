package com.ridervoice.ui.theme

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

@VisibleForTesting
internal var enableEdgeToEdgeInvocationCount: Int = 0

/**
 * Synchronizes window background, cutout layout mode, transparent system bars,
 * contrast enforcement flags, and light/dark status/navigation icon appearance
 * with the in-app theme boolean [isDark] regardless of system dark mode.
 */
fun ComponentActivity.applyRiderVoiceWindowChrome(isDark: Boolean) {
    ThemeState.set(isDark)
    val barStyle = if (isDark) {
        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
    } else {
        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
    }
    enableEdgeToEdgeInvocationCount++
    enableEdgeToEdge(
        statusBarStyle = barStyle,
        navigationBarStyle = barStyle
    )
    val bgArgb = (if (isDark) DarkPalette.Background else LightPalette.Background).toArgb()
    window.setBackgroundDrawable(ColorDrawable(bgArgb))
    window.decorView.setBackgroundColor(bgArgb)
    @Suppress("DEPRECATION")
    run {
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
    }
    if (Build.VERSION.SDK_INT >= 28) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !isDark
        isAppearanceLightNavigationBars = !isDark
    }
}
