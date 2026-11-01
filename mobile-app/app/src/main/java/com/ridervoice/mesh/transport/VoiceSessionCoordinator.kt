package com.ridervoice.mesh.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * - Safe fallback to mesh with automatic rollback on failure (D3).
 * - Safe recovery to LiveKit with automatic rollback to mesh on failure (D3).
 */
class VoiceSessionCoordinator(
    val liveKitTransport: VoiceTransport,
    val meshTransport: VoiceTransport,
    val audioOwnershipArbiter: AudioOwnershipArbiter,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val switchMutex = Mutex()
    @Volatile private var lastOnlineSession: VoiceSession.Online? = null

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
        }
        next.start(newSession)
    }

    /**
     * Option B: Fallback to offline mesh when online degradation occurs (D3).
     *
     * Precondition: mesh must report a valid route.
     * Stops LiveKit, claims OFFLINE_MESH, and enables mesh audio.
     * On failure: rolls back to LiveKit with retained online session.
     */
    suspend fun fallbackToMesh(): Boolean = switchMutex.withLock {
        val mesh = meshTransport as? MeshControl ?: return@withLock false
        if (!mesh.hasValidRoute.value) return@withLock false // Never abandon working online audio without a route

        val online = lastOnlineSession
        liveKitTransport.stop()

        val ok = runCatching {
            check(audioOwnershipArbiter.tryClaimOfflineMesh()) { "Failed to claim offline mesh audio owner" }
            mesh.enableAudio()
        }.isSuccess

        if (!ok) {
            // Rollback to LiveKit
            runCatching { mesh.disableAudio() }
            audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)
            if (online != null) {
                runCatching { liveKitTransport.start(online) }
            }
        } else {
            _activeKind.value = PeerTransportKind.OFFLINE_MESH
            _activeTransport.value = meshTransport
        }
        ok
    }

    /**
     * Option B: Hand back to LiveKit when online health recovers (D3).
     *
     * Disables mesh audio first, starts LiveKit, and waits up to 15s for CONNECTED.
     * On failure: rolls back to offline mesh.
     */
    suspend fun recoverToLiveKit(): Boolean = switchMutex.withLock {
        val mesh = meshTransport as? MeshControl ?: return@withLock false
        val online = lastOnlineSession ?: return@withLock false

        mesh.disableAudio()
        audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)

        runCatching { liveKitTransport.start(online) }
        val connected = withTimeoutOrNull(15_000) {
            liveKitTransport.state.first { it == VoiceTransportState.CONNECTED }
        } != null

        if (!connected) {
            // Rollback to mesh
            liveKitTransport.stop()
            if (audioOwnershipArbiter.tryClaimOfflineMesh()) {
                runCatching { mesh.enableAudio() }
            }
        } else {
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
