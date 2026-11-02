package com.ridervoice.mesh.switching

import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.identity.MeshIdentityRepository
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        healthSignalsProvider: LocalHealthSignalsProvider
    ) : this(
        activeRideRepository,
        liveKitManager,
        networkObserver,
        coordinator,
        meshTransport,
        identityRepo,
        healthSignalsProvider,
        Dispatchers.Default
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _lastTransitionReason = MutableStateFlow<String?>(null)
    val lastTransitionReason: StateFlow<String?> = _lastTransitionReason.asStateFlow()

    private val _groupStatus = MutableStateFlow(GroupConnectivityStatus.ENTIRE_GROUP_ONLINE)
    val groupStatus: StateFlow<GroupConnectivityStatus> = _groupStatus.asStateFlow()

    val stateMachine: AutomaticSwitchStateMachine = LocalHealthSignalsProvider.createMachine(
        coordinator = coordinator,
        onEmitTransitionDiagnostic = { from, to, reason ->
            _lastTransitionReason.value = "$from -> $to ($reason)"
        },
        onProbeMesh = {
            val ride = activeRideRepository.state.value ?: return@createMachine
            val canonicalKey = ride.lkRoomName?.takeIf { it.isNotBlank() } ?: ride.roomId
            val companionSession = withContext(Dispatchers.Default) {
                OfflineSession.fromOnlineRoom(canonicalKey, System.currentTimeMillis())
            }
            if (companionSession != null) {
                coordinator.ensureWarmStandbyDiscovery(companionSession)
            }
        }
    )

    val autoSwitchState: StateFlow<AutoSwitchState> = stateMachine.state

    private var drivingJob: Job? = null

    fun start() {
        if (drivingJob != null) return

        drivingJob = scope.launch {
            activeRideRepository.state.collectLatest { ride ->
                if (ride == null) {
                    stateMachine.endSession()
                    coordinator.setOnlineSession(null)
                    _groupStatus.value = GroupConnectivityStatus.ENTIRE_GROUP_ONLINE
                } else {
                    if (ride.displayName.isNotBlank()) {
                        identityRepo.setDisplayName(ride.displayName)
                    }

                    coordinator.setOnlineSession(
                        VoiceSession.Online(
                            url = ride.livekitUrl?.takeIf { it.isNotBlank() } ?: Constants.LIVEKIT_URL,
                            roomId = ride.roomId,
                            tokenProvider = { activeRideRepository.ensureFreshToken().getOrThrow() },
                            expectedLkRoomName = ride.lkRoomName
                        )
                    )

                    val canonicalKey = ride.lkRoomName?.takeIf { it.isNotBlank() } ?: ride.roomId
                    val companionSession = withContext(Dispatchers.Default) {
                        OfflineSession.fromOnlineRoom(canonicalKey, System.currentTimeMillis())
                    }

                    while (isActive && activeRideRepository.state.value != null) {
                        if ((OfflineMeshGate.isWarmStandbyAllowed() || OfflineMeshGate.isAutomaticFallbackAllowed()) &&
                            companionSession != null &&
                            meshTransport.state.value == VoiceTransportState.STOPPED
                        ) {
                            coordinator.ensureWarmStandbyDiscovery(companionSession)
                        }

                        val signals = healthSignalsProvider.sampleSignals()

                        if (stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE &&
                            signals.shouldTriggerBackgroundReconnect()
                        ) {
                            (coordinator.liveKitTransport as? LiveKitVoiceTransport)?.triggerBackgroundReconnect()
                        }

                        stateMachine.evaluate(signals)

                        _groupStatus.value = when {
                            stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE ->
                                GroupConnectivityStatus.LOCAL_OFFLINE_MESH_ACTIVE
                            liveKitManager.participants.value.any { it.isGhost } ||
                                meshTransport.participants.value.any { it.isOnline } ->
                                GroupConnectivityStatus.LOCAL_ONLINE_SOME_PEERS_OFFLINE
                            else -> GroupConnectivityStatus.ENTIRE_GROUP_ONLINE
                        }

                        delay(1_000L)
                    }
                }
            }
        }
    }

    fun stop() {
        drivingJob?.cancel()
        drivingJob = null
        scope.launch { stateMachine.endSession() }
    }
}
