package com.ridervoice.mesh.transport

import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LiveKitManagerPort(
    private val m: LiveKitManager,
    private val voxStopper: () -> Unit = {}
) : LiveKitPort {
    override val connectionState: StateFlow<ConnectionState> get() = m.connectionState
    override val activeSpeaker: StateFlow<String?> get() = m.activeSpeaker
    override val participants: StateFlow<List<Participant>> get() = m.participants
    override val isUserMuted: StateFlow<Boolean> get() = m.isUserMuted
    override val deafened: StateFlow<Boolean> get() = m.deafened
    override suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?) =
        m.connect(url = url, roomId = roomId, tokenProvider = tokenProvider, expectedLkRoomName = expectedLkRoomName)
    override fun disconnect() = m.disconnect()
    override fun setUserMuted(muted: Boolean) = m.setUserMuted(muted)
    override fun setDeafened(deafened: Boolean) = m.setDeafened(deafened)
    override fun onVoiceGate(open: Boolean) = m.onVoiceGate(open)
    override fun onNetworkRestored() = m.onNetworkRestored()
    override fun stopVox() = voxStopper()
}

/**
 * Stage 2 Adapter wrapping [LiveKitManager] behind the generic [VoiceTransport] contract.
 *
 * NON-NEGOTIABLE CONTRACT:
 * Does NOT rewrite, modify, or replace LiveKitManager in any way.
 * Delegates 100% of online operations directly to LiveKitManager.
 */
class LiveKitVoiceTransport(
    private val liveKitPort: LiveKitPort,
    val audioOwnershipArbiter: AudioOwnershipArbiter,
    private val onAudioRouteChanged: (AudioRoute) -> Unit = {},
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) : VoiceTransport {

    constructor(
        liveKitManager: LiveKitManager,
        audioOwnershipArbiter: AudioOwnershipArbiter,
        voxStopper: () -> Unit = {},
        onAudioRouteChanged: (AudioRoute) -> Unit = {},
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
    ) : this(LiveKitManagerPort(liveKitManager, voxStopper), audioOwnershipArbiter, onAudioRouteChanged, dispatcher)

    constructor(
        liveKitManager: LiveKitManager,
        audioOwnershipArbiter: AudioOwnershipArbiter,
        onAudioRouteChanged: (AudioRoute) -> Unit = {},
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
    ) : this(LiveKitManagerPort(liveKitManager), audioOwnershipArbiter, onAudioRouteChanged, dispatcher)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(VoiceTransportState.STOPPED)
    override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
    override val participants: StateFlow<List<UnifiedVoicePeer>> = _participants.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    override val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    override val audioOwner: StateFlow<AudioOwner> = audioOwnershipArbiter.owner

    private val _micState = MutableStateFlow(MicState.OFF)
    override val micState: StateFlow<MicState> = _micState.asStateFlow()

    private var userMicEnabled = true

    @Volatile private var savedUserMutedBeforePause: Boolean = false
    @Volatile private var savedDeafenedBeforePause: Boolean = false
    @Volatile var pausedByCoordinator: Boolean = false
        private set

    init {
        // Observe connection state
        scope.launch {
            liveKitPort.connectionState.collectLatest { conn ->
                _state.value = when (conn) {
                    ConnectionState.CONNECTED -> VoiceTransportState.CONNECTED
                    ConnectionState.CONNECTING -> VoiceTransportState.STARTING
                    ConnectionState.RECONNECTING -> VoiceTransportState.RECONNECTING
                    ConnectionState.FAILED -> VoiceTransportState.FAILED
                    ConnectionState.DISCONNECTED -> VoiceTransportState.STOPPED
                }

                when (conn) {
                    ConnectionState.CONNECTING,
                    ConnectionState.CONNECTED,
                    ConnectionState.RECONNECTING -> {
                        if (pausedByCoordinator) {
                            // Keep LiveKit silent while offline mesh owns audio
                            liveKitPort.onVoiceGate(false)
                            liveKitPort.setUserMuted(true)
                            liveKitPort.setDeafened(true)
                            liveKitPort.stopVox()
                            if (conn == ConnectionState.CONNECTED) {
                                delay(250L) // Defeat LiveKitManager.startVoxSafely() 150ms delay
                                if (pausedByCoordinator) {
                                    liveKitPort.onVoiceGate(false)
                                    liveKitPort.setUserMuted(true)
                                    liveKitPort.setDeafened(true)
                                    liveKitPort.stopVox()
                                }
                            }
                        } else if (audioOwnershipArbiter.owner.value != AudioOwner.OFFLINE_MESH) {
                            audioOwnershipArbiter.claimLiveKit()
                        }
                    }
                    ConnectionState.DISCONNECTED,
                    ConnectionState.FAILED -> {
                        if (!pausedByCoordinator && audioOwnershipArbiter.owner.value == AudioOwner.LIVEKIT) {
                            audioOwnershipArbiter.release(AudioOwner.LIVEKIT)
                        }
                    }
                }
            }
        }

        // Observe active speaker
        scope.launch {
            liveKitPort.activeSpeaker.collectLatest { speaker ->
                _activeSpeakers.value = if (speaker != null) setOf(speaker) else emptySet()
            }
        }

        // Observe participants and project to UnifiedVoicePeer
        scope.launch {
            liveKitPort.participants.collectLatest { lkParticipants ->
                val speaker = liveKitPort.activeSpeaker.value
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
        liveKitPort.connect(
            url = onlineSession.url,
            roomId = onlineSession.roomId,
            tokenProvider = onlineSession.tokenProvider,
            expectedLkRoomName = onlineSession.expectedLkRoomName
        )
    }

    override suspend fun stop() {
        pausedByCoordinator = false
        liveKitPort.disconnect()
        audioOwnershipArbiter.release(AudioOwner.LIVEKIT)
        _micState.value = MicState.OFF
        _state.value = VoiceTransportState.STOPPED
    }

    override suspend fun pause() {
        if (pausedByCoordinator) return
        savedUserMutedBeforePause = !userMicEnabled || liveKitPort.isUserMuted.value
        savedDeafenedBeforePause = liveKitPort.deafened.value
        pausedByCoordinator = true
        _micState.value = MicState.MUTED
        liveKitPort.onVoiceGate(false)
        liveKitPort.setDeafened(true)
        liveKitPort.stopVox()
        liveKitPort.setUserMuted(true)
    }

    override suspend fun resume() {
        if (!pausedByCoordinator) return
        pausedByCoordinator = false
        val restoreMuted = savedUserMutedBeforePause
        val restoreDeafened = savedDeafenedBeforePause
        userMicEnabled = !restoreMuted
        _micState.value = if (userMicEnabled) MicState.ON else MicState.OFF
        liveKitPort.setDeafened(restoreDeafened)
        liveKitPort.setUserMuted(restoreMuted)
    }

    fun triggerBackgroundReconnect() {
        liveKitPort.onNetworkRestored()
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        userMicEnabled = enabled
        _micState.value = if (enabled) MicState.ON else MicState.OFF
        if (!pausedByCoordinator) {
            liveKitPort.setUserMuted(!enabled)
        }
    }

    override suspend fun setDeafened(deafened: Boolean) {
        liveKitPort.setDeafened(deafened)
    }

    override suspend fun setAudioRoute(route: AudioRoute) {
        onAudioRouteChanged(route)
    }
}
