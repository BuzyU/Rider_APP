package com.ridervoice.mesh.switching

import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.identity.MeshIdentityRepository
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.network.NetworkObserver
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.utils.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshAutoSwitchManager(
    private val activeRideRepository: ActiveRideRepository,
    private val liveKitManager: LiveKitManager,
    private val networkObserver: NetworkObserver,
    private val coordinator: VoiceSessionCoordinator,
    private val meshTransport: OfflineMeshVoiceTransport,
    private val identityRepo: MeshIdentityRepository,
    private val healthSignalsProvider: LocalHealthSignalsProvider,
    private val secretProvider: OfflineSecretProvider = NullOfflineSecretProvider(),
    private val clock: Clock = SystemClock,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    @Inject
    constructor(
        activeRideRepository: ActiveRideRepository,
        liveKitManager: LiveKitManager,
        networkObserver: NetworkObserver,
        coordinator: VoiceSessionCoordinator,
        meshTransport: OfflineMeshVoiceTransport,
        identityRepo: MeshIdentityRepository,
        healthSignalsProvider: LocalHealthSignalsProvider,
        secretProvider: OfflineSecretProvider
    ) : this(
        activeRideRepository,
        liveKitManager,
        networkObserver,
        coordinator,
        meshTransport,
        identityRepo,
        healthSignalsProvider,
        secretProvider,
        SystemClock,
        Dispatchers.Default
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _lastTransitionReason = MutableStateFlow<String?>(null)
    val lastTransitionReason: StateFlow<String?> = _lastTransitionReason.asStateFlow()

    private val _groupStatus = MutableStateFlow(GroupConnectivityStatus.ONLINE_ALL)
    val groupStatus: StateFlow<GroupConnectivityStatus> = _groupStatus.asStateFlow()

    val offlineAvailability: StateFlow<OfflineAvailability> = coordinator.offlineAvailability

    val stateMachine: AutomaticSwitchStateMachine = LocalHealthSignalsProvider.createMachine(
        coordinator = coordinator,
        clock = { clock.nowMs() },
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
        if (drivingJob != null) return

        drivingJob = scope.launch {
            var currentLiveRideId: String? = null

            combine(
                activeRideRepository.state,
                OfflineMeshGate.settingsFlow
            ) { ride, _ -> ride }.collectLatest { ride ->
                if (ride == null || ride.phase != ActiveRideRepository.Phase.LIVE) {
                    // Phase.LOBBY / Phase.ENDING / null -> endSession, stop ticks, stop discovery, enable-nothing
                    if (currentLiveRideId != null) {
                        stateMachine.endSession()
                        currentLiveRideId = null
                    }
                    coordinator.setOnlineSession(null)
                    _groupStatus.value = GroupConnectivityStatus.ONLINE_ALL
                    if (meshTransport.state.value != VoiceTransportState.STOPPED &&
                        stateMachine.state.value == AutoSwitchState.ONLINE
                    ) {
                        meshTransport.stop()
                    }
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
                        stateMachine.state.value == AutoSwitchState.ONLINE
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

                if (meshTransport.state.value != VoiceTransportState.STOPPED &&
                    stateMachine.state.value == AutoSwitchState.ONLINE
                ) {
                    meshTransport.stop()
                }
            }
        }
    }

    private fun buildUnifiedParticipantList(isLocalOffline: Boolean): List<com.ridervoice.mesh.transport.UnifiedVoicePeer> {
        val rawMesh = meshTransport.participants.value
        val meshPeers = (rawMesh as? List<*>)?.filterIsInstance<com.ridervoice.mesh.transport.UnifiedVoicePeer>() ?: emptyList()
        if (isLocalOffline) {
            return meshPeers
        }
        val rawLk = liveKitManager.participants.value
        val lkParticipants = (rawLk as? List<*>)?.filterIsInstance<com.ridervoice.models.Participant>() ?: emptyList()
        val lkPeers = lkParticipants.map { p ->
            com.ridervoice.mesh.transport.UnifiedVoicePeer(
                id = p.identity,
                name = p.displayName?.takeIf { it.isNotBlank() } ?: p.identity,
                isOnline = !p.isGhost,
                transport = com.ridervoice.mesh.transport.PeerTransportKind.ONLINE_LIVEKIT,
                hopCount = 1,
                nextHopId = null,
                isDirect = true,
                isRelayed = false,
                routeAgeMs = 0L,
                isSpeaking = false
            )
        }
        return if (meshPeers.isEmpty()) lkPeers else lkPeers + meshPeers
    }

    fun stop() {
        drivingJob?.cancel()
        drivingJob = null
        scope.launch { stateMachine.endSession() }
    }
}
