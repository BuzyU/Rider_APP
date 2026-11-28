package com.ridervoice.mesh.switching

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.convoy.ConvoyHash
import com.ridervoice.mesh.convoy.ConvoyMembershipSnapshot
import com.ridervoice.mesh.convoy.ConvoyModeState
import com.ridervoice.mesh.convoy.ConvoyProtocolConfig
import com.ridervoice.mesh.convoy.ConvoyReadinessWireRecord
import com.ridervoice.mesh.convoy.ConvoyStateCodec
import com.ridervoice.mesh.convoy.ConvoyStateDecodeResult
import com.ridervoice.mesh.convoy.ConvoyStatePersistence
import com.ridervoice.mesh.convoy.ConvoyStateWirePayload
import com.ridervoice.mesh.convoy.ConvoyTransportCoordinator
import com.ridervoice.mesh.convoy.CoordinatorInput
import com.ridervoice.mesh.convoy.CoordinatorOutput
import com.ridervoice.mesh.convoy.EncryptedConvoyStateStore
import com.ridervoice.mesh.convoy.LiveKitReadiness
import com.ridervoice.mesh.convoy.LocalModeWire
import com.ridervoice.mesh.convoy.LocalTransportState
import com.ridervoice.mesh.convoy.ManualExclusionRequest
import com.ridervoice.mesh.convoy.MemberObservation
import com.ridervoice.mesh.convoy.MonotonicClock
import com.ridervoice.mesh.convoy.PromptKind
import com.ridervoice.mesh.convoy.ReadinessSource
import com.ridervoice.mesh.convoy.SingleFlightHandoffExecutor
import com.ridervoice.mesh.convoy.TransportAction
import com.ridervoice.mesh.convoy.TransportDecision
import com.ridervoice.mesh.convoy.TransportMode
import com.ridervoice.mesh.link.AndroidWifiDirectGroupController
import com.ridervoice.mesh.link.StarGroupOwnerSelector
import com.ridervoice.mesh.link.WifiDirectGroupController
import com.ridervoice.mesh.link.WifiDirectStatus
import com.ridervoice.mesh.notification.MeshStatusEvent
import com.ridervoice.mesh.notification.MeshStatusNotifier
import com.ridervoice.mesh.offline.RiderLinkLevel
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.topology.MeshControlExtension
import com.ridervoice.mesh.topology.MeshPositionBridge
import com.ridervoice.mesh.topology.TopologyEngine
import com.ridervoice.mesh.topology.TopologyPlan
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.MeshCadence
import com.ridervoice.mesh.transport.MeshControl
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.OnlineParticipant
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.mesh.transport.VoiceTransportState
import com.ridervoice.network.ConnectionState
import com.ridervoice.network.LiveKitManager
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.utils.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MeshAutoSwitchManager(
    private val activeRideRepository: ActiveRideRepository,
    private val liveKitManager: LiveKitManager,
    private val coordinator: VoiceSessionCoordinator,
    private val meshTransport: OfflineMeshVoiceTransport,
    private val healthSignalsProvider: LocalHealthSignalsProvider,
    private val secretProvider: OfflineSecretProvider = NullOfflineSecretProvider(),
    private val clock: Clock = SystemClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val isStandaloneSessionActive: () -> Boolean = { false },
    private val sessionHolder: com.ridervoice.mesh.session.OfflineSessionHolder? = null,
    private val localOnlineIdentity: () -> String? = { runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() },
    private val meshRuntimeSettings: com.ridervoice.mesh.MeshRuntimeSettings? = null,
    private val convoyPersistence: ConvoyStatePersistence? = null,
    private val convoyConfig: ConvoyProtocolConfig = ConvoyProtocolConfig(),
    private val wifiDirectGroupController: WifiDirectGroupController? = null,
    private val statusNotifier: MeshStatusNotifier? = null,
    private val positionBridge: MeshPositionBridge? = null,
    private val dualP2pCapableIdsProvider: () -> Set<String> = { emptySet() }
) {

    companion object {
        const val MAX_DECISION_QUEUE_SIZE = MeshConfig.MAX_DEVICES * 3
        const val MAX_OBSERVATION_QUEUE_SIZE = MeshConfig.MAX_DEVICES * 5
    }

    private var scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _lastTransitionReason = MutableStateFlow<String?>(null)
    val lastTransitionReason: StateFlow<String?> = _lastTransitionReason.asStateFlow()

    private val _groupStatus = MutableStateFlow(GroupConnectivityStatus.ONLINE_ALL)
    val groupStatus: StateFlow<GroupConnectivityStatus> = _groupStatus.asStateFlow()

    private val _convoyModeState = MutableStateFlow(ConvoyModeState.ONLINE)
    val convoyModeState: StateFlow<ConvoyModeState> = _convoyModeState.asStateFlow()

    private val _localTransportState = MutableStateFlow(LocalTransportState.ONLINE_ACTIVE)
    val localTransportState: StateFlow<LocalTransportState> = _localTransportState.asStateFlow()

    private val _stuckExclusionMemberIds = MutableStateFlow<List<String>>(emptyList())
    val stuckExclusionMemberIds: StateFlow<List<String>> = _stuckExclusionMemberIds.asStateFlow()

    private val _protocolMPromptActive = MutableStateFlow(false)
    val protocolMPromptActive: StateFlow<Boolean> = _protocolMPromptActive.asStateFlow()

    private val _moreThanSixParticipantsDisabled = MutableStateFlow(false)
    val moreThanSixParticipantsDisabled: StateFlow<Boolean> = _moreThanSixParticipantsDisabled.asStateFlow()

    private val _manualReadyAsserted = MutableStateFlow(false)
    val manualReadyAsserted: StateFlow<Boolean> = _manualReadyAsserted.asStateFlow()

    private val _topologyPlan = MutableStateFlow<TopologyPlan?>(null)
    val topologyPlan: StateFlow<TopologyPlan?> = _topologyPlan.asStateFlow()

    @Volatile private var pendingExclusionRequest: ManualExclusionRequest? = null
    @Volatile private var convoyCoordinator: ConvoyTransportCoordinator? = null
    @Volatile private var convoyExecutor: SingleFlightHandoffExecutor? = null
    @Volatile private var activeConvoyMembership: ConvoyMembershipSnapshot? = null
    @Volatile private var lastConvoyOutput: CoordinatorOutput? = null
    @Volatile private var latestSignals: LocalHealthSignals? = null
    @Volatile private var localIncarnation: Long = EncryptedConvoyStateStore.generateSenderIncarnation()
    private val queueLock = Any()
    private val inboundDecisionQueue = ArrayDeque<TransportDecision>()
    private val inboundObservationQueue = ArrayDeque<MemberObservation>()
    private val inboundWakeSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile internal var pendingControlBurst: Boolean = false
    @Volatile private var lastSelectedGoId: String? = null
    private val excludedGoIds = HashMap<String, Long>()
    private var goAbsentSinceMs: Long? = null
    private var lastNotifiedTransportState: LocalTransportState? = null
    private var lastNotifiedP2pStatus: WifiDirectStatus? = null
    @Volatile private var adoptedGoAnnounce: MeshControlExtension.GoAnnounce? = null
    private var topologyElectionEpoch: Int = 0
    private var hasConnectedToCurrentGo: Boolean = false
    private var lastGoAnnounceSentMs: Long = 0L

    internal fun enqueueBoundedDecision(decision: TransportDecision) {
        synchronized(queueLock) {
            while (inboundDecisionQueue.size >= MAX_DECISION_QUEUE_SIZE) {
                inboundDecisionQueue.removeFirst()
            }
            inboundDecisionQueue.addLast(decision)
        }
        inboundWakeSignal.trySend(Unit)
    }

    internal fun enqueueBoundedObservation(observation: MemberObservation) {
        synchronized(queueLock) {
            while (inboundObservationQueue.size >= MAX_OBSERVATION_QUEUE_SIZE) {
                inboundObservationQueue.removeFirst()
            }
            inboundObservationQueue.addLast(observation)
        }
        if (observation.recoveryEligible || observation.ackFor != null) {
            inboundWakeSignal.trySend(Unit)
        }
    }

    internal fun drainBoundedDecisions(): List<TransportDecision> = synchronized(queueLock) {
        val out = inboundDecisionQueue.toList()
        inboundDecisionQueue.clear()
        out
    }

    internal fun drainBoundedObservations(): List<MemberObservation> = synchronized(queueLock) {
        val out = inboundObservationQueue.toList()
        inboundObservationQueue.clear()
        out
    }

    internal suspend fun awaitNextTickOrWakeForTest(tickDelayMs: Long): Boolean =
        withTimeoutOrNull(tickDelayMs) { inboundWakeSignal.receive() } != null

    internal fun flushControlBurstIfAlive(
        membership: ConvoyMembershipSnapshot,
        out: CoordinatorOutput,
        signals: LocalHealthSignals,
        localId: String,
        needsBurst: Boolean
    ): Boolean {
        if (needsBurst && !signals.isMeshTransportAlive) {
            pendingControlBurst = true
            return false
        }
        if ((needsBurst || pendingControlBurst) && signals.isMeshTransportAlive) {
            val bytes = buildControlPayloadBytes(membership, out, signals, localId) ?: return false
            meshTransport.sendControlFrame(bytes)
            pendingControlBurst = false
            return true
        }
        return false
    }

    fun assertManualReady(ready: Boolean) {
        _manualReadyAsserted.value = ready
    }

    fun requestManualExclusion(excludedIds: Set<String>, expectedEligibleIds: Set<String>) {
        pendingExclusionRequest = ManualExclusionRequest(
            excludedMemberIds = excludedIds,
            confirmedAtMs = clock.nowMs(),
            expectedEligibleSnapshot = expectedEligibleIds
        )
    }

    fun currentEligibleMemberIds(): Set<String> {
        val snap = activeConvoyMembership ?: return emptySet()
        val nonReady = _stuckExclusionMemberIds.value.toSet()
        return snap.meshParticipants.filterTo(LinkedHashSet()) { it !in nonReady }
    }

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

    private fun ActiveRideRepository.ActiveRide?.toMirrorSnapshot(): com.ridervoice.mesh.RoomMeshMirrorSnapshot? =
        this?.let { ride ->
            val cfg = ride.meshConfig ?: com.ridervoice.mesh.RoomMeshConfig.DEFAULT
            com.ridervoice.mesh.RoomMeshMirrorSnapshot(
                isLive = ride.phase == ActiveRideRepository.Phase.LIVE,
                offlineMeshEnabled = cfg.offlineMeshEnabled,
                optOut = cfg.optOut,
                automaticFallbackEnabled = cfg.automaticFallbackEnabled,
                warmStandbyEnabled = cfg.warmStandbyEnabled
            )
        }

    fun start() {
        if (!OfflineMeshGate.isBuildGateOpen() || OfflineMeshGate.decision() == com.ridervoice.mesh.GateDecision.DISABLED_BY_BUILD) return
        if (drivingJob?.isActive == true) return
        sessionHolder?.restoreBackupIfAllowed()
        healthSignalsProvider.startWatching()
        meshTransport.onlineIdentitySupplier = {
            if (liveKitManager.connectionState.value == ConnectionState.CONNECTED) {
                localOnlineIdentity()
            } else null
        }
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
                    val wasRideLive = currentLiveRideId != null
                    if (wasRideLive) {
                        stateMachine.endSession()
                        currentLiveRideId = null
                    }
                    if (ride == null ||
                        ride.phase == ActiveRideRepository.Phase.ENDING ||
                        ride.meshConfig?.optOut == true ||
                        ride.meshConfig?.offlineMeshEnabled == false
                    ) {
                        wifiDirectGroupController?.stop()
                    }
                    meshRuntimeSettings?.let { com.ridervoice.mesh.RoomMeshFlagMirror.sync(ride.toMirrorSnapshot(), it) }
                    coordinator.setOnlineSession(null)
                    _groupStatus.value = GroupConnectivityStatus.ONLINE_ALL
                    clearConvoySessionState()
                    if (wasRideLive) {
                        coordinator.onOnlineRideEnded(keepStandaloneMesh = isStandaloneSessionActive())
                    } else if (!isStandaloneSessionActive() &&
                        meshTransport.state.value != VoiceTransportState.STOPPED &&
                        stateMachine.state.value == AutoSwitchState.ONLINE
                    ) {
                        meshTransport.stop()
                    }
                    return@collectLatest
                }

                // Phase.LIVE (new rideId) -> beginSession, start evaluation ticks
                if (currentLiveRideId != ride.roomId) {
                    currentLiveRideId = ride.roomId
                    meshRuntimeSettings?.let { com.ridervoice.mesh.RoomMeshFlagMirror.sync(ride.toMirrorSnapshot(), it) }
                    stateMachine.beginSession(ride.roomId, clock.nowMs())
                    initConvoySessionState(ride)
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

                var netLostSinceMs: Long? = null
                var recoveryStableSinceMs: Long? = null
                var validatedOnlineWithoutLkSinceMs: Long? = null
                var probeFailedWithoutLkSinceMs: Long? = null

                while (isActive &&
                    activeRideRepository.state.value?.roomId == ride.roomId &&
                    activeRideRepository.state.value?.phase == ActiveRideRepository.Phase.LIVE &&
                    OfflineMeshGate.isOfflineAllowed()
                ) {
                    val membership = activeRideRepository.state.value?.convoyMembershipSnapshot ?: activeConvoyMembership
                    val isMeshCapExceeded = membership?.isMeshCapExceeded == true
                    _moreThanSixParticipantsDisabled.value = isMeshCapExceeded

                    val backup = if (isMeshCapExceeded) null else secretProvider.getSessionForOnlineRide(ride.roomId)
                    if (!isMeshCapExceeded && backup != null && OfflineMeshGate.isBuildGateOpen()) {
                        ensureP2pGroupWithSelector(
                            ride = ride,
                            membership = membership,
                            backup = backup,
                            localId = localOnlineIdentity(),
                            nowMs = clock.nowMs()
                        )
                    } else if (isMeshCapExceeded) {
                        wifiDirectGroupController?.stop()
                    }
                    if (!isMeshCapExceeded &&
                        (OfflineMeshGate.isWarmStandbyAllowed() || OfflineMeshGate.isAutomaticFallbackAllowed()) &&
                        backup != null &&
                        meshTransport.state.value in setOf(VoiceTransportState.STOPPED, VoiceTransportState.FAILED)
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
                    val nowSample = clock.nowMs()
                    val probeAndLkDown = !signals.isLiveKitConnected && !signals.isHealthProbeSuccessful
                    if (probeAndLkDown) {
                        if (probeFailedWithoutLkSinceMs == null) probeFailedWithoutLkSinceMs = nowSample
                    } else {
                        probeFailedWithoutLkSinceMs = null
                    }
                    // On real hotspots / dead APs Android can keep NET_CAPABILITY_VALIDATED=true long after internet drops.
                    // Once both LiveKit AND the HTTP /health probe have failed continuously, treat internet as unvalidated.
                    val staleValidatedOverride = signals.isValidatedInternet &&
                        probeAndLkDown &&
                        probeFailedWithoutLkSinceMs != null &&
                        (nowSample - probeFailedWithoutLkSinceMs >= MeshConfig.STALE_VALIDATED_OVERRIDE_MS ||
                            signals.liveKitReconnectAttempts >= 2)
                    val effectiveSignals = if (staleValidatedOverride) {
                        signals.copy(isValidatedInternet = false)
                    } else {
                        signals
                    }
                    latestSignals = effectiveSignals

                    if (!isMeshCapExceeded &&
                        (stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE ||
                            _localTransportState.value == LocalTransportState.OFFLINE_ACTIVE) &&
                        signals.shouldTriggerBackgroundReconnect()
                    ) {
                        (coordinator.liveKitTransport as? LiveKitVoiceTransport)?.triggerBackgroundReconnect()
                    }

                    val offlineAvailable = !isMeshCapExceeded &&
                        coordinator.offlineAvailability.value == OfflineAvailability.AVAILABLE

                    val convoyCoord = convoyCoordinator
                    val localId = localOnlineIdentity()
                    if (convoyCoord != null && membership != null && membership.isGroupFallbackEligible &&
                        localId != null && membership.isMeshParticipant(localId) && !isMeshCapExceeded
                    ) {
                        val now = nowSample
                        val localNetLost = !effectiveSignals.isValidatedInternet &&
                            !effectiveSignals.isLiveKitConnected &&
                            !effectiveSignals.isHealthProbeSuccessful
                        if (localNetLost) {
                            if (netLostSinceMs == null) netLostSinceMs = now
                            recoveryStableSinceMs = null
                        } else {
                            netLostSinceMs = null
                            val lkOk = effectiveSignals.isValidatedInternet &&
                                effectiveSignals.isLiveKitConnected &&
                                effectiveSignals.isHealthProbeSuccessful
                            if (lkOk) {
                                if (recoveryStableSinceMs == null) recoveryStableSinceMs = now
                            } else {
                                recoveryStableSinceMs = null
                            }
                        }
                        val totalLossConfirmed = netLostSinceMs != null && (now - netLostSinceMs) >= convoyConfig.failureConfirmationMs
                        val recoveryStable = recoveryStableSinceMs != null && (now - recoveryStableSinceMs) >= convoyConfig.recoveryStabilityMs

                        val inOfflineMode = convoyCoord.currentAcceptedDecision()?.mode == TransportMode.OFFLINE
                        if (inOfflineMode && !localNetLost && effectiveSignals.isValidatedInternet && !effectiveSignals.isLiveKitConnected) {
                            if (validatedOnlineWithoutLkSinceMs == null) validatedOnlineWithoutLkSinceMs = now
                        } else {
                            validatedOnlineWithoutLkSinceMs = null
                        }
                        val probingUnavailable = inOfflineMode && !localNetLost && effectiveSignals.isValidatedInternet && (
                            liveKitManager.connectionState.value == ConnectionState.FAILED ||
                                (validatedOnlineWithoutLkSinceMs != null && (now - validatedOnlineWithoutLkSinceMs) >= convoyConfig.recoveryStabilityMs + 4_000L)
                            )

                        val decisionsIn = drainBoundedDecisions()
                        val obsIn = drainBoundedObservations()

                        val lkReadiness = when (liveKitManager.connectionState.value) {
                            ConnectionState.CONNECTED -> if (recoveryStable) LiveKitReadiness.READY else LiveKitReadiness.CONNECTED_UNVERIFIED
                            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> LiveKitReadiness.CONNECTING
                            ConnectionState.FAILED -> LiveKitReadiness.FAILED
                            ConnectionState.DISCONNECTED -> LiveKitReadiness.NOT_READY
                        }

                        val input = CoordinatorInput(
                            localMemberId = localId,
                            membership = membership,
                            currentDecision = convoyCoord.currentAcceptedDecision(),
                            observations = obsIn,
                            localNetLost = localNetLost,
                            localTotalLossConfirmed = totalLossConfirmed && offlineAvailable,
                            localLiveKitReadiness = lkReadiness,
                            localRecoveryStable = recoveryStable,
                            localMeshRouteAvailable = effectiveSignals.hasValidMeshRoute && offlineAvailable,
                            localExecutionState = _localTransportState.value,
                            generationHighWater = convoyCoord.currentGenerationHighWater(),
                            generationUnknown = convoyCoord.isGenerationUnknown(),
                            manualReadyAsserted = _manualReadyAsserted.value,
                            manualExclusionRequest = pendingExclusionRequest,
                            probingUnavailable = probingUnavailable,
                            incomingDecisions = decisionsIn
                        )
                        pendingExclusionRequest = null

                        val out = convoyCoord.evaluate(input)
                        lastConvoyOutput = out
                        _convoyModeState.value = out.convoyModeState
                        if (!localNetLost && _manualReadyAsserted.value && lkReadiness == LiveKitReadiness.READY) {
                            // Keep manualReadyAsserted only while offline/recovering
                        } else if (localNetLost) {
                            _manualReadyAsserted.value = false
                        }

                        var stuckMembers: List<String> = emptyList()
                        var showProtocolM = false
                        var needsBurst = false
                        var fallbackUnavailableReason: String? = null
                        for (act in out.actions) {
                            when (act) {
                                is TransportAction.SendControlBurst, is TransportAction.AdvertiseDecision -> needsBurst = true
                                is TransportAction.PromptRider -> {
                                    if (act.kind == PromptKind.STUCK_OFFLINE_EXCLUSION) {
                                        stuckMembers = act.memberIds
                                    } else if (act.kind == PromptKind.PROTOCOL_M_MANUAL_RETURN) {
                                        showProtocolM = true
                                    }
                                }
                                is TransportAction.ReportFallbackUnavailable -> {
                                    fallbackUnavailableReason = act.reason
                                    _lastTransitionReason.value = if (act.reason == "NO_LOCAL_MESH_ROUTE") {
                                        "ONLINE -> FALLBACK_UNAVAILABLE (no mesh route: ${act.reason})"
                                    } else {
                                        "ONLINE -> FALLBACK_UNAVAILABLE (${act.reason})"
                                    }
                                }
                                else -> Unit
                            }
                        }
                        _stuckExclusionMemberIds.value = stuckMembers
                        _protocolMPromptActive.value = showProtocolM

                        val executor = convoyExecutor
                        if (executor != null) {
                            out.acceptedDecision?.let { executor.onDesiredDecision(it) }
                            val cmd = executor.pollNextCommand(
                                nowMs = now,
                                canFallbackToMesh = effectiveSignals.hasValidMeshRoute && offlineAvailable,
                                canRecoverToLiveKit = effectiveSignals.isValidatedInternet &&
                                    (effectiveSignals.isLiveKitConnected || (probingUnavailable && _manualReadyAsserted.value))
                            )
                            when (cmd) {
                                is SingleFlightHandoffExecutor.Command.RunFallback -> {
                                    _localTransportState.value = LocalTransportState.STARTING_OFFLINE
                                    emitEdgeStatusNotifications()
                                    val ok = coordinator.fallbackToMesh()
                                    executor.onHandoffFinished(cmd.decision.decisionId, ok, clock.nowMs())
                                    if (ok) {
                                        convoyCoord.recordActualAudioTransition(clock.nowMs())
                                        _localTransportState.value = LocalTransportState.OFFLINE_ACTIVE
                                        _lastTransitionReason.value = "Convoy fallback gen=${cmd.decision.generation}"
                                    } else {
                                        _localTransportState.value = LocalTransportState.FALLBACK_UNAVAILABLE
                                        _lastTransitionReason.value = "Convoy fallback failed gen=${cmd.decision.generation}"
                                    }
                                }
                                is SingleFlightHandoffExecutor.Command.RunRecovery -> {
                                    _localTransportState.value = LocalTransportState.STARTING_ONLINE
                                    emitEdgeStatusNotifications()
                                    val ok = coordinator.recoverToLiveKit()
                                    executor.onHandoffFinished(cmd.decision.decisionId, ok, clock.nowMs())
                                    if (ok) {
                                        convoyCoord.recordActualAudioTransition(clock.nowMs())
                                        _localTransportState.value = LocalTransportState.ONLINE_ACTIVE
                                        _manualReadyAsserted.value = false
                                        _lastTransitionReason.value = "Convoy recovery gen=${cmd.decision.generation}"
                                    } else {
                                        _localTransportState.value = LocalTransportState.RECOVERY_FAILED
                                    }
                                }
                                null -> {
                                    _localTransportState.value = deriveLocalTransportState(
                                        convoyCoord = convoyCoord,
                                        localNetLost = localNetLost,
                                        owner = coordinator.audioOwnershipArbiter.owner.value,
                                        fallbackUnavailable = fallbackUnavailableReason != null
                                    )
                                }
                            }
                        }

                        flushControlBurstIfAlive(membership, out, effectiveSignals, localId, needsBurst)
                    } else {
                        stateMachine.evaluate(effectiveSignals, offlineAvailable = offlineAvailable)
                        _localTransportState.value = mapLegacyStateToLocalTransport(stateMachine.state.value, isMeshCapExceeded)
                    }

                    emitEdgeStatusNotifications()

                    // Accelerate mesh peer discovery as soon as local online loss starts so
                    // nearby phones exchange HELLO routes before fallback confirmation expires.
                    val isOwnerOffline = coordinator.audioOwnershipArbiter.owner.value == AudioOwner.OFFLINE_MESH
                    val isOwnerLiveKit = coordinator.audioOwnershipArbiter.owner.value == AudioOwner.LIVEKIT
                    if (!isMeshCapExceeded && meshTransport.state.value == VoiceTransportState.CONNECTED && !isOwnerOffline) {
                        val suspectOrUnavailable = probeAndLkDown ||
                            (!effectiveSignals.isValidatedInternet && !effectiveSignals.isLiveKitConnected) ||
                            stateMachine.state.value in setOf(AutoSwitchState.ONLINE_SUSPECT, AutoSwitchState.FALLBACK_UNAVAILABLE) ||
                            _localTransportState.value in setOf(LocalTransportState.ONLINE_SUSPECT, LocalTransportState.FALLBACK_UNAVAILABLE)
                        if (suspectOrUnavailable) {
                            (meshTransport as? MeshControl)?.setCadence(MeshCadence.BOOST)
                        } else if (isOwnerLiveKit &&
                            effectiveSignals.isValidatedInternet &&
                            effectiveSignals.isLiveKitConnected &&
                            stateMachine.state.value == AutoSwitchState.ONLINE &&
                            _localTransportState.value == LocalTransportState.ONLINE_ACTIVE
                        ) {
                            (meshTransport as? MeshControl)?.setCadence(MeshCadence.STANDBY)
                        }
                    }

                    val isLocalOffline = stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE ||
                        coordinator.audioOwnershipArbiter.owner.value == AudioOwner.OFFLINE_MESH
                    val unifiedPeers = buildUnifiedParticipantList(isLocalOffline)
                    _groupStatus.value = computeGroupConnectivity(isLocalOffline, unifiedPeers)

                    val isCurrentlyOffline = isLocalOffline ||
                        convoyCoord?.currentAcceptedDecision()?.mode == TransportMode.OFFLINE ||
                        _localTransportState.value == LocalTransportState.OFFLINE_ACTIVE
                    val isSuspectOrStarting =
                        _localTransportState.value == LocalTransportState.ONLINE_SUSPECT ||
                        _localTransportState.value == LocalTransportState.STARTING_OFFLINE ||
                        stateMachine.state.value == AutoSwitchState.ONLINE_SUSPECT ||
                        stateMachine.state.value == AutoSwitchState.STARTING_OFFLINE_MESH
                    val isRecoveringFast =
                        (isCurrentlyOffline && recoveryStableSinceMs != null) ||
                        _convoyModeState.value == ConvoyModeState.ONLINE_PROPOSED ||
                        _localTransportState.value == LocalTransportState.STARTING_ONLINE ||
                        stateMachine.state.value == AutoSwitchState.ONLINE_RECOVERING
                    healthSignalsProvider.setFastProbeMode(isSuspectOrStarting)
                    val tickDelayMs = if (isSuspectOrStarting || isRecoveringFast) MeshConfig.SUSPECT_TICK_MS else 1_000L
                    withTimeoutOrNull(tickDelayMs) { inboundWakeSignal.receive() }
                }
                healthSignalsProvider.setFastProbeMode(false)

                if (!OfflineMeshGate.isOfflineAllowed()) {
                    val wasOffOnline = stateMachine.state.value != AutoSwitchState.ONLINE
                    if (wasOffOnline) {
                        stateMachine.beginSession(ride.roomId, clock.nowMs())
                    }
                    _groupStatus.value = GroupConnectivityStatus.ONLINE_ALL
                    if (meshTransport.state.value != VoiceTransportState.STOPPED && !isStandaloneSessionActive()) {
                        meshTransport.stop()
                    }
                    if (wasOffOnline) {
                        coordinator.onOnlineRideEnded(keepStandaloneMesh = false)
                    }
                } else if (meshTransport.state.value != VoiceTransportState.STOPPED &&
                    stateMachine.state.value == AutoSwitchState.ONLINE &&
                    !isStandaloneSessionActive()
                ) {
                    meshTransport.stop()
                }
            }
        }
    }

    internal fun buildUnifiedParticipantList(isLocalOffline: Boolean): List<UnifiedVoicePeer> {
        val rawMesh = meshTransport.participants.value
        val meshPeers = (rawMesh as? List<*>)?.filterIsInstance<UnifiedVoicePeer>() ?: emptyList()
        if (isLocalOffline) {
            return meshPeers
        }
        val rawLk = liveKitManager.participants.value
        val lkParticipants = (rawLk as? List<*>)?.filterIsInstance<com.ridervoice.models.Participant>() ?: emptyList()

        val session = secretProvider.backupSession.value
        val payloadKey = if (session != null && !session.isClosed) {
            try { session.payloadKey() } catch (_: IllegalStateException) { null }
        } else {
            null
        }

        val lkPeers = lkParticipants.map { p ->
            val tag = if (payloadKey != null) com.ridervoice.mesh.protocol.PayloadCrypto.computeOnlineIdTag(payloadKey, p.identity) else null
            UnifiedVoicePeer.fromOnline(
                OnlineParticipant(
                    identity = p.identity,
                    displayName = p.displayName?.takeIf { it.isNotBlank() } ?: p.identity,
                    isSpeaking = liveKitManager.activeSpeaker.value == p.identity,
                    isConnected = !p.isGhost
                ),
                onlineIdTag = tag
            )
        }

        val onlineTags = lkPeers.filter { it.isOnline }.mapNotNull { it.onlineIdTag }.toSet()
        val deduplicatedMeshPeers = if (onlineTags.isNotEmpty()) {
            meshPeers.filter { meshPeer ->
                val meshTag = meshPeer.onlineIdTag
                meshTag == null || meshTag !in onlineTags
            }
        } else {
            meshPeers
        }

        return if (deduplicatedMeshPeers.isEmpty()) lkPeers else lkPeers + deduplicatedMeshPeers
    }

    fun stop() {
        healthSignalsProvider.stopWatching()
        drivingJob?.cancel()
        drivingJob = null
        clearConvoySessionState()
        scope.launch {
            stateMachine.endSession()
        }
    }

    fun close() {
        stop()
        statusNotifier?.release()
        scope.cancel()
    }

    private fun initConvoySessionState(ride: ActiveRideRepository.ActiveRide) {
        val persistedState = convoyPersistence?.load(ConvoyHash.stableSessionUuid(ride.roomId))
        val snap = ride.convoyMembershipSnapshot ?: persistedState?.membership
        activeConvoyMembership = snap
        synchronized(queueLock) {
            inboundDecisionQueue.clear()
            inboundObservationQueue.clear()
        }
        pendingControlBurst = false
        lastSelectedGoId = null
        excludedGoIds.clear()
        goAbsentSinceMs = null
        lastNotifiedTransportState = null
        lastNotifiedP2pStatus = null
        adoptedGoAnnounce = null
        topologyElectionEpoch = 0
        hasConnectedToCurrentGo = false
        lastGoAnnounceSentMs = 0L
        positionBridge?.clear()
        _topologyPlan.value = null
        lastConvoyOutput = null
        pendingExclusionRequest = null
        _stuckExclusionMemberIds.value = emptyList()
        _protocolMPromptActive.value = false
        _manualReadyAsserted.value = false

        val localId = localOnlineIdentity()
        if (snap == null || localId.isNullOrBlank()) {
            convoyCoordinator = null
            convoyExecutor = null
            _convoyModeState.value = ConvoyModeState.ONLINE
            _localTransportState.value = LocalTransportState.ONLINE_ACTIVE
            return
        }
        _moreThanSixParticipantsDisabled.value = snap.isMeshCapExceeded
        val coord = ConvoyTransportCoordinator(
            localMemberId = localId,
            clock = MonotonicClock { clock.nowMs() },
            config = convoyConfig,
            persistence = convoyPersistence
        )
        coord.restoreFromPersistence(snap.sessionId)
        (convoyPersistence as? EncryptedConvoyStateStore)?.saveMembership(snap)
        convoyCoordinator = coord
        convoyExecutor = SingleFlightHandoffExecutor()
        localIncarnation = EncryptedConvoyStateStore.generateSenderIncarnation()

        meshTransport.onControlPacketReceived = { pkt ->
            val currentSnap = activeConvoyMembership
            if (currentSnap != null && !currentSnap.isMeshCapExceeded) {
                val discriminator = pkt.payload.firstOrNull()
                if (discriminator == MeshControlExtension.DISCRIMINATOR_POSITION) {
                    val senderUid = resolveOnlineMemberId(pkt.sourceId, currentSnap)
                    if (senderUid != null) {
                        MeshControlExtension.decodePosition(pkt.payload, senderUid)?.let { fix ->
                            positionBridge?.recordRemotePosition(fix)
                        }
                    }
                } else if (discriminator == MeshControlExtension.DISCRIMINATOR_GO_ANNOUNCE) {
                    MeshControlExtension.decodeGoAnnounce(pkt.payload)?.let { announce ->
                        val isLiveOffline = _localTransportState.value == LocalTransportState.OFFLINE_ACTIVE ||
                            stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE
                        val cur = adoptedGoAnnounce
                        if (cur == null || (!isLiveOffline && announce.beats(cur))) {
                            adoptedGoAnnounce = announce
                            if (announce.electionEpoch > topologyElectionEpoch) {
                                topologyElectionEpoch = announce.electionEpoch
                            }
                        }
                    }
                } else {
                    val res = ConvoyStateCodec.decode(pkt.payload, currentSnap.meshParticipants.size)
                    if (res is ConvoyStateDecodeResult.Ok) {
                        res.payload.decision?.let { enqueueBoundedDecision(it) }
                        val rxMs = clock.nowMs()
                        for (rec in res.payload.records) {
                            val originId = currentSnap.meshParticipants.getOrNull(rec.rosterIdx) ?: continue
                            enqueueBoundedObservation(rec.toObservation(originId, rxMs))
                        }
                    }
                }
            }
        }
        meshTransport.controlPayloadProvider = {
            val currentSnap = activeConvoyMembership
            val out = lastConvoyOutput
            val uid = localOnlineIdentity()
            val sig = latestSignals
            if (currentSnap != null && !currentSnap.isMeshCapExceeded && out != null && uid != null && sig != null) {
                buildControlPayloadBytes(currentSnap, out, sig, uid)
            } else null
        }
    }

    private fun clearConvoySessionState() {
        convoyCoordinator = null
        convoyExecutor = null
        activeConvoyMembership = null
        lastConvoyOutput = null
        pendingExclusionRequest = null
        synchronized(queueLock) {
            inboundDecisionQueue.clear()
            inboundObservationQueue.clear()
        }
        pendingControlBurst = false
        lastSelectedGoId = null
        excludedGoIds.clear()
        goAbsentSinceMs = null
        lastNotifiedTransportState = null
        lastNotifiedP2pStatus = null
        adoptedGoAnnounce = null
        topologyElectionEpoch = 0
        hasConnectedToCurrentGo = false
        lastGoAnnounceSentMs = 0L
        positionBridge?.clear()
        _topologyPlan.value = null
        meshTransport.controlPayloadProvider = null
        meshTransport.onControlPacketReceived = null
        _convoyModeState.value = ConvoyModeState.ONLINE
        _localTransportState.value = LocalTransportState.ONLINE_ACTIVE
        _stuckExclusionMemberIds.value = emptyList()
        _protocolMPromptActive.value = false
        _moreThanSixParticipantsDisabled.value = false
        _manualReadyAsserted.value = false
    }

    private fun buildControlPayloadBytes(
        membership: ConvoyMembershipSnapshot,
        out: CoordinatorOutput,
        signals: LocalHealthSignals,
        localId: String
    ): ByteArray? {
        val coord = convoyCoordinator ?: return null
        if (membership.isMeshCapExceeded) return null
        val meshIdx = membership.indexOfMeshParticipant(localId)
        if (meshIdx !in 0 until ConvoyMembershipSnapshot.MAX_MESH_PARTICIPANTS) return null
        val seq = coord.nextOwnReadinessSeq()
        val ackFor8 = out.ackFor ?: ByteArray(8)
        val ownRec = ConvoyReadinessWireRecord(
            rosterIdx = meshIdx,
            senderIncarnation = localIncarnation,
            seq = seq,
            livekitReady = signals.isLiveKitConnected && signals.isValidatedInternet,
            recoveryEligible = out.localRecoveryEligible,
            meshReady = signals.hasValidMeshRoute,
            netLost = !signals.isValidatedInternet && !signals.isLiveKitConnected,
            localMode = LocalModeWire.fromLocalTransportState(_localTransportState.value),
            readinessSource = out.readinessSource,
            baseGen = out.baseOfflineGen,
            ackFor = ackFor8,
            ageTenthsSec = 0,
            hops = 0
        )
        val now = clock.nowMs()
        val relays = coord.getReAdvertisableObservations(now).mapNotNull { obs ->
            val idx = membership.indexOfMeshParticipant(obs.memberId)
            if (idx !in 0 until ConvoyMembershipSnapshot.MAX_MESH_PARTICIPANTS || idx == meshIdx) return@mapNotNull null
            ConvoyReadinessWireRecord(
                rosterIdx = idx,
                senderIncarnation = obs.senderIncarnation,
                seq = obs.readinessSeq,
                livekitReady = obs.livekitReadiness == LiveKitReadiness.READY,
                recoveryEligible = obs.recoveryEligible,
                meshReady = obs.meshReadiness == com.ridervoice.mesh.convoy.MeshReadiness.ROUTE_AVAILABLE,
                netLost = obs.netState != com.ridervoice.mesh.convoy.NetState.HEALTHY,
                localMode = LocalModeWire.fromLocalTransportState(obs.localExecutionState),
                readinessSource = obs.readinessSource,
                baseGen = obs.baseGen,
                ackFor = obs.ackFor ?: ByteArray(8),
                ageTenthsSec = (obs.ageMs / 100).coerceIn(0, 0xFFFF),
                hops = obs.hops
            )
        }
        val allRecs = (listOf(ownRec) + relays).distinctBy { it.rosterIdx }.take(ConvoyMembershipSnapshot.MAX_MESH_PARTICIPANTS)
        return ConvoyStateCodec.encode(
            ConvoyStateWirePayload(
                decision = out.decisionToAdvertise,
                records = allRecs
            )
        )
    }

    private fun deriveLocalTransportState(
        convoyCoord: ConvoyTransportCoordinator,
        localNetLost: Boolean,
        owner: AudioOwner,
        fallbackUnavailable: Boolean = false
    ): LocalTransportState = when {
        convoyCoord.isGenerationUnknown() -> LocalTransportState.GENERATION_UNKNOWN
        convoyCoord.isGenerationExhausted() -> LocalTransportState.GENERATION_EXHAUSTED
        owner == AudioOwner.OFFLINE_MESH -> LocalTransportState.OFFLINE_ACTIVE
        fallbackUnavailable -> LocalTransportState.FALLBACK_UNAVAILABLE
        owner == AudioOwner.LIVEKIT && localNetLost -> LocalTransportState.ONLINE_SUSPECT
        owner == AudioOwner.LIVEKIT -> LocalTransportState.ONLINE_ACTIVE
        else -> LocalTransportState.ONLINE_ACTIVE
    }

    private fun mapLegacyStateToLocalTransport(
        state: AutoSwitchState,
        moreThanSix: Boolean
    ): LocalTransportState = when (state) {
        AutoSwitchState.ONLINE -> LocalTransportState.ONLINE_ACTIVE
        AutoSwitchState.ONLINE_SUSPECT -> LocalTransportState.ONLINE_SUSPECT
        AutoSwitchState.STARTING_OFFLINE_MESH -> LocalTransportState.STARTING_OFFLINE
        AutoSwitchState.OFFLINE_ACTIVE -> LocalTransportState.OFFLINE_ACTIVE
        AutoSwitchState.ONLINE_RECOVERING, AutoSwitchState.HANDING_BACK_TO_LIVEKIT -> LocalTransportState.STARTING_ONLINE
        AutoSwitchState.FALLBACK_UNAVAILABLE -> if (moreThanSix) LocalTransportState.VOICE_UNAVAILABLE else LocalTransportState.FALLBACK_UNAVAILABLE
        AutoSwitchState.SESSION_ENDED -> LocalTransportState.ONLINE_ACTIVE
    }

    private fun resolveOnlineMemberId(
        meshDeviceId: String,
        membership: ConvoyMembershipSnapshot
    ): String? {
        if (meshDeviceId in membership.meshParticipants) return meshDeviceId
        val peerTag = meshTransport.participants.value
            .filterIsInstance<UnifiedVoicePeer>()
            .firstOrNull { it.id == meshDeviceId }
            ?.onlineIdTag ?: return null
        if (peerTag in membership.meshParticipants) return peerTag
        val session = secretProvider.backupSession.value
        val key = if (session != null && !session.isClosed) {
            runCatching { session.payloadKey() }.getOrNull()
        } else null
        key ?: return null
        return membership.meshParticipants.firstOrNull { uid ->
            PayloadCrypto.computeOnlineIdTag(key, uid) == peerTag
        }
    }

    private fun isMemberPresentOnMesh(
        memberId: String,
        meshPeers: List<UnifiedVoicePeer>
    ): Boolean {
        val rawIds = meshPeers.mapNotNull { it.onlineIdTag ?: it.id }
        if (memberId in rawIds || meshPeers.any { it.id == memberId }) return true
        val session = secretProvider.backupSession.value
        val key = if (session != null && !session.isClosed) {
            runCatching { session.payloadKey() }.getOrNull()
        } else null
        key ?: return false
        val expectedTag = PayloadCrypto.computeOnlineIdTag(key, memberId)
        return expectedTag in rawIds
    }

    private fun ensureP2pGroupWithSelector(
        ride: ActiveRideRepository.ActiveRide,
        membership: ConvoyMembershipSnapshot?,
        backup: com.ridervoice.mesh.session.OfflineSession,
        localId: String?,
        nowMs: Long
    ) {
        val controller = wifiDirectGroupController ?: return
        val localLinkLevel = ride.meshConfig?.linkLevel
        if (localLinkLevel != null && localLinkLevel != RiderLinkLevel.AUTO_P2P) {
            lastSelectedGoId = null
            _topologyPlan.value = null
            controller.stop()
            return
        }

        if (membership == null || localId == null || !membership.isMeshParticipant(localId)) {
            controller.ensureActive(backup, isHost = ride.isHost)
            return
        }

        excludedGoIds.entries.removeIf { it.value <= nowMs }
        val isLiveOfflineVoice = _localTransportState.value == LocalTransportState.OFFLINE_ACTIVE ||
            _localTransportState.value == LocalTransportState.FALLBACK_UNAVAILABLE ||
            stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE
        val p2pConnected = controller.isGroupConnected.value
        if (p2pConnected) {
            hasConnectedToCurrentGo = true
        }
        val prevGoId = lastSelectedGoId

        val meshPeers = meshTransport.participants.value.filterIsInstance<UnifiedVoicePeer>()
        var forceReelect = false
        if (prevGoId != null && prevGoId != localId && !p2pConnected) {
            if (controller.status.value == WifiDirectStatus.FAILED) {
                excludedGoIds[prevGoId] = nowMs + MeshConfig.GO_EXCLUSION_TTL_MS
                goAbsentSinceMs = null
                forceReelect = true
            } else if (isLiveOfflineVoice || hasConnectedToCurrentGo) {
                val goPresent = controller.status.value == WifiDirectStatus.CONNECTED ||
                    isMemberPresentOnMesh(prevGoId, meshPeers)
                if (goPresent) {
                    goAbsentSinceMs = null
                } else {
                    val firstAbsent = goAbsentSinceMs ?: nowMs.also { goAbsentSinceMs = it }
                    if (nowMs - firstAbsent >= MeshConfig.GO_LOSS_DETECTION_MS) {
                        excludedGoIds[prevGoId] = nowMs + MeshConfig.GO_EXCLUSION_TTL_MS
                        goAbsentSinceMs = null
                        forceReelect = true
                    }
                }
            } else {
                // Initial connection setup before first group formation: do not prematurely exclude host
                goAbsentSinceMs = null
            }
        } else {
            goAbsentSinceMs = null
        }

        val positionsMap = positionBridge?.snapshotPositions(localId).orEmpty()
        val plan = TopologyEngine.computePlan(
            meshParticipants = membership.meshParticipants,
            positions = positionsMap,
            dualP2pCapableIds = dualP2pCapableIdsProvider(),
            currentHostId = ride.hostUserId,
            electionEpoch = topologyElectionEpoch
        )
        _topologyPlan.value = plan

        val selectorGoId = StarGroupOwnerSelector.selectGoId(
            StarGroupOwnerSelector.Input(
                meshParticipants = membership.meshParticipants,
                hostUserId = ride.hostUserId,
                previousGoId = prevGoId,
                isP2pConnected = p2pConnected,
                isLiveOfflineVoiceActive = isLiveOfflineVoice,
                excludedGoIds = excludedGoIds.keys,
                positions = positionsMap.mapValues { it.value.latDeg to it.value.lonDeg },
                forceReelect = forceReelect
            )
        )
        val adopted = adoptedGoAnnounce
        val adoptedGoId = if (!isLiveOfflineVoice && !forceReelect && adopted != null) {
            val candidate = membership.meshParticipants.getOrNull(adopted.electedRosterIdx)
            val expectedTag = candidate?.let { ConvoyHash.computeProposerTag(membership.sessionId, it) }
            if (candidate != null &&
                candidate !in excludedGoIds.keys &&
                expectedTag != null &&
                expectedTag.contentEquals(adopted.electedGoTag)
            ) {
                candidate
            } else {
                null
            }
        } else {
            null
        }
        val selectedGoId = adoptedGoId ?: selectorGoId
        lastSelectedGoId = selectedGoId
        if (selectedGoId != null && prevGoId != null && selectedGoId != prevGoId) {
            hasConnectedToCurrentGo = false
            controller.resetFailureCooldown()
            topologyElectionEpoch++
        }
        val isGo = selectedGoId != null && selectedGoId == localId
        controller.ensureActive(backup, isHost = isGo)

        val linkAlive = runCatching { meshTransport.isLinkAlive.value }.getOrDefault(false)
        if (linkAlive) {
            if (positionBridge != null) {
                positionBridge.maybeBuildPositionBroadcast(localId)?.let { posFrame ->
                    meshTransport.sendControlFrame(posFrame)
                }
            }
            if (isGo && selectedGoId != null &&
                (selectedGoId != prevGoId || (nowMs - lastGoAnnounceSentMs) >= MeshConfig.TOPOLOGY_SETTLE_MS)
            ) {
                val rosterIdx = membership.indexOfMeshParticipant(selectedGoId)
                if (rosterIdx in 0..255) {
                    val goTag = ConvoyHash.computeProposerTag(membership.sessionId, selectedGoId)
                    val goFrame = MeshControlExtension.encodeGoAnnounce(
                        MeshControlExtension.GoAnnounce(
                            electionEpoch = topologyElectionEpoch,
                            electedGoTag = goTag,
                            electedRosterIdx = rosterIdx
                        )
                    )
                    meshTransport.sendControlFrame(goFrame)
                    lastGoAnnounceSentMs = nowMs
                }
            }
        }

        runCatching {
            meshTransport.diagnosticsRepo.updateLinkMetadata(
                linkLevel = (localLinkLevel ?: RiderLinkLevel.AUTO_P2P).wireName,
                p2pStatus = controller.status.value.name,
                p2pGoIp = controller.p2pOwnerAddress.value.orEmpty(),
                selectedGoId = selectedGoId.orEmpty()
            )
        }
    }

    private fun emitEdgeStatusNotifications() {
        val notifier = statusNotifier ?: return
        val deafened = runCatching { meshTransport.deafened.value }.getOrDefault(false)
        val curState = _localTransportState.value
        val prevState = lastNotifiedTransportState
        if (curState != prevState) {
            lastNotifiedTransportState = curState
            val event = when (curState) {
                LocalTransportState.STARTING_OFFLINE -> MeshStatusEvent.STARTING_OFFLINE
                LocalTransportState.OFFLINE_ACTIVE -> MeshStatusEvent.OFFLINE_ACTIVE
                LocalTransportState.STARTING_ONLINE -> MeshStatusEvent.STARTING_ONLINE
                LocalTransportState.ONLINE_ACTIVE -> if (prevState in setOf(
                        LocalTransportState.OFFLINE_ACTIVE,
                        LocalTransportState.STARTING_ONLINE,
                        LocalTransportState.RECOVERY_FAILED
                    )
                ) MeshStatusEvent.ONLINE_RECOVERED else null
                else -> null
            }
            event?.let { notifier.notify(it, isDeafened = deafened) }
        }

        val curP2p = wifiDirectGroupController?.status?.value
        val prevP2p = lastNotifiedP2pStatus
        if (curP2p != null && curP2p != prevP2p) {
            lastNotifiedP2pStatus = curP2p
            when (curP2p) {
                WifiDirectStatus.DISABLED_WIFI_OFF -> notifier.notify(
                    MeshStatusEvent.WIFI_OFF,
                    isDeafened = deafened
                )
                WifiDirectStatus.FAILED -> notifier.notify(
                    MeshStatusEvent.LINK_FAILED,
                    isDeafened = deafened,
                    attempts = AndroidWifiDirectGroupController.MAX_SETUP_ATTEMPTS
                )
                else -> Unit
            }
        }
    }
}
