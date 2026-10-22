package com.ridervoice.utils

import android.util.Log
import com.ridervoice.BuildConfig

/**
 * RvLog – centralized debug logging facade for RiderVoice instrumentation.
 *
 * Tag prefix: RV_<component>
 * All messages are gated by BuildConfig.DEBUG OR the "Diagnostic logging" setting.
 * NEVER log tokens, emails, or raw GPS in release.
 *
 * Usage:
 *   RvLog.d("VOX", "path=adm frames/s=$fps")
 *   RvLog.audio("requested=$req routed=$actual mismatch=$mismatch")
 *   RvLog.vox("open floor=$floor thr=$thr speed=$speed")
 */
object RvLog {

    /** Runtime toggle — may be set from Settings "Diagnostic logging" */
    @Volatile var diagnosticLoggingEnabled: Boolean = false

    private fun isEnabled(): Boolean = BuildConfig.DEBUG || diagnosticLoggingEnabled

    // ─── Generic helpers ─────────────────────────────────────────────────────

    fun d(component: String, msg: String) {
        if (isEnabled()) Log.d("RV_$component", msg)
    }

    fun i(component: String, msg: String) {
        if (isEnabled()) Log.i("RV_$component", msg)
    }

    fun w(component: String, msg: String) {
        // Warnings always logged (needed for production debugging)
        Log.w("RV_$component", msg)
    }

    fun e(component: String, msg: String, t: Throwable? = null) {
        Log.e("RV_$component", msg, t)
    }

    // ─── Domain-specific helpers (exact patterns for runtime test script) ────

    /** VOX events: open/close, floor, threshold, speed multiplier, capture path */
    fun vox(msg: String) = d("VOX", msg)

    /** Audio routing: requested vs routed device, mismatch */
    fun audio(msg: String) = d("AUDIO", msg)

    /** LiveKit connection state changes */
    fun lk(msg: String) = d("LK", msg)

    /** Room lifecycle: roomId, phase */
    fun room(msg: String) = d("ROOM", msg)

    /** Location: interval, priority, thermal tier */
    fun loc(msg: String) = d("LOC", msg)

    /** Battery: start%, end%, perHour drain */
    fun bat(msg: String) = d("BAT", msg)

    /** Diagnostics collection events */
    fun diag(msg: String) = d("DIAG", msg)

    /** Network events */
    fun net(msg: String) = d("NET", msg)
}
