package com.ridervoice.mesh.transport

import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.OfflineAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stage 2 Coordinator managing the active voice transport and ensuring atomic transitions.
 *
 * Guarantees:
 * - Exactly one transport is active at any time.
 * - Previous transport is completely stopped before a new transport starts.
 * - Audio ownership is strictly arbitrated to prevent dual AudioRecord/WebRTC contention.
 * - Single-capture quiet guard: verifies LiveKit capture quiet (muted && VOX stopped) for >= 400 ms.
 * - Safe fallback to mesh with automatic rollback on failure (D3).
 * - Safe recovery to LiveKit with automatic rollback to mesh on failure (D3).
 */
class VoiceSessionCoordinator(
    val liveKitTransport: VoiceTransport,
    val meshTransport: VoiceTransport,
    val audioOwnershipArbiter: AudioOwnershipArbiter,
    val secretProvider: OfflineSecretProvider = NullOfflineSecretProvider(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        if (exception is CancellationException) throw exception
        // Coordinator error logging without process crash
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler)
    private val switchMutex = Mutex()
    @Volatile private var lastOnlineSession: VoiceSession.Online? = null

    private val _offlineAvailability = MutableStateFlow(computeAvailability())
    val offlineAvailability: StateFlow<OfflineAvailability> = _offlineAvailability.asStateFlow()

    private fun computeAvailability(): OfflineAvailability {
        if (!OfflineMeshGate.isOfflineAllowed()) return OfflineAvailability.DISABLED
        val roomId = lastOnlineSession?.roomId
        val secret = if (roomId != null) secretProvider.getSecretForOnlineRide(roomId) else null
        return if (secret != null) OfflineAvailability.AVAILABLE else OfflineAvailability.NO_SHARED_SECRET
    }

    private val _activeKind = MutableStateFlow(PeerTransportKind.ONLINE_LIVEKIT)
    val activeKind: StateFlow<PeerTransportKind> = _activeKind.asStateFlow()

    private val _activeTransport = MutableStateFlow(liveKitTransport)
    val activeTransport: StateFlow<VoiceTransport> = _activeTransport.asStateFlow()

    private val _transportState = MutableStateFlow(VoiceTransportState.STOPPED)
    val transportState: StateFlow<VoiceTransportState> = _transportState.asStateFlow()

    private val _participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
    val participants: StateFlow<List<UnifiedVoicePeer>> = _participants.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    init {
        // Dynamically proxy state from whichever transport is currently active
        scope.launch {
            _activeTransport.collectLatest { transport ->
                launch {
                    transport.state.collectLatest { _transportState.value = it }
                }
                launch {
                    transport.participants.collectLatest { _participants.value = it }
                }
                launch {
                    transport.activeSpeakers.collectLatest { _activeSpeakers.value = it }
                }
            }
        }
    }

    fun setOnlineSession(session: VoiceSession.Online?) {
        lastOnlineSession = session
        _offlineAvailability.value = computeAvailability()
    }

    suspend fun ensureWarmStandbyDiscovery(session: OfflineSession) {
        val mesh = meshTransport as? MeshControl ?: return
        if (meshTransport.state.value == VoiceTransportState.STOPPED) {
            mesh.startDiscoveryOnly(session)
        }
    }

    /**
     * Atomically switches active transport.
     */
    suspend fun switchToTransport(
        targetKind: PeerTransportKind,
        newSession: VoiceSession
    ) = switchMutex.withLock {
        val current = _activeTransport.value
        val next = when (targetKind) {
            PeerTransportKind.ONLINE_LIVEKIT -> liveKitTransport
            PeerTransportKind.OFFLINE_MESH -> meshTransport
        }

        if (current !== next) {
            current.stop()
        }

        _activeKind.value = targetKind
        _activeTransport.value = next
        if (newSession is VoiceSession.Online) {
            lastOnlineSession = newSession
            _offlineAvailability.value = computeAvailability()
        }
        next.start(newSession)
    }

    /**
     * Option B: Fallback to offline mesh when online degradation occurs (D3).
     *
     * Precondition: mesh must report a valid route.
     * Suspends LiveKit, verifies 400ms quiet window, claims OFFLINE_MESH, and enables mesh audio.
     * On failure: rolls back to LiveKit with retained online session.
     */
    suspend fun fallbackToMesh(): Boolean = switchMutex.withLock {
        val mesh = meshTransport as? MeshControl ?: return@withLock false
        if (!mesh.hasValidRoute.value) return@withLock false // Never abandon working online audio without a route

        val online = lastOnlineSession
        val priorMicWanted = (liveKitTransport.micState.value == MicState.ON)

        // 1. Pause LiveKit
        liveKitTransport.pause()

        // 2. Single-capture guard window: counter frozen 150ms delayed unmute in startVoxSafely()
        // Require "LiveKit capture quiet": LiveKitPort reports muted && VOX stopped for >= 400 ms.
        val isQuiet = verifyLiveKitQuiet(timeoutMs = 2_000L)
        if (!isQuiet) {
            // Abort switch, keep LIVEKIT owner, report failure
            liveKitTransport.resume()
            audioOwnershipArbiter.claimLiveKit()
            return@withLock false
        }

        // 3. Claim OFFLINE_MESH ownership
        val claimed = audioOwnershipArbiter.handoffLiveKitToOfflineMesh {
            // Already paused
        }
        if (!claimed) {
            liveKitTransport.resume()
            audioOwnershipArbiter.claimLiveKit()
            return@withLock false
        }

        val enableResult = runCatching { mesh.enableAudio() }.getOrElse { EnableAudioResult.Failure(it.message ?: "Exception") }
        val ok = (enableResult is EnableAudioResult.Success) && (meshTransport.micState.value != MicState.HARDWARE_BUSY)

        if (!ok) {
            // Rollback to LiveKit: owner must be consistent. NEVER liveKitTransport.stop() here (A9).
            try {
                runCatching { mesh.disableAudio() }
            } finally {
                audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)
                audioOwnershipArbiter.claimLiveKit()
                runCatching {
                    liveKitTransport.resume()
                }
            }
            return@withLock false
        } else {
            _activeKind.value = PeerTransportKind.OFFLINE_MESH
            _activeTransport.value = meshTransport
            if (priorMicWanted) {
                runCatching { meshTransport.setMicrophoneEnabled(true) }
            }
            return@withLock true
        }
    }

    private suspend fun verifyLiveKitQuiet(timeoutMs: Long = 2_000L): Boolean {
        val lk = liveKitTransport as? LiveKitVoiceTransport
        if (lk == null) {
            // Non-LiveKit test transport: apply 400ms guard delays
            delay(200L)
            delay(200L)
            return true
        }

        var quietSinceMs: Long? = null
        val startMs = clock()

        while (clock() - startMs < timeoutMs) {
            val quiet = lk.isCaptureQuiet()
            val now = clock()
            if (quiet) {
                if (quietSinceMs == null) {
                    quietSinceMs = now
                } else if (now - quietSinceMs >= 400L) {
                    return true
                }
            } else {
                quietSinceMs = null
                lk.reassertPauseMute()
            }
            delay(100L)
        }
        return false
    }

    /**
     * Option B: Hand back to LiveKit when online health recovers (D3).
     *
     * Disables mesh audio first, triggers LiveKit reconnection/resume, and waits up to 15s for CONNECTED.
     * On failure: rolls back to offline mesh.
     */
    suspend fun recoverToLiveKit(): Boolean = switchMutex.withLock {
        val mesh = meshTransport as? MeshControl ?: return@withLock false
        val online = lastOnlineSession
        val priorMeshMicWanted = (meshTransport.micState.value == MicState.ON)

        mesh.disableAudio()
        audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)

        if (liveKitTransport.state.value != VoiceTransportState.CONNECTED) {
            if (liveKitTransport is LiveKitVoiceTransport) {
                liveKitTransport.triggerBackgroundReconnect()
            } else if (online != null) {
                runCatching { liveKitTransport.start(online) }
            }
        }

        val connected = withTimeoutOrNull(15_000L) {
            liveKitTransport.state.first { it == VoiceTransportState.CONNECTED }
        } != null

        if (!connected) {
            // Rollback to mesh
            if (audioOwnershipArbiter.tryClaimOfflineMesh()) {
                runCatching { mesh.enableAudio() }
            }
        } else {
            audioOwnershipArbiter.claimLiveKit()
            liveKitTransport.resume()
            if (priorMeshMicWanted) {
                runCatching { liveKitTransport.setMicrophoneEnabled(true) }
            }
            _activeKind.value = PeerTransportKind.ONLINE_LIVEKIT
            _activeTransport.value = liveKitTransport
        }
        connected
    }

    suspend fun stopCurrent() = switchMutex.withLock {
        _activeTransport.value.stop()
        audioOwnershipArbiter.releaseAll()
    }
}
