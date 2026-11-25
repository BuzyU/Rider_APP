package com.ridervoice.ui.theme

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/**
 * Presentation-layer ride ergonomics preferences (UX-09).
 */
object RideErgonomicsSettings {
    private var _hapticFeedbackEnabled by mutableStateOf(true)
    val hapticFeedbackEnabled: Boolean get() = _hapticFeedbackEnabled

    private var _keepScreenOnDuringRide by mutableStateOf(true)
    val keepScreenOnDuringRide: Boolean get() = _keepScreenOnDuringRide

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        _hapticFeedbackEnabled = enabled
    }

    fun setKeepScreenOnDuringRide(enabled: Boolean) {
        _keepScreenOnDuringRide = enabled
    }
}

private fun android.content.Context.findHostActivity(): Activity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Keeps the screen awake while an active ride composable is on screen when [enabled] is true,
 * clearing both `view.keepScreenOn` and `FLAG_KEEP_SCREEN_ON` when disabled or disposed.
 */
@Composable
fun KeepRideScreenOnEffect(enabled: Boolean = RideErgonomicsSettings.keepScreenOnDuringRide) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findHostActivity()?.window
        view.keepScreenOn = enabled
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
