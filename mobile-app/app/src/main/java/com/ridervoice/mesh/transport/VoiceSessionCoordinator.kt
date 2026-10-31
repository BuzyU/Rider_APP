package com.ridervoice.mesh.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Stage 2 Coordinator managing the active voice transport and ensuring atomic transitions.
 *
 * Guarantees:
 * - Exactly one transport is active at any time.
 * - Previous transport is completely stopped before a new transport starts.
 * - Audio ownership is strictly arbitrated to prevent dual AudioRecord/WebRTC contention.
 */
class VoiceSessionCoordinator(
    val liveKitTransport: VoiceTransport,
    val meshTransport: VoiceTransport,
    val audioOwnershipArbiter: AudioOwnershipArbiter,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

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
    ) {
        val current = _activeTransport.value
        current.stop()

        val next = when (targetKind) {
            PeerTransportKind.ONLINE_LIVEKIT -> liveKitTransport
            PeerTransportKind.OFFLINE_MESH -> meshTransport
        }

        _activeKind.value = targetKind
        _activeTransport.value = next
        next.start(newSession)
    }

    suspend fun stopCurrent() {
        _activeTransport.value.stop()
        audioOwnershipArbiter.releaseAll()
    }
}

