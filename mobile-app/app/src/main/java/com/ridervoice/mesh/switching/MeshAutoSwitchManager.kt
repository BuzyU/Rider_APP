package com.ridervoice.mesh.switching

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
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

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
    private val convoyConfig: ConvoyProtocolConfig = ConvoyProtocolConfig()
) {

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

    @Volatile private var pendingExclusionRequest: ManualExclusionRequest? = null
    @Volatile private var convoyCoordinator: ConvoyTransportCoordinator? = null
    @Volatile private var convoyExecutor: SingleFlightHandoffExecutor? = null
    @Volatile private var activeConvoyMembership: ConvoyMembershipSnapshot? = null
    @Volatile private var lastConvoyOutput: CoordinatorOutput? = null
    @Volatile private var latestSignals: LocalHealthSignals? = null
    @Volatile private var localIncarnation: Long = EncryptedConvoyStateStore.generateSenderIncarnation()
    private val inboundDecisionQueue = ConcurrentLinkedQueue<TransportDecision>()
    private val inboundObservationQueue = ConcurrentLinkedQueue<MemberObservation>()

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

                while (isActive &&
                    activeRideRepository.state.value?.roomId == ride.roomId &&
                    activeRideRepository.state.value?.phase == ActiveRideRepository.Phase.LIVE &&
                    OfflineMeshGate.isOfflineAllowed()
                ) {
                    val membership = activeRideRepository.state.value?.convoyMembershipSnapshot ?: activeConvoyMembership
                    val isMeshCapExceeded = membership?.isMeshCapExceeded == true
                    _moreThanSixParticipantsDisabled.value = isMeshCapExceeded

                    val backup = if (isMeshCapExceeded) null else secretProvider.getSessionForOnlineRide(ride.roomId)
                    if (!isMeshCapExceeded &&
                        (OfflineMeshGate.isWarmStandbyAllowed() || OfflineMeshGate.isAutomaticFallbackAllowed()) &&
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
                    latestSignals = signals

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
                        val now = clock.nowMs()
                        val localNetLost = !signals.isValidatedInternet && !signals.isLiveKitConnected && !signals.isHealthProbeSuccessful
                        if (localNetLost) {
                            if (netLostSinceMs == null) netLostSinceMs = now
                            recoveryStableSinceMs = null
                        } else {
                            netLostSinceMs = null
                            val lkOk = signals.isValidatedInternet && signals.isLiveKitConnected && signals.isHealthProbeSuccessful
                            if (lkOk) {
                                if (recoveryStableSinceMs == null) recoveryStableSinceMs = now
                            } else {
                                recoveryStableSinceMs = null
                            }
                        }
                        val totalLossConfirmed = netLostSinceMs != null && (now - netLostSinceMs) >= convoyConfig.failureConfirmationMs
                        val recoveryStable = recoveryStableSinceMs != null && (now - recoveryStableSinceMs) >= convoyConfig.recoveryStabilityMs

                        val inOfflineMode = convoyCoord.currentAcceptedDecision()?.mode == TransportMode.OFFLINE
                        if (inOfflineMode && !localNetLost && signals.isValidatedInternet && !signals.isLiveKitConnected) {
                            if (validatedOnlineWithoutLkSinceMs == null) validatedOnlineWithoutLkSinceMs = now
                        } else {
                            validatedOnlineWithoutLkSinceMs = null
                        }
                        val probingUnavailable = inOfflineMode && !localNetLost && signals.isValidatedInternet && (
                            liveKitManager.connectionState.value == ConnectionState.FAILED ||
                                (validatedOnlineWithoutLkSinceMs != null && (now - validatedOnlineWithoutLkSinceMs) >= convoyConfig.recoveryStabilityMs + 4_000L)
                            )

                        val decisionsIn = ArrayList<TransportDecision>()
                        while (true) {
                            decisionsIn.add(inboundDecisionQueue.poll() ?: break)
                        }
                        val obsIn = ArrayList<MemberObservation>()
                        while (true) {
                            obsIn.add(inboundObservationQueue.poll() ?: break)
                        }

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
                            localMeshRouteAvailable = signals.hasValidMeshRoute && offlineAvailable,
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
                                canFallbackToMesh = signals.hasValidMeshRoute && offlineAvailable,
                                canRecoverToLiveKit = signals.isValidatedInternet &&
                                    (signals.isLiveKitConnected || (probingUnavailable && _manualReadyAsserted.value))
                            )
                            when (cmd) {
                                is SingleFlightHandoffExecutor.Command.RunFallback -> {
                                    _localTransportState.value = LocalTransportState.STARTING_OFFLINE
                                    val ok = coordinator.fallbackToMesh()
                                    executor.onHandoffFinished(cmd.decision.decisionId, ok, clock.nowMs())
                                    if (ok) {
                                        convoyCoord.recordActualAudioTransition(clock.nowMs())
                                        _localTransportState.value = LocalTransportState.OFFLINE_ACTIVE
                                        _lastTransitionReason.value = "Convoy fallback gen=${cmd.decision.generation}"
                                    } else {
                                        _localTransportState.value = LocalTransportState.FALLBACK_UNAVAILABLE
                                    }
                                }
                                is SingleFlightHandoffExecutor.Command.RunRecovery -> {
                                    _localTransportState.value = LocalTransportState.STARTING_ONLINE
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
                                        owner = coordinator.audioOwnershipArbiter.owner.value
                                    )
                                }
                            }
                        }

                        if (needsBurst && signals.isMeshTransportAlive) {
                            buildControlPayloadBytes(membership, out, signals, localId)?.let { bytes ->
                                meshTransport.sendControlFrame(bytes)
                            }
                        }
                    } else {
                        stateMachine.evaluate(signals, offlineAvailable = offlineAvailable)
                        _localTransportState.value = mapLegacyStateToLocalTransport(stateMachine.state.value, isMeshCapExceeded)
                    }

                    val isLocalOffline = stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE ||
                        coordinator.audioOwnershipArbiter.owner.value == AudioOwner.OFFLINE_MESH
                    val unifiedPeers = buildUnifiedParticipantList(isLocalOffline)
                    _groupStatus.value = computeGroupConnectivity(isLocalOffline, unifiedPeers)

                    val tickDelayMs = 1_000L
                    delay(tickDelayMs)
                }

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
        val payloadKey = session?.payloadKey()

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
        scope.cancel()
    }

    private fun initConvoySessionState(ride: ActiveRideRepository.ActiveRide) {
        val persistedState = convoyPersistence?.load(ConvoyHash.stableSessionUuid(ride.roomId))
        val snap = ride.convoyMembershipSnapshot ?: persistedState?.membership
        activeConvoyMembership = snap
        inboundDecisionQueue.clear()
        inboundObservationQueue.clear()
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
                val res = ConvoyStateCodec.decode(pkt.payload, currentSnap.meshParticipants.size)
                if (res is ConvoyStateDecodeResult.Ok) {
                    res.payload.decision?.let { inboundDecisionQueue.add(it) }
                    val rxMs = clock.nowMs()
                    for (rec in res.payload.records) {
                        val originId = currentSnap.meshParticipants.getOrNull(rec.rosterIdx) ?: continue
                        inboundObservationQueue.add(rec.toObservation(originId, rxMs))
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
        inboundDecisionQueue.clear()
        inboundObservationQueue.clear()
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
        owner: AudioOwner
    ): LocalTransportState = when {
        convoyCoord.isGenerationUnknown() -> LocalTransportState.GENERATION_UNKNOWN
        convoyCoord.isGenerationExhausted() -> LocalTransportState.GENERATION_EXHAUSTED
        owner == AudioOwner.OFFLINE_MESH -> LocalTransportState.OFFLINE_ACTIVE
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
}
