package com.ridervoice.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import com.ridervoice.network.ConnectionState

enum class RvHapticEvent {
    TICK,
    TOGGLE,
    CONFIRM,
    WARNING
}

fun interface HapticSink {
    fun perform(event: RvHapticEvent)
}

/**
 * Presentation-layer haptic dispatcher gated by [RideErgonomicsSettings.hapticFeedbackEnabled]
 * with a 5-second debounce on connection state transitions so cellular flapping cannot buzz continuously.
 */
class RvHapticsController(
    private val sink: HapticSink,
    private val isEnabled: () -> Boolean = { RideErgonomicsSettings.hapticFeedbackEnabled },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var lastObservedConnection: ConnectionState? = null
    private var lastConnectionPulseAtMs: Long? = null

    fun tick() {
        if (isEnabled()) sink.perform(RvHapticEvent.TICK)
    }

    fun toggle() {
        if (isEnabled()) sink.perform(RvHapticEvent.TOGGLE)
    }

    fun confirm() {
        if (isEnabled()) sink.perform(RvHapticEvent.CONFIRM)
    }

    fun warning() {
        if (isEnabled()) sink.perform(RvHapticEvent.WARNING)
    }

    fun onConnectionTransition(newState: ConnectionState) {
        val prev = lastObservedConnection
        lastObservedConnection = newState
        if (prev == null || prev == newState) return
        if (!isEnabled()) return

        val event = when (newState) {
            ConnectionState.CONNECTED -> RvHapticEvent.CONFIRM
            ConnectionState.RECONNECTING,
            ConnectionState.FAILED,
            ConnectionState.DISCONNECTED -> RvHapticEvent.WARNING
            ConnectionState.CONNECTING -> return
        }

        val now = clock()
        val last = lastConnectionPulseAtMs
        if (last == null || (now - last) >= CONNECTION_DEBOUNCE_MS) {
            lastConnectionPulseAtMs = now
            sink.perform(event)
        }
    }

    companion object {
        const val CONNECTION_DEBOUNCE_MS = 5_000L
    }
}

val LocalRvHaptics = staticCompositionLocalOf<RvHapticsController?> { null }

@Composable
fun rememberRvHapticsController(): RvHapticsController {
    LocalRvHaptics.current?.let { return it }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    return remember(haptic, view) {
        RvHapticsController(
            sink = { event ->
                when (event) {
                    RvHapticEvent.TICK -> {
                        if (!view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    RvHapticEvent.TOGGLE -> {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    RvHapticEvent.CONFIRM -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            if (!view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                    RvHapticEvent.WARNING -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            }
        )
    }
}
