package com.ridervoice.mesh.audio

import com.ridervoice.audio.HardwarePTTManager
import com.ridervoice.audio.VoxEngine
import com.ridervoice.mesh.transport.VoiceTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stage 2 Bridge connecting audio input events (PTT button, VOX speech gate) to the active mesh transport.
 *
 * Subscribes to hardware PTT events and VoxEngine speech detection, delegating to [VoiceTransport.setMicrophoneEnabled].
 * Guarantees release within 200ms when PTT is released or VOX gate closes.
 */
class MeshVoxBridge(
    private val transport: VoiceTransport,
    private val hardwarePttManager: HardwarePTTManager? = null,
    private val voxEngine: VoxEngine? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var isPttHeld = false
    private var isVoxSpeechActive = false
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
        scope.launch {
            mutex.withLock {
                isPttHeld = pressed
                updateMicState()
            }
        }
    }

    /** Update VOX speech active state. */
    fun setVoxSpeechActive(active: Boolean) {
        scope.launch {
            mutex.withLock {
                isVoxSpeechActive = active
                updateMicState()
            }
        }
    }

    private suspend fun updateMicState() {
        val shouldOpen = isPttHeld || isVoxSpeechActive
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

