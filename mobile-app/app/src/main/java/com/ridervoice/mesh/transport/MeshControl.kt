package com.ridervoice.mesh.transport

import com.ridervoice.mesh.session.OfflineSession
import kotlinx.coroutines.flow.StateFlow

sealed interface EnableAudioResult {
    object Success : EnableAudioResult
    data class Failure(val reason: String) : EnableAudioResult {
        companion object {
            val HardwareBusy = Failure("HARDWARE_BUSY")
            val GateClosed = Failure("GATE_CLOSED")
            val OwnershipDenied = Failure("OWNERSHIP_DENIED")
            val RecordInitFailed = Failure("RECORD_INIT_FAILED")
        }
    }
}

/**
 * Control interface for offline mesh lifecycle separation (D3).
 *
 * Allows starting background discovery/routing without capturing or playing audio,
 * and dynamically enabling or disabling audio hardware according to arbitration.
 */
interface MeshControl {
    /** Whether the mesh currently has at least one valid multi-hop or direct route. */
    val hasValidRoute: StateFlow<Boolean>

    /**
     * Starts UDP socket, heartbeat, and topology discovery only.
     * Does NOT allocate AudioRecord or AudioTrack, and does NOT claim audio hardware.
     */
    suspend fun startDiscoveryOnly(session: OfflineSession)

    /**
     * Claims audio ownership from the arbiter and starts playback and mic capture.
     * Returns a sealed [EnableAudioResult] instead of throwing (V15).
     */
    suspend fun enableAudio(): EnableAudioResult

    /** Stops mic capture first, then playback, and releases audio ownership. */
    suspend fun disableAudio()
}
