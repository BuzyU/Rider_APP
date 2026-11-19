package com.ridervoice.utils

import android.util.Log
import com.ridervoice.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * RvLog – centralized debug logging facade and numeric audio counter registry
 * for RiderVoice instrumentation.
 *
 * Tag prefix: RV_<component>
 * Debug logs are gated by BuildConfig.DEBUG OR the "Diagnostic logging" setting.
 * Non-PII numeric counters (underruns, concealed frames, gate open/close, route republish,
 * longest silence in talkspurt) log via Log.i so logcat captures from release builds work cleanly.
 * NEVER log tokens, emails, or raw GPS in release.
 */
object RvLog {

    /** Runtime toggle — may be set from Settings "Diagnostic logging" */
    @Volatile var diagnosticLoggingEnabled: Boolean = false

    val gateOpenCount = AtomicLong(0L)
    val gateCloseCount = AtomicLong(0L)
    val routeRepublishCount = AtomicLong(0L)
    val underrunCount = AtomicLong(0L)
    val concealedFrameCount = AtomicLong(0L)
    val lastLongestSilenceInTalkspurtMs = AtomicLong(0L)

    private fun isEnabled(): Boolean = BuildConfig.DEBUG || diagnosticLoggingEnabled

    // ─── Generic helpers ─────────────────────────────────────────────────────

    fun d(component: String, msg: String) {
        if (isEnabled()) runCatching { Log.d("RV_$component", msg) }
    }

    fun i(component: String, msg: String) {
        if (isEnabled()) runCatching { Log.i("RV_$component", msg) }
    }

    /** Always-on non-PII numeric metric log for release/debug logcat diagnostics. */
    fun metric(component: String, msg: String) {
        runCatching { Log.i("RV_$component", "[METRIC] [RvLog] $msg") }
    }

    fun w(component: String, msg: String) {
        // Warnings always logged (needed for production debugging)
        runCatching { Log.w("RV_$component", msg) }
    }

    fun e(component: String, msg: String, t: Throwable? = null) {
        runCatching { Log.e("RV_$component", msg, t) }
    }

    // ─── Audio metric counters (WP-154) ──────────────────────────────────────

    fun recordGateOpen(floor: Float, threshold: Float, speed: Float) {
        val opens = gateOpenCount.incrementAndGet()
        metric("VOX", "gate=OPEN count=$opens floor=$floor thr=$threshold speed=$speed")
    }

    fun recordGateClose(reason: String, floor: Float, threshold: Float, durationMs: Long, longestSilenceMs: Long) {
        val closes = gateCloseCount.incrementAndGet()
        val prevLongest = lastLongestSilenceInTalkspurtMs.get()
        if (longestSilenceMs > prevLongest) {
            lastLongestSilenceInTalkspurtMs.compareAndSet(prevLongest, longestSilenceMs)
        }
        metric("VOX", "gate=CLOSE count=$closes reason=$reason durationMs=$durationMs longestSilenceMs=$longestSilenceMs floor=$floor thr=$threshold")
    }

    fun recordRouteRepublish(status: String, route: String, reason: String) {
        val count = if (status == "executed") routeRepublishCount.incrementAndGet() else routeRepublishCount.get()
        metric("AUDIO", "route_republish status=$status count=$count route=$route reason=$reason")
    }

    fun recordPlayoutCounters(underrunDelta: Long, concealedDelta: Long, trackUnderrunDelta: Long) {
        val u = if (underrunDelta > 0) underrunCount.addAndGet(underrunDelta) else underrunCount.get()
        val c = if (concealedDelta > 0) concealedFrameCount.addAndGet(concealedDelta) else concealedFrameCount.get()
        val totalU = if (trackUnderrunDelta > 0) underrunCount.addAndGet(trackUnderrunDelta) else u
        metric("MESH_AUDIO", "playout underruns=$totalU concealed=$c")
    }

    fun audioCountersSummary(): String =
        "gateOpen=${gateOpenCount.get()} gateClose=${gateCloseCount.get()} " +
            "routeRepublish=${routeRepublishCount.get()} underruns=${underrunCount.get()} " +
            "concealed=${concealedFrameCount.get()} longestSilenceMs=${lastLongestSilenceInTalkspurtMs.get()}"

    fun resetAudioCounters() {
        gateOpenCount.set(0L)
        gateCloseCount.set(0L)
        routeRepublishCount.set(0L)
        underrunCount.set(0L)
        concealedFrameCount.set(0L)
        lastLongestSilenceInTalkspurtMs.set(0L)
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
