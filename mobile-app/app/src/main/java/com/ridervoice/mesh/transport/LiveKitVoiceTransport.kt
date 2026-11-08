package com.ridervoice.mesh.transport

import com.ridervoice.models.Participant
import com.ridervoice.mesh.GateDecision
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class LiveKitManagerPort(
    private val m: LiveKitManager,
    private val voxStopper: () -> Unit = {},
    private val voxRestarter: () -> Unit = {},
    private val voxRunningCheck: (() -> Boolean)? = null
) : LiveKitPort {
    @Volatile private var voxStoppedByUs = false

    // LiveKitManager starts VOX on every successful connect (when open-mic setting is enabled) and stops it on disconnect.
    override val isVoxRunning: Boolean
        get() = (voxRunningCheck?.invoke() ?: (m.connectionState.value == ConnectionState.CONNECTED)) && !voxStoppedByUs

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
    override fun stopVox() {
        voxStoppedByUs = true
        voxStopper()
    }
    override fun restartVoxIfPreviouslyRunning() {
        voxStoppedByUs = false
        if (m.connectionState.value == ConnectionState.CONNECTED) {
            voxRestarter() // never start VOX while disconnected
        }
    }
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
    private val gateAllowed: () -> Boolean = { OfflineMeshGate.isOfflineAllowed() },
    private val settingsFlow: StateFlow<MeshSettingsSnapshot>? = null,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) : VoiceTransport {

    constructor(
        liveKitManager: LiveKitManager,
        audioOwnershipArbiter: AudioOwnershipArbiter,
        voxStopper: () -> Unit = {},
        voxRestarter: () -> Unit = {},
        voxRunningCheck: (() -> Boolean)? = null,
        onAudioRouteChanged: (AudioRoute) -> Unit = {},
        gateAllowed: () -> Boolean = { OfflineMeshGate.isOfflineAllowed() },
        settingsFlow: StateFlow<MeshSettingsSnapshot>? = OfflineMeshGate.settingsFlow,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
    ) : this(
        LiveKitManagerPort(liveKitManager, voxStopper, voxRestarter, voxRunningCheck),
        audioOwnershipArbiter,
        onAudioRouteChanged,
        gateAllowed,
        settingsFlow,
        dispatcher
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var collectorParentJob: Job? = null

    private val _state = MutableStateFlow(VoiceTransportState.STOPPED)
    override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
    override val participants: StateFlow<List<UnifiedVoicePeer>> = _participants.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    override val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    override val audioOwner: StateFlow<AudioOwner> = audioOwnershipArbiter.owner

    private val _micState = MutableStateFlow(MicState.OFF)
    override val micState: StateFlow<MicState> = _micState.asStateFlow()

    val deafened: StateFlow<Boolean> get() = liveKitPort.deafened

    private var userMicEnabled = false
    @Volatile private var explicitUserMicEnabled: Boolean? = null
    @Volatile private var explicitMicOverrideWhilePaused: Boolean? = null
    private val reconnectHistory = ArrayDeque<Long>()
    private val _reconnectAttempts = MutableStateFlow(0)
    val reconnectAttempts: StateFlow<Int> = _reconnectAttempts.asStateFlow()

    @Volatile private var savedUserMutedBeforePause: Boolean = false
    @Volatile private var savedDeafenedBeforePause: Boolean = false
    @Volatile var savedVoxRunningBeforePause: Boolean = false
        private set
    val isVoxRunning: Boolean
        get() = liveKitPort.isVoxRunning
    @Volatile var pausedByCoordinator: Boolean = false
        private set

    init {
        if (settingsFlow != null) {
            if (OfflineMeshGate.isBuildGateOpen()) {
                scope.launch {
                    settingsFlow.collectLatest {
                        if (gateAllowed()) {
                            attachCollectors()
                        } else {
                            detachCollectors()
                        }
                    }
                }
            }
        } else if (gateAllowed()) {
            attachCollectors()
        }
    }

    private fun detachCollectors() {
        collectorParentJob?.cancel()
        collectorParentJob = null
        reconnectHistory.clear()
        _reconnectAttempts.value = 0
    }

    private fun attachCollectors() {
        if (collectorParentJob?.isActive == true) return
        val parent = SupervisorJob(scope.coroutineContext[Job])
        collectorParentJob = parent
        val collectorScope = CoroutineScope(scope.coroutineContext + parent)

        // Observe connection state
        collectorScope.launch {
            liveKitPort.connectionState.collectLatest { conn ->
                _state.value = when (conn) {
                    ConnectionState.CONNECTED -> VoiceTransportState.CONNECTED
                    ConnectionState.CONNECTING -> VoiceTransportState.STARTING
                    ConnectionState.RECONNECTING -> {
                        val now = System.currentTimeMillis()
                        reconnectHistory.addLast(now)
                        while (reconnectHistory.isNotEmpty() && now - reconnectHistory.first() > 60_000L) {
                            reconnectHistory.removeFirst()
                        }
                        _reconnectAttempts.value = reconnectHistory.size
                        VoiceTransportState.RECONNECTING
                    }
                    ConnectionState.FAILED -> VoiceTransportState.FAILED
                    ConnectionState.DISCONNECTED -> VoiceTransportState.STOPPED
                }

                when (conn) {
                    ConnectionState.CONNECTING,
                    ConnectionState.CONNECTED,
                    ConnectionState.RECONNECTING -> {
                        if (pausedByCoordinator) {
                            // Keep LiveKit silent while offline mesh owns audio
                            reassertPauseMute()
                            if (conn == ConnectionState.CONNECTED) {
                                delay(250L) // Defeat LiveKitManager.startVoxSafely() 150ms delay
                                if (pausedByCoordinator) {
                                    reassertPauseMute()
                                }
                            }
                        } else {
                            // Never call liveKitPort.setUserMuted(!userMicEnabled) during normal online operation!
                            // LiveKitManager is authoritative for online mute state unless paused by coordinator.
                            if (audioOwnershipArbiter.owner.value != AudioOwner.OFFLINE_MESH) {
                                audioOwnershipArbiter.claimLiveKit()
                            }
                        }
                    }
                    ConnectionState.DISCONNECTED,
                    ConnectionState.FAILED -> {
                        if (!pausedByCoordinator) {
                            _micState.value = MicState.OFF
                            if (audioOwnershipArbiter.owner.value == AudioOwner.LIVEKIT) {
                                audioOwnershipArbiter.release(AudioOwner.LIVEKIT)
                            }
                        }
                    }
                }
            }
        }

        // Observe port mute changes so user mutes in RoomScreen stay synchronized with userMicEnabled and micState
        collectorScope.launch {
            liveKitPort.isUserMuted.drop(1).collectLatest { isMuted ->
                if (!pausedByCoordinator) {
                    userMicEnabled = !isMuted
                    explicitUserMicEnabled = !isMuted
                    updateMicStateFromPort(liveKitPort.connectionState.value, isMuted)
                }
            }
        }

        // Observe active speaker
        collectorScope.launch {
            liveKitPort.activeSpeaker.collectLatest { speaker ->
                _activeSpeakers.value = if (speaker != null) setOf(speaker) else emptySet()
            }
        }

        // Observe participants and project to UnifiedVoicePeer
        collectorScope.launch {
            combine(liveKitPort.participants, liveKitPort.activeSpeaker) { lkParticipants, speaker ->
                lkParticipants.map { p ->
                    UnifiedVoicePeer.fromOnline(
                        OnlineParticipant(
                            identity = p.identity,
                            displayName = p.displayName ?: p.identity,
                            isSpeaking = p.identity == speaker,
                            isConnected = !p.isGhost
                        )
                    )
                }
            }.collectLatest { peers ->
                _participants.value = peers
            }
        }
    }

    private fun updateMicStateFromPort(conn: ConnectionState, isMuted: Boolean) {
        _micState.value = when (conn) {
            ConnectionState.CONNECTED -> if (isMuted) MicState.MUTED else MicState.ON
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING -> if (isMuted) MicState.MUTED else MicState.STARTING
            ConnectionState.DISCONNECTED,
            ConnectionState.FAILED -> MicState.OFF
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
        resetCoordinatorPauseOnRideEnd()
        liveKitPort.disconnect()
        audioOwnershipArbiter.release(AudioOwner.LIVEKIT)
        _micState.value = MicState.OFF
        _state.value = VoiceTransportState.STOPPED
    }

    override suspend fun pause() {
        if (pausedByCoordinator) return
        // Order: record state -> stopVox -> setDeafened(true) -> setUserMuted(true)
        savedUserMutedBeforePause = explicitUserMicEnabled?.let { !it } ?: liveKitPort.isUserMuted.value
        savedDeafenedBeforePause = liveKitPort.deafened.value
        savedVoxRunningBeforePause = liveKitPort.isVoxRunning
        explicitMicOverrideWhilePaused = null
        pausedByCoordinator = true
        _micState.value = MicState.MUTED
        liveKitPort.onVoiceGate(false)
        liveKitPort.stopVox()
        liveKitPort.setDeafened(true)
        liveKitPort.setUserMuted(true)
    }

    override suspend fun resume() {
        if (!pausedByCoordinator) return
        pausedByCoordinator = false
        // Order: reverse of pause
        val overrideEnabled = explicitMicOverrideWhilePaused
        explicitMicOverrideWhilePaused = null
        val restoreMuted = overrideEnabled?.let { !it } ?: savedUserMutedBeforePause
        val restoreDeafened = savedDeafenedBeforePause
        val restoreVox = savedVoxRunningBeforePause
        userMicEnabled = !restoreMuted
        explicitUserMicEnabled = !restoreMuted
        _micState.value = if (userMicEnabled) MicState.ON else MicState.OFF
        if (restoreVox) {
            liveKitPort.restartVoxIfPreviouslyRunning()
        }
        liveKitPort.setDeafened(restoreDeafened)
        liveKitPort.setUserMuted(restoreMuted)
    }

    fun resetCoordinatorPauseOnRideEnd() {
        pausedByCoordinator = false
        savedVoxRunningBeforePause = false
        explicitMicOverrideWhilePaused = null
        explicitUserMicEnabled = null
    }

    fun close() {
        detachCollectors()
        scope.cancel()
    }

    fun isCaptureQuiet(): Boolean {
        val isMuted = liveKitPort.isUserMuted.value || (explicitUserMicEnabled == false)
        val isVoxStopped = !liveKitPort.isVoxRunning
        return isMuted && isVoxStopped
    }

    fun reassertPauseMute() {
        if (pausedByCoordinator) {
            liveKitPort.onVoiceGate(false)
            liveKitPort.stopVox()
            liveKitPort.setDeafened(true)
            liveKitPort.setUserMuted(true)
        }
    }

    fun triggerBackgroundReconnect() {
        liveKitPort.onNetworkRestored()
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        userMicEnabled = enabled
        explicitUserMicEnabled = enabled
        _micState.value = if (enabled) MicState.ON else MicState.OFF
        if (pausedByCoordinator) {
            explicitMicOverrideWhilePaused = enabled
        } else {
            liveKitPort.setUserMuted(!enabled)
        }
    }

    override suspend fun setDeafened(deafened: Boolean) {
        if (pausedByCoordinator) {
            savedDeafenedBeforePause = deafened
        } else {
            liveKitPort.setDeafened(deafened)
        }
    }

    override suspend fun setAudioRoute(route: AudioRoute) {
        onAudioRouteChanged(route)
    }
}

