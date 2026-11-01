package com.ridervoice.mesh.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AudioOwner {
    NONE,
    LIVEKIT,
    OFFLINE_MESH
}

/**
 * Enforces strict mutual exclusion over microphone/audio hardware ownership.
 * Coroutine-safe via [Mutex] (D3).
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
    private val mutex = Mutex()
    private val _owner = MutableStateFlow(AudioOwner.NONE)
    val owner: StateFlow<AudioOwner> = _owner.asStateFlow()

    suspend fun claimLiveKit(onStopOfflineFirst: suspend () -> Unit = {}): Boolean = mutex.withLock {
        if (_owner.value == AudioOwner.OFFLINE_MESH) {
            onStopOfflineFirst()
            _owner.value = AudioOwner.NONE
        }
        _owner.value = AudioOwner.LIVEKIT
        true
    }

    suspend fun tryClaimOfflineMesh(): Boolean = mutex.withLock {
        if (_owner.value == AudioOwner.LIVEKIT) {
            return@withLock false
        }
        _owner.value = AudioOwner.OFFLINE_MESH
        true
    }

    suspend fun handoffLiveKitToOfflineMesh(onSuspendLiveKitCapture: suspend () -> Unit): Boolean = mutex.withLock {
        if (_owner.value == AudioOwner.LIVEKIT) {
            onSuspendLiveKitCapture()
            _owner.value = AudioOwner.NONE
        }
        _owner.value = AudioOwner.OFFLINE_MESH
        true
    }

    suspend fun handoffOfflineMeshToLiveKit(
        onStopOfflineCapture: suspend () -> Unit,
        onResumeLiveKitCapture: suspend () -> Unit
    ): Boolean = mutex.withLock {
        if (_owner.value == AudioOwner.OFFLINE_MESH) {
            onStopOfflineCapture()
            _owner.value = AudioOwner.NONE
        }
        _owner.value = AudioOwner.LIVEKIT
        onResumeLiveKitCapture()
        true
    }

    suspend fun release(expectedOwner: AudioOwner) = mutex.withLock {
        if (_owner.value == expectedOwner) {
            _owner.value = AudioOwner.NONE
        }
    }

    suspend fun releaseAll() = mutex.withLock {
        _owner.value = AudioOwner.NONE
    }

    fun releaseSync(expectedOwner: AudioOwner) {
        if (mutex.tryLock()) {
            try {
                if (_owner.value == expectedOwner) {
                    _owner.value = AudioOwner.NONE
                }
            } finally {
                mutex.unlock()
            }
        } else {
            _owner.compareAndSet(expectedOwner, AudioOwner.NONE)
        }
    }

    fun releaseAllSync() {
        if (mutex.tryLock()) {
            try {
                _owner.value = AudioOwner.NONE
            } finally {
                mutex.unlock()
            }
        } else {
            _owner.value = AudioOwner.NONE
        }
    }
}
