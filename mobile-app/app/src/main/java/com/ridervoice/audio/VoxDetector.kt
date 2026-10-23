package com.ridervoice.audio

data class VoxConfig(
    val analysisMs: Int = 20,
    val attackMs: Int = 60,
    val holdMs: Int = 900,
    val openRatio: Float = 3.0f,
    val closeRatio: Float = 1.8f,
    val minFloor: Float = 120f,
    val maxFloor: Float = 4000f,
    val initialFloor: Float = 800f,
    val floorWindowMs: Int = 5000,
    val floorPercentile: Float = 0.2f,
    val floorUpTauMs: Int = 2500,
    val floorDownTauMs: Int = 500,
    val maxOpenWithoutSpeechMs: Int = 8000,
    val hpCutoffHz: Float = 130f
)

class NoiseFloorTracker(private val cfg: VoxConfig) {
    private val cap = cfg.floorWindowMs / cfg.analysisMs
    private val ring = FloatArray(cap)
    private var n = 0
    private var i = 0
    private var target = cfg.initialFloor
    var floor = cfg.initialFloor
        private set
    private var tick = 0

    fun update(rms: Float): Float {
        ring[i] = rms
        i = (i + 1) % cap
        if (n < cap) n++
        if (++tick % 10 == 0) { // every 200 ms
            val c = ring.copyOf(n)
            c.sort()
            target = c[(n * cfg.floorPercentile).toInt().coerceIn(0, n - 1)].coerceIn(cfg.minFloor, cfg.maxFloor)
        }
        val tau = if (target > floor) cfg.floorUpTauMs else cfg.floorDownTauMs
        floor += (target - floor) * (1f - kotlin.math.exp(-cfg.analysisMs / tau.toFloat()))
        return floor
    }

    fun setFloor(value: Float) {
        floor = value.coerceIn(cfg.minFloor, cfg.maxFloor)
        target = floor
    }
}

class BiquadHighPass(private val cutoffHz: Float = 130f, private val q: Float = 0.707f) {
    private var sampleRate = 0
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun updateSampleRate(rate: Int) {
        if (rate <= 0 || rate == sampleRate) return
        sampleRate = rate
        reset()
        val w0 = 2.0 * Math.PI * cutoffHz / sampleRate
        val alpha = Math.sin(w0) / (2.0 * q)
        val c = Math.cos(w0)
        val a0 = 1.0 + alpha
        b0 = (((1.0 + c) / 2.0) / a0).toFloat()
        b1 = ((-(1.0 + c)) / a0).toFloat()
        b2 = (((1.0 + c) / 2.0) / a0).toFloat()
        a1 = ((-2.0 * c) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
        com.ridervoice.utils.RvLog.vox("path=filter rate=$sampleRate fc=$cutoffHz")
    }

    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    fun process(frame: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val x = frame[i].toFloat()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            sum += y.toDouble() * y.toDouble()
        }
        return Math.sqrt(sum / count).toFloat()
    }
}

class VoxDetector(
    val cfg: VoxConfig = VoxConfig(),
    initialSampleRate: Int = 16000
) {
    enum class Decision { NONE, OPEN, CLOSE }

    companion object {
        const val FRAME = 320
        const val CLOSE_RATIO = 1.8f
        const val ATTACK_FRAMES = 5
        const val HOLD_MS = 900L
        const val FLOOR_MIN = 120f
        const val FLOOR_MAX = 4000f
        const val DEFAULT_OPEN_RATIO = 3.0f
    }

    private val filter = BiquadHighPass(cfg.hpCutoffHz, 0.707f)
    private val floorTracker = NoiseFloorTracker(cfg)

    @Volatile var openRatio = cfg.openRatio
    @Volatile var speedExtra = 1.0f

    val floor: Float get() = floorTracker.floor

    private var attackAccumMs = 0L
    private var lastSpeechTime = 0L
    private var openStartTime = 0L
    private var highestRmsDuringOpen = 0f

    init {
        filter.updateSampleRate(initialSampleRate)
    }

    val openThreshold: Float get() = floor * openRatio * speedExtra
    val closeThreshold: Float get() = floor * cfg.closeRatio * speedExtra

    fun setSpeedKmh(kmh: Float) {
        speedExtra = when {
            kmh <= 40f -> 1.0f
            kmh >= 120f -> 1.4f
            else -> 1.0f + 0.4f * ((kmh - 40f) / 80f)
        }
    }

    @Synchronized
    fun rms(frame: ShortArray, count: Int, sampleRate: Int = 16000): Float {
        filter.updateSampleRate(sampleRate)
        return filter.process(frame, count)
    }

    @Synchronized
    fun updateFloor(rms: Float) {
        floorTracker.update(rms)
    }

    @Synchronized
    fun setFloor(value: Float) {
        floorTracker.setFloor(value)
    }

    @Synchronized
    fun evaluate(rms: Float, now: Long, isOpen: Boolean, frameDurationMs: Long = cfg.analysisMs.toLong()): Decision {
        if (!isOpen) {
            if (rms >= openThreshold) {
                attackAccumMs += frameDurationMs
                if (attackAccumMs >= cfg.attackMs) {
                    lastSpeechTime = now
                    openStartTime = now
                    highestRmsDuringOpen = rms
                    attackAccumMs = 0L
                    com.ridervoice.utils.RvLog.vox("open floor=$floor thr=$openThreshold speed=$speedExtra")
                    return Decision.OPEN
                }
            } else {
                attackAccumMs = 0L
            }
        } else {
            if (rms > highestRmsDuringOpen) {
                highestRmsDuringOpen = rms
            }

            if (rms >= closeThreshold) {
                lastSpeechTime = now
            } else if (now - lastSpeechTime >= cfg.holdMs) {
                com.ridervoice.utils.RvLog.vox("close floor=$floor thr=$closeThreshold hold=${now - lastSpeechTime}")
                return Decision.CLOSE
            }

            // Max open guard: if OPEN for maxOpenWithoutSpeechMs and no window exceeded floor * openRatio * 1.2
            if (now - openStartTime >= cfg.maxOpenWithoutSpeechMs) {
                if (highestRmsDuringOpen < floor * openRatio * 1.2f) {
                    com.ridervoice.utils.RvLog.vox("close max_open_guard=true floor=$floor")
                    return Decision.CLOSE
                }
            }
        }
        return Decision.NONE
    }

    @Synchronized
    fun onFrame(pcm: ShortArray, sampleRate: Int, nowMs: Long, isOpen: Boolean): Decision {
        val r = rms(pcm, pcm.size, sampleRate)
        updateFloor(r)
        val frameMs = (pcm.size * 1000L) / sampleRate
        return evaluate(r, nowMs, isOpen, frameMs)
    }

    @Synchronized
    fun onPttRelease(now: Long) {
        lastSpeechTime = now
        attackAccumMs = 0L
    }

    @Synchronized
    fun resetAttack() {
        attackAccumMs = 0L
    }
}
