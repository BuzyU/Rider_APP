package com.ridervoice.mesh.switching

import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.OnlineParticipant
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.utils.Constants
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MeshAutoSwitchManager(
    private val activeRideRepository: ActiveRideRepository,
    private val liveKitManager: LiveKitManager,
    private val coordinator: VoiceSessionCoordinator,
    private val meshTransport: OfflineMeshVoiceTransport,
    private val healthSignalsProvider: LocalHealthSignalsProvider,
    private val secretProvider: OfflineSecretProvider = NullOfflineSecretProvider(),
    private val clock: Clock = SystemClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val isStandaloneSessionActive: () -> Boolean = { false }
) {

    private var scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _lastTransitionReason = MutableStateFlow<String?>(null)
    val lastTransitionReason: StateFlow<String?> = _lastTransitionReason.asStateFlow()

    private val _groupStatus = MutableStateFlow(GroupConnectivityStatus.ONLINE_ALL)
    val groupStatus: StateFlow<GroupConnectivityStatus> = _groupStatus.asStateFlow()

    val offlineAvailability: StateFlow<OfflineAvailability> = coordinator.offlineAvailability

    val stateMachine: AutomaticSwitchStateMachine = LocalHealthSignalsProvider.createMachine(
        coordinator = coordinator,
        clock = clock,
        onEmitTransitionDiagnostic = { from, to, reason ->
            _lastTransitionReason.value = "$from -> $to ($reason)"
        },
        // hasValidRoute is the transport's own live StateFlow; no separate probe needed
        onProbeMesh = null
    )

    val autoSwitchState: StateFlow<AutoSwitchState> = stateMachine.state

    private var drivingJob: Job? = null

    fun start() {
        if (!OfflineMeshGate.isBuildGateOpen() || OfflineMeshGate.decision() == com.ridervoice.mesh.GateDecision.DISABLED_BY_BUILD) return
        if (drivingJob?.isActive == true) return
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        }

        drivingJob = scope.launch {
            var currentLiveRideId: String? = null

            combine(
                activeRideRepository.state,
                OfflineMeshGate.settingsFlow
            ) { ride, _ -> ride }.collectLatest { ride ->
                if (ride == null || ride.phase != ActiveRideRepository.Phase.LIVE) {
                    // Phase.LOBBY / Phase.ENDING / null -> endSession, stop ticks, stop discovery, restore online transport
                    if (currentLiveRideId != null) {
                        stateMachine.endSession()
                        currentLiveRideId = null
                    }
                    coordinator.setOnlineSession(null)
                    _groupStatus.value = GroupConnectivityStatus.ONLINE_ALL
                    coordinator.onOnlineRideEnded(keepStandaloneMesh = isStandaloneSessionActive())
                    return@collectLatest
                }

                // Phase.LIVE (new rideId) -> beginSession, start evaluation ticks
                if (currentLiveRideId != ride.roomId) {
                    currentLiveRideId = ride.roomId
                    stateMachine.beginSession(ride.roomId, clock.nowMs())
                }
                // Phase.LIVE (same rideId) -> continue

                coordinator.setOnlineSession(
                    VoiceSession.Online(
                        url = ride.livekitUrl?.takeIf { it.isNotBlank() } ?: Constants.LIVEKIT_URL,
                        roomId = ride.roomId,
                        tokenProvider = { activeRideRepository.ensureFreshToken().getOrThrow() },
                        expectedLkRoomName = ride.lkRoomName
                    )
                )

                while (isActive &&
                    activeRideRepository.state.value?.roomId == ride.roomId &&
                    activeRideRepository.state.value?.phase == ActiveRideRepository.Phase.LIVE &&
                    OfflineMeshGate.isOfflineAllowed()
                ) {
                    val backup = secretProvider.getSessionForOnlineRide(ride.roomId)
                    if ((OfflineMeshGate.isWarmStandbyAllowed() || OfflineMeshGate.isAutomaticFallbackAllowed()) &&
                        backup != null &&
                        meshTransport.state.value == VoiceTransportState.STOPPED
                    ) {
                        coordinator.ensureWarmStandbyDiscovery(backup)
                    }
                    if (backup == null && meshTransport.state.value == VoiceTransportState.CONNECTED &&
                        stateMachine.state.value == AutoSwitchState.ONLINE &&
                        !isStandaloneSessionActive()
                    ) {
                        meshTransport.stop()
                    }

                    val signals = healthSignalsProvider.sampleSignals()

                    if (stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE &&
                        signals.shouldTriggerBackgroundReconnect()
                    ) {
                        (coordinator.liveKitTransport as? LiveKitVoiceTransport)?.triggerBackgroundReconnect()
                    }

                    val offlineAvailable = coordinator.offlineAvailability.value == OfflineAvailability.AVAILABLE
                    stateMachine.evaluate(signals, offlineAvailable = offlineAvailable)

                    val isLocalOffline = stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE
                    val unifiedPeers = buildUnifiedParticipantList(isLocalOffline)
                    _groupStatus.value = computeGroupConnectivity(isLocalOffline, unifiedPeers)

                    delay(1_000L)
                }

                if (!OfflineMeshGate.isOfflineAllowed()) {
                    if (currentLiveRideId != null) {
                        stateMachine.endSession()
                        currentLiveRideId = null
                    }
                    _groupStatus.value = GroupConnectivityStatus.ONLINE_ALL
                    if (meshTransport.state.value != VoiceTransportState.STOPPED && !isStandaloneSessionActive()) {
                        meshTransport.stop()
                    }
                    coordinator.onOnlineRideEnded(keepStandaloneMesh = false)
                } else if (meshTransport.state.value != VoiceTransportState.STOPPED &&
                    stateMachine.state.value == AutoSwitchState.ONLINE &&
                    !isStandaloneSessionActive()
                ) {
                    meshTransport.stop()
                }
            }
        }
    }

    private fun buildUnifiedParticipantList(isLocalOffline: Boolean): List<UnifiedVoicePeer> {
        val rawMesh = meshTransport.participants.value
        val meshPeers = (rawMesh as? List<*>)?.filterIsInstance<UnifiedVoicePeer>() ?: emptyList()
        if (isLocalOffline) {
            return meshPeers
        }
        val rawLk = liveKitManager.participants.value
        val lkParticipants = (rawLk as? List<*>)?.filterIsInstance<com.ridervoice.models.Participant>() ?: emptyList()
        val lkPeers = lkParticipants.map { p ->
            UnifiedVoicePeer.fromOnline(
                OnlineParticipant(
                    identity = p.identity,
                    displayName = p.displayName?.takeIf { it.isNotBlank() } ?: p.identity,
                    isSpeaking = liveKitManager.activeSpeaker.value == p.identity,
                    isConnected = !p.isGhost
                )
            )
        }
        return if (meshPeers.isEmpty()) lkPeers else lkPeers + meshPeers
    }

    fun stop() {
        drivingJob?.cancel()
        drivingJob = null
        scope.launch {
            stateMachine.endSession()
        }
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
