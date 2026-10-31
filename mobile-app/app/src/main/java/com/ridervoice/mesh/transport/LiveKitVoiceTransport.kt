package com.ridervoice.mesh.transport

import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Stage 2 Adapter wrapping [LiveKitManager] behind the generic [VoiceTransport] contract.
 *
 * NON-NEGOTIABLE CONTRACT:
 * Does NOT rewrite, modify, or replace LiveKitManager in any way.
 * Delegates 100% of online operations directly to LiveKitManager.
 */
class LiveKitVoiceTransport(
    private val liveKitManager: LiveKitManager,
    private val audioOwnershipArbiter: AudioOwnershipArbiter,
    private val onAudioRouteChanged: (AudioRoute) -> Unit = {},
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) : VoiceTransport {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(VoiceTransportState.STOPPED)
    override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
    override val participants: StateFlow<List<UnifiedVoicePeer>> = _participants.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    override val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    override val audioOwner: StateFlow<AudioOwner> = audioOwnershipArbiter.owner

    init {
        // Observe connection state
        scope.launch {
            liveKitManager.connectionState.collectLatest { conn ->
                _state.value = when (conn) {
                    ConnectionState.CONNECTED -> VoiceTransportState.CONNECTED
                    ConnectionState.CONNECTING -> VoiceTransportState.STARTING
                    ConnectionState.RECONNECTING -> VoiceTransportState.RECONNECTING
                    ConnectionState.FAILED -> VoiceTransportState.FAILED
                    ConnectionState.DISCONNECTED -> VoiceTransportState.STOPPED
                }
            }
        }

        // Observe active speaker
        scope.launch {
            liveKitManager.activeSpeaker.collectLatest { speaker ->
                _activeSpeakers.value = if (speaker != null) setOf(speaker) else emptySet()
            }
        }

        // Observe participants and project to UnifiedVoicePeer
        scope.launch {
            liveKitManager.participants.collectLatest { lkParticipants ->
                val speaker = liveKitManager.activeSpeaker.value
                _participants.value = lkParticipants.map { p ->
                    UnifiedVoicePeer.fromOnline(
                        OnlineParticipant(
                            identity = p.identity,
                            displayName = p.displayName ?: p.identity,
                            isSpeaking = p.identity == speaker,
                            isConnected = !p.isGhost
                        )
                    )
                }
            }
        }
    }

    override suspend fun start(session: VoiceSession) {
        val onlineSession = when (session) {
            is VoiceSession.Online -> session
            is VoiceSession.Offline -> throw IllegalArgumentException("LiveKitVoiceTransport cannot start an offline session")
        }

        audioOwnershipArbiter.claimLiveKit()
        liveKitManager.connect(
            url = onlineSession.url,
            roomId = onlineSession.roomId,
            tokenProvider = onlineSession.tokenProvider,
            expectedLkRoomName = onlineSession.expectedLkRoomName
        )
    }

    override suspend fun stop() {
        liveKitManager.disconnect()
        audioOwnershipArbiter.release(AudioOwner.LIVEKIT)
        _state.value = VoiceTransportState.STOPPED
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        liveKitManager.setUserMuted(!enabled)
    }

    override suspend fun setDeafened(deafened: Boolean) {
        liveKitManager.setDeafened(deafened)
    }

    override suspend fun setAudioRoute(route: AudioRoute) {
        onAudioRouteChanged(route)
    }
}

