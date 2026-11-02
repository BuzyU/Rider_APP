package com.ridervoice.mesh.audio

import com.ridervoice.audio.HardwarePTTManager
import com.ridervoice.audio.VoxDetector
import com.ridervoice.audio.VoxEngine
import com.ridervoice.mesh.transport.VoiceTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stage 2 Bridge connecting audio input events (PTT button, VOX speech gate) to the active mesh transport.
 *
 * Subscribes to hardware PTT events and VoxEngine speech detection, delegating to [VoiceTransport.setMicrophoneEnabled].
 * Also provides single-AudioRecord frame gating via [shouldTransmitPcmFrame] with built-in [VoxDetector].
 */
class MeshVoxBridge(
    private val transport: VoiceTransport,
    private val hardwarePttManager: HardwarePTTManager? = null,
    private val voxEngine: VoxEngine? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()

    @Volatile
    var isPttHeld: Boolean = false
        private set

    @Volatile
    var isVoxSpeechActive: Boolean = false
        private set

    private val _voxEnabled = MutableStateFlow(true)
    val voxEnabled: StateFlow<Boolean> = _voxEnabled.asStateFlow()

    private val _isPttActive = MutableStateFlow(false)
    val isPttActive: StateFlow<Boolean> = _isPttActive.asStateFlow()

    private val _isGateOpen = MutableStateFlow(false)
    val isGateOpen: StateFlow<Boolean> = _isGateOpen.asStateFlow()

    val voxDetector: VoxDetector = VoxDetector()

    private var voxJob: Job? = null
    private var previousPttCallback: ((Boolean) -> Unit)? = null

    init {
        // Wire Hardware PTT manager
        hardwarePttManager?.let { ptt ->
            previousPttCallback = ptt.onMicToggleRequest
            ptt.onMicToggleRequest = { open ->
                scope.launch {
                    mutex.withLock {
                        isPttHeld = open
                        _isPttActive.value = open
                        updateMicState()
                    }
                }
            }
        }

        // Wire VoxEngine mic gate
        voxEngine?.let { vox ->
            voxJob = scope.launch {
                vox.isMicOpen.collectLatest { open ->
                    mutex.withLock {
                        isVoxSpeechActive = open
                        updateMicState()
                    }
                }
            }
        }
    }

    /** Direct manual PTT control (e.g. from UI buttons). */
    fun setPttPressed(pressed: Boolean) {
        isPttHeld = pressed
        _isPttActive.value = pressed
        scope.launch {
            mutex.withLock {
                updateMicState()
            }
        }
    }

    /** Set whether VOX gate detection is enabled. */
    fun setVoxEnabled(enabled: Boolean) {
        _voxEnabled.value = enabled
        if (!enabled) {
            isVoxSpeechActive = false
            _isGateOpen.value = isPttHeld
            scope.launch {
                mutex.withLock {
                    updateMicState()
                }
            }
        }
    }

    /** Update VOX speech active state. */
    fun setVoxSpeechActive(active: Boolean) {
        isVoxSpeechActive = active
        scope.launch {
            mutex.withLock {
                updateMicState()
            }
        }
    }

    /**
     * Inspects a raw PCM frame (640-byte 16 kHz mono 16-bit PCM = 320 samples) and returns
     * true if the frame should be transmitted over the mesh link.
     */
    @Synchronized
    fun shouldTransmitPcmFrame(pcmBytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isPttHeld) {
            _isGateOpen.value = true
            return true
        }
        if (!_voxEnabled.value) {
            _isGateOpen.value = false
            return false
        }
        val shorts = ShortArray(pcmBytes.size / 2)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val decision = voxDetector.onFrame(shorts, 16_000, nowMs, isVoxSpeechActive)
        when (decision) {
            VoxDetector.Decision.OPEN -> isVoxSpeechActive = true
            VoxDetector.Decision.CLOSE -> isVoxSpeechActive = false
            VoxDetector.Decision.NONE -> {}
        }
        val open = isPttHeld || isVoxSpeechActive
        _isGateOpen.value = open
        return open
    }

    private suspend fun updateMicState() {
        val shouldOpen = isPttHeld || isVoxSpeechActive
        _isGateOpen.value = shouldOpen
        transport.setMicrophoneEnabled(shouldOpen)
    }

    override fun close() {
        hardwarePttManager?.let { ptt ->
            if (ptt.onMicToggleRequest != null) {
                ptt.onMicToggleRequest = previousPttCallback
            }
        }
        voxJob?.cancel()
        scope.cancel()
    }
}
