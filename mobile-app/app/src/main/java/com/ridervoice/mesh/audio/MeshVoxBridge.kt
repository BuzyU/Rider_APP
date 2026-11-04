package com.ridervoice.mesh.audio

import com.ridervoice.audio.VoxDetector
import com.ridervoice.audio.VoxEngine
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stage 2 Bridge connecting audio input events (PTT button, VOX speech gate) to the active mesh transport.
 *
 * Subscribes to hardware PTT events via [HardwarePttPort] and VoxEngine speech detection,
 * delegating to [MicController] or [VoiceTransport.setMicrophoneEnabled].
 *
 * Adheres strictly to CODE-4 lifecycle: does NOT touch HardwarePttPort in init;
 * attaches only during active session and detaches cleanly without clobbering third-party callbacks.
 */
class MeshVoxBridge(
    private val transport: VoiceTransport,
    val hardwarePttPort: HardwarePttPort? = null,
    val voxEngine: VoxEngine? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : AutoCloseable {

    private var scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    val voxDetector: VoxDetector = VoxDetector()

    private var ownCallback: ((Boolean) -> Unit)? = null
    private var previousCallback: ((Boolean) -> Unit)? = null
    private var activatedByUs = false
    private var voxJob: Job? = null

    // Standalone fallback controller if transport is not OfflineMeshVoiceTransport (e.g. test fakes)
    private val fallbackController: MicController by lazy {
        MicControllerImpl(
            audioEnabled = { true },
            applyCapture = { _ ->
                val shouldOpen = isMicToggledOn || isPttHeld || (voxEnabled.value && isVoxSpeechActive)
                runCatching { transport.setMicrophoneEnabled(shouldOpen) }
            },
            voxDetector = voxDetector,
            scope = scope,
            initialVoxEnabled = true
        )
    }

    private val micController: MicController
        get() = (transport as? OfflineMeshVoiceTransport)?.micController ?: fallbackController

    private val _isGateOpen = MutableStateFlow(false)

    val micToggleOn: StateFlow<Boolean> get() = micController.micToggleOn
    val pttHeld: StateFlow<Boolean> get() = micController.pttHeld
    val voxEnabled: StateFlow<Boolean> get() = micController.voxEnabled
    val isPttActive: StateFlow<Boolean> get() = micController.pttHeld
    val isGateOpen: StateFlow<Boolean>
        get() {
            _isGateOpen.value = isMicToggledOn || isPttHeld || (voxEnabled.value && isVoxSpeechActive)
            return _isGateOpen.asStateFlow()
        }

    val isMicToggledOn: Boolean get() = micController.micToggleOn.value
    val isPttHeld: Boolean get() = micController.pttHeld.value
    val isVoxSpeechActive: Boolean get() = micController.voxArmed.value

    @Synchronized
    fun attach(roomOwnsAudio: Boolean) {
        if (ownCallback != null) return
        val port = hardwarePttPort ?: return
        previousCallback = port.onMicToggleRequest
        val cb: (Boolean) -> Unit = { _ -> toggleMic() }
        ownCallback = cb
        port.onMicToggleRequest = cb
        if (!roomOwnsAudio) {
            port.activateSession()
            activatedByUs = true
        }

        // Wire optional VoxEngine speech detection if provided
        voxEngine?.let { vox ->
            voxJob?.cancel()
            voxJob = scope.launch {
                vox.isMicOpen.collectLatest { open ->
                    if (open) {
                        micController.setVoxEnabled(true)
                    }
                }
            }
        }
    }

    @Synchronized
    fun detach() {
        val port = hardwarePttPort
        if (port != null && ownCallback != null) {
            if (port.onMicToggleRequest === ownCallback) {
                port.onMicToggleRequest = previousCallback
            }
            if (activatedByUs) {
                port.deactivateSession()
            }
        }
        ownCallback = null
        previousCallback = null
        activatedByUs = false
        voxJob?.cancel()
        voxJob = null
        scope.coroutineContext.cancelChildren()
    }

    /** Direct manual PTT control (e.g. from UI buttons). */
    fun setPttPressed(pressed: Boolean) {
        micController.setPttPressed(pressed)
    }

    /** Toggle microphone state. */
    fun setMicToggled(toggled: Boolean) {
        micController.setMicToggled(toggled)
    }

    /** Flip microphone state (e.g. helmet PTT key). */
    fun toggleMic() {
        micController.toggleMic()
    }

    /** Set whether VOX gate detection is enabled. */
    fun setVoxEnabled(enabled: Boolean) {
        micController.setVoxEnabled(enabled)
    }

    /** Direct update of VOX speech active state (testing or external detector). */
    fun setVoxSpeechActive(active: Boolean) {
        if (active) {
            micController.setVoxEnabled(true)
        }
        micController.setVoxArmed(active)
    }

    /**
     * Inspects a raw PCM frame (640-byte 16 kHz mono 16-bit PCM = 320 samples) and returns
     * true if the frame should be transmitted over the mesh link.
     */
    fun shouldTransmitPcmFrame(pcmBytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean {
        val res = micController.shouldTransmitPcmFrame(pcmBytes, nowMs)
        _isGateOpen.value = isMicToggledOn || isPttHeld || (voxEnabled.value && isVoxSpeechActive)
        return res
    }

    override fun close() {
        detach()
    }
}
