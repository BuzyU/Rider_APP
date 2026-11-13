package com.ridervoice.mesh.transport

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.audio.MicController
import com.ridervoice.mesh.session.OfflineSession
import kotlinx.coroutines.flow.StateFlow

enum class MeshCadence(val intervalMs: Long) {
    STANDBY(MeshConfig.STANDBY_HELLO_INTERVAL_MS), // 5,000 ms: warm standby while LiveKit is healthy
    ACTIVE(MeshConfig.HELLO_INTERVAL_MS),          // 1,500 ms: mesh owns audio
    BOOST(MeshConfig.BOOST_HELLO_INTERVAL_MS)      // 750 ms: transient initial discovery upon switch or trigger
}

sealed interface EnableAudioResult {
    object Success : EnableAudioResult
    data class Failure(val code: Code, val detail: String? = null) : EnableAudioResult {
        enum class Code {
            HARDWARE_BUSY,
            GATE_CLOSED,
            OWNERSHIP_DENIED,
            NOT_CONNECTED,
            NOT_INITIALIZED,
            PLAYBACK_START_FAILED,
            UNEXPECTED
        }
        companion object {
            val HardwareBusy = Failure(Code.HARDWARE_BUSY)
            val GateClosed = Failure(Code.GATE_CLOSED)
            val OwnershipDenied = Failure(Code.OWNERSHIP_DENIED)
        }
    }
}

/**
 * Control interface for offline mesh lifecycle separation.
 *
 * Allows starting background discovery/routing without capturing or playing audio,
 * and dynamically enabling or disabling audio hardware according to arbitration.
 */
interface MeshControl {
    /** Whether the mesh currently has at least one valid multi-hop or direct route. */
    val hasValidRoute: StateFlow<Boolean>

    /** Whether the underlying mesh network link is currently active/running. */
    val isLinkAlive: StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** Sets the periodic discovery HELLO beacon interval. */
    fun setHelloIntervalMs(intervalMs: Long) {}

    /** Sets the periodic discovery HELLO beacon cadence via unified MeshCadence state. */
    fun setCadence(cadence: MeshCadence) {
        setHelloIntervalMs(cadence.intervalMs)
    }

    /** Optional mic controller for the mesh audio subsystem. */
    val micController: MicController? get() = null

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

    /** Pauses offline AudioRecord capture while keeping playback active (no dead air during recovery). */
    suspend fun pauseCaptureOnly()

    /** Resumes offline AudioRecord capture if recovery to LiveKit fails or times out. */
    suspend fun resumeCaptureOnly()
}
