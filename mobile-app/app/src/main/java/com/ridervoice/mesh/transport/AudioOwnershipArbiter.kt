package com.ridervoice.mesh.transport

import com.ridervoice.mesh.util.runCatchingCancellable
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
 * Coroutine-safe via [Mutex].
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

    @Volatile
    private var pendingReset: Boolean = false

    private fun applyPendingResetLocked() {
        if (pendingReset) {
            pendingReset = false
            _owner.value = AudioOwner.NONE
        }
    }

    suspend fun claimLiveKit(onStopOfflineFirst: suspend () -> Unit = {}): Boolean = mutex.withLock {
        applyPendingResetLocked()
        if (_owner.value == AudioOwner.OFFLINE_MESH) {
            if (runCatchingCancellable { onStopOfflineFirst() }.isFailure) return@withLock false
            _owner.value = AudioOwner.NONE
        }
        _owner.value = AudioOwner.LIVEKIT
        applyPendingResetLocked()
        _owner.value == AudioOwner.LIVEKIT
    }

    suspend fun tryClaimOfflineMesh(): Boolean = mutex.withLock {
        applyPendingResetLocked()
        if (_owner.value == AudioOwner.LIVEKIT) {
            return@withLock false
        }
        _owner.value = AudioOwner.OFFLINE_MESH
        applyPendingResetLocked()
        _owner.value == AudioOwner.OFFLINE_MESH
    }

    suspend fun handoffLiveKitToOfflineMesh(onSuspendLiveKitCapture: suspend () -> Unit): Boolean = mutex.withLock {
        applyPendingResetLocked()
        if (_owner.value != AudioOwner.OFFLINE_MESH) {
            if (runCatchingCancellable { onSuspendLiveKitCapture() }.isFailure) return@withLock false
            _owner.value = AudioOwner.NONE
        }
        _owner.value = AudioOwner.OFFLINE_MESH
        applyPendingResetLocked()
        _owner.value == AudioOwner.OFFLINE_MESH
    }

    suspend fun handoffOfflineMeshToLiveKit(
        onStopOfflineCapture: suspend () -> Unit,
        onResumeLiveKitCapture: suspend () -> Unit
    ): Boolean = mutex.withLock {
        applyPendingResetLocked()
        if (_owner.value != AudioOwner.LIVEKIT) {
            if (runCatchingCancellable { onStopOfflineCapture() }.isFailure) return@withLock false
            _owner.value = AudioOwner.NONE
        }
        if (runCatchingCancellable { onResumeLiveKitCapture() }.isFailure) {
            _owner.value = AudioOwner.OFFLINE_MESH
            return@withLock false
        }
        _owner.value = AudioOwner.LIVEKIT
        applyPendingResetLocked()
        _owner.value == AudioOwner.LIVEKIT
    }

    suspend fun release(expectedOwner: AudioOwner) = mutex.withLock {
        applyPendingResetLocked()
        if (_owner.value == expectedOwner) {
            _owner.value = AudioOwner.NONE
        }
    }

    suspend fun releaseAll() = mutex.withLock {
        pendingReset = false
        _owner.value = AudioOwner.NONE
    }

    fun releaseSync(expectedOwner: AudioOwner) {
        if (mutex.tryLock()) {
            try {
                applyPendingResetLocked()
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
                pendingReset = false
                _owner.value = AudioOwner.NONE
            } finally {
                mutex.unlock()
            }
        } else {
            pendingReset = true
        }
    }
}
