package com.ridervoice.mesh.audio

import com.ridervoice.audio.VoxDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MicInputs(
    val audioEnabled: Boolean,
    val micToggleOn: Boolean,
    val pttHeld: Boolean,
    val voxEnabled: Boolean,
    val voxArmed: Boolean
)

object MicRules {
    fun captureWanted(i: MicInputs): Boolean =
        i.audioEnabled && (i.micToggleOn || i.pttHeld || i.voxEnabled)

    fun transmit(i: MicInputs): Boolean =
        i.audioEnabled && (i.micToggleOn || i.pttHeld || (i.voxEnabled && i.voxArmed))
}

interface MicController {
    val micToggleOn: StateFlow<Boolean>
    val pttHeld: StateFlow<Boolean>
    val voxEnabled: StateFlow<Boolean>
    val voxArmed: StateFlow<Boolean>
    var onPreRollFrames: ((List<ByteArray>) -> Unit)?
        get() = null
        set(_) {}
    fun popPreRollFrames(): List<ByteArray> = emptyList()
    fun setMicToggled(on: Boolean)
    fun toggleMic()                       // helmet key
    fun setPttPressed(pressed: Boolean)
    fun setVoxEnabled(enabled: Boolean)   // default false
    fun setVoxArmed(armed: Boolean)
    fun shouldTransmitPcmFrame(pcm: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean
    fun reset()                           // called from transport.stop()
    fun close() {}
}

class MicControllerImpl(
    private val audioEnabled: () -> Boolean,
    private val applyCapture: suspend (Boolean) -> Unit,    // transport-internal start/stop of AudioRecord
    private val voxDetector: VoxDetector = VoxDetector(),
    private val scope: CoroutineScope,
    initialVoxEnabled: Boolean = false
) : MicController {
    private val _toggle = MutableStateFlow(false)
    override val micToggleOn: StateFlow<Boolean> = _toggle.asStateFlow()

    private val _ptt = MutableStateFlow(false)
    override val pttHeld: StateFlow<Boolean> = _ptt.asStateFlow()

    private val _vox = MutableStateFlow(initialVoxEnabled)
    override val voxEnabled: StateFlow<Boolean> = _vox.asStateFlow()

    private val _armed = MutableStateFlow(false)
    override val voxArmed: StateFlow<Boolean> = _armed.asStateFlow()

    override var onPreRollFrames: ((List<ByteArray>) -> Unit)? = null
    private val preRollBuffer = ArrayDeque<ByteArray>(4)
    private val preRollOutputQueue = mutableListOf<ByteArray>()

    override fun popPreRollFrames(): List<ByteArray> {
        val result = preRollOutputQueue.toList()
        preRollOutputQueue.clear()
        return result
    }

    // CONFLATED: only the latest desired capture state matters, and order is preserved.
    private val desired = Channel<Boolean>(Channel.CONFLATED)
    @Volatile private var consumerStarted = false
    private val startLock = Any()

    private fun ensureConsumerStarted() {
        if (!consumerStarted) {
            synchronized(startLock) {
                if (!consumerStarted) {
                    consumerStarted = true
                    scope.launch {
                        for (want in desired) {
                            applyCapture(want)
                        }
                    }
                }
            }
        }
    }

    private fun inputs() = MicInputs(audioEnabled(), _toggle.value, _ptt.value, _vox.value, _armed.value)

    private fun publish() {
        ensureConsumerStarted()
        desired.trySend(MicRules.captureWanted(inputs()))
    }

    override fun setMicToggled(on: Boolean) {
        _toggle.value = on
        publish()
    }

    override fun toggleMic() {
        _toggle.value = !_toggle.value
        publish()
    }

    override fun setPttPressed(pressed: Boolean) {
        _ptt.value = pressed
        publish()
    }

    override fun setVoxEnabled(enabled: Boolean) {
        _vox.value = enabled
        if (!enabled) _armed.value = false
        publish()
    }

    override fun setVoxArmed(armed: Boolean) {
        _armed.value = armed
        publish()
    }

    @Synchronized
    override fun shouldTransmitPcmFrame(pcm: ByteArray, nowMs: Long): Boolean {
        if (_toggle.value || _ptt.value) {
            preRollBuffer.clear()
            return true
        }
        if (!_vox.value) {
            preRollBuffer.clear()
            return false
        }
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val wasArmed = _armed.value
        when (voxDetector.onFrame(shorts, 16_000, nowMs, wasArmed)) {
            VoxDetector.Decision.OPEN -> {
                _armed.value = true
                if (!wasArmed) {
                    val preRoll = preRollBuffer.toList()
                    preRollBuffer.clear()
                    if (preRoll.isNotEmpty()) {
                        preRollOutputQueue.addAll(preRoll)
                        onPreRollFrames?.invoke(preRoll)
                    }
                }
            }
            VoxDetector.Decision.CLOSE -> {
                _armed.value = false
            }
            VoxDetector.Decision.NONE -> Unit
        }
        if (!_armed.value) {
            // Retain up to 4 pre-roll frames while gate is closed
            if (preRollBuffer.size >= 4) {
                preRollBuffer.removeFirst()
            }
            preRollBuffer.addLast(pcm.copyOf())
        }
        return MicRules.transmit(inputs())
    }

    override fun reset() {
        _toggle.value = false
        _ptt.value = false
        _vox.value = false
        _armed.value = false
        preRollBuffer.clear()
        preRollOutputQueue.clear()
        publish()
    }

    override fun close() {
        desired.close()
    }
}
