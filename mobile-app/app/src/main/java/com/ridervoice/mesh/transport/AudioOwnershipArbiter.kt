package com.ridervoice.mesh.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioOwner {
    NONE,
    LIVEKIT,
    OFFLINE_MESH
}

/**
 * Enforces strict mutual exclusion over microphone/audio hardware ownership.
 *
 * Rules:
 * - Healthy online session: [AudioOwner.LIVEKIT]
 * - Offline mesh active: [AudioOwner.OFFLINE_MESH]
 * - No active session: [AudioOwner.NONE]
 * - Before offline capture starts: owner must NOT be [AudioOwner.LIVEKIT]
 * - Before LiveKit audio resumes: offline capture must be stopped and released first
 * - Simultaneous ownership is impossible.
 */
class AudioOwnershipArbiter {
    private val _owner = MutableStateFlow(AudioOwner.NONE)
    val owner: StateFlow<AudioOwner> = _owner.asStateFlow()

    @Synchronized
    fun claimLiveKit(onStopOfflineFirst: () -> Unit = {}): Boolean {
        if (_owner.value == AudioOwner.OFFLINE_MESH) {
            onStopOfflineFirst()
            _owner.value = AudioOwner.NONE
        }
        _owner.value = AudioOwner.LIVEKIT
        return true
    }

    @Synchronized
    fun tryClaimOfflineMesh(): Boolean {
        if (_owner.value == AudioOwner.LIVEKIT) {
            return false
        }
        _owner.value = AudioOwner.OFFLINE_MESH
        return true
    }

    @Synchronized
    fun handoffLiveKitToOfflineMesh(onSuspendLiveKitCapture: () -> Unit): Boolean {
        if (_owner.value == AudioOwner.LIVEKIT) {
            onSuspendLiveKitCapture()
            _owner.value = AudioOwner.NONE
        }
        _owner.value = AudioOwner.OFFLINE_MESH
        return true
    }

    @Synchronized
    fun handoffOfflineMeshToLiveKit(
        onStopOfflineCapture: () -> Unit,
        onResumeLiveKitCapture: () -> Unit
    ): Boolean {
        if (_owner.value == AudioOwner.OFFLINE_MESH) {
            onStopOfflineCapture()
            _owner.value = AudioOwner.NONE
        }
        _owner.value = AudioOwner.LIVEKIT
        onResumeLiveKitCapture()
        return true
    }

    @Synchronized
    fun release(expectedOwner: AudioOwner) {
        if (_owner.value == expectedOwner) {
            _owner.value = AudioOwner.NONE
        }
    }

    @Synchronized
    fun releaseAll() {
        _owner.value = AudioOwner.NONE
    }
}

