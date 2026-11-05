package com.ridervoice.mesh.transport

import com.ridervoice.mesh.audio.HardwarePttPort
import com.ridervoice.mesh.audio.MeshAudioRouteController
import com.ridervoice.mesh.audio.MeshVoxBridge
import com.ridervoice.mesh.audio.MicController
import com.ridervoice.mesh.audio.MicControllerImpl
import com.ridervoice.mesh.audio.MicRules
import com.ridervoice.mesh.audio.MicInputs

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.MeshLog
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.audio.AudioRecordSource
import com.ridervoice.mesh.audio.CaptureStart
import com.ridervoice.mesh.audio.MultiSourceJitterBufferManager
import com.ridervoice.mesh.audio.OfflineAudioCapture
import com.ridervoice.mesh.audio.OfflineAudioPlayback
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsSnapshot
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.link.NoOpWifiNetworkWatcher
import com.ridervoice.mesh.link.WifiNetworkWatcher
import com.ridervoice.mesh.link.NetworkLocks
import com.ridervoice.mesh.link.NoNetworkLocks
import com.ridervoice.mesh.link.UdpMeshLink
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Full offline mesh implementation of [VoiceTransport] and [MeshControl].
 * Manages the mesh network lifecycle, forwarding engine, jitter buffer, and audio capture/playback.
 */
class OfflineMeshVoiceTransport(
    private var identity: MeshIdentity,
    val audioOwnershipArbiter: AudioOwnershipArbiter,
    private val onAudioRouteChanged: (AudioRoute) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gateAllowed: () -> Boolean = { OfflineMeshGate.isOfflineAllowed() },
    private val linkFactory: () -> MeshLink = {
        UdpMeshLink(
            bindPort = MeshConfig.MESH_PORT,
            gateAllowed = gateAllowed,
            dispatcher = dispatcher
        )
    },
    private val locks: NetworkLocks = NoNetworkLocks,
    private val wifiWatcher: WifiNetworkWatcher = NoOpWifiNetworkWatcher,
    val diagnosticsRepo: MeshDiagnosticsRepository = MeshDiagnosticsRepository(),
    private val broadcastProvider: () -> List<MeshEndpoint> = {
        UdpMeshLink.discoverBroadcastEndpoints(MeshConfig.MESH_PORT)
    },
    private val hasMicPermission: () -> Boolean = { true },
    private val audioRecordFactory: (() -> AudioRecordSource?)? = null,
    val routeController: MeshAudioRouteController? = null,
    val hardwarePttPort: HardwarePttPort? = null,
    private val identityProvider: () -> MeshIdentity = { identity }
) : VoiceTransport, MeshControl {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val startMutex = Mutex()
    private var locksHeld = false
    private var multicastHeld = false
    private val linkRestartMutex = Mutex()
    private var linkSupervisorJob: Job? = null
    private var wifiWatcherJob: Job? = null
    private val _state = MutableStateFlow(VoiceTransportState.STOPPED)
    override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()

    val diagnostics: StateFlow<MeshDiagnosticsSnapshot> = diagnosticsRepo.snapshot

    private val _isLinkAlive = MutableStateFlow(true)
    override val isLinkAlive: StateFlow<Boolean> = _isLinkAlive.asStateFlow()

    val helloIntervalMs = java.util.concurrent.atomic.AtomicLong(MeshConfig.HELLO_INTERVAL_MS)
    override fun setHelloIntervalMs(intervalMs: Long) {
        helloIntervalMs.set(intervalMs)
    }

    private val _audioRoute = MutableStateFlow(AudioRoute.SPEAKER)
    val audioRoute: StateFlow<AudioRoute> = routeController?.activeRoute ?: _audioRoute.asStateFlow()

    private val _deafened = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> = _deafened.asStateFlow()

    val currentIdentity: MeshIdentity get() = identity

    override val micController: MicController = MicControllerImpl(
        audioEnabled = { audioPlayback != null && audioOwnershipArbiter.owner.value == AudioOwner.OFFLINE_MESH },
        applyCapture = { wantCapture ->
            micWanted = wantCapture
            val cap = audioCapture
            if (cap != null) {
                cap.setMicEnabled(wantCapture)
                if (wantCapture) {
                    val res = cap.start()
                    _micState.value = res.toMicState()
                } else {
                    cap.stop()
                    _micState.value = if (paused) MicState.MUTED else MicState.OFF
                }
            } else {
                _micState.value = if (wantCapture) (if (hasMicPermission()) MicState.STARTING else MicState.PERMISSION_DENIED) else MicState.OFF
            }
        },
        scope = scope
    )

    val voxBridge: MeshVoxBridge = MeshVoxBridge(this, hardwarePttPort = hardwarePttPort, dispatcher = dispatcher)

    private val _micState = MutableStateFlow(MicState.OFF)
    override val micState: StateFlow<MicState> = _micState.asStateFlow()

    private val _participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
    override val participants: StateFlow<List<UnifiedVoicePeer>> = _participants.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    override val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    override val audioOwner: StateFlow<AudioOwner> = audioOwnershipArbiter.owner

    private val _hasValidRoute = MutableStateFlow(false)
    override val hasValidRoute: StateFlow<Boolean> = _hasValidRoute.asStateFlow()

    private var transportScope: CoroutineScope? = null
    private var heartbeatJob: Job? = null
    private var frameListenerJob: Job? = null
    private var peerProjectorJob: Job? = null
    private var expireJob: Job? = null

    private var sessionManager: SessionManager? = null
    private var link: MeshLink? = null
    private var graphRepository: MeshGraphRepository? = null
    private var routingTable: RoutingTable? = null
    private var forwardingEngine: ForwardingEngine? = null
    private var jitterManager: MultiSourceJitterBufferManager? = null
    private var audioCapture: OfflineAudioCapture? = null
    private var audioPlayback: OfflineAudioPlayback? = null

    private var currentSession: OfflineSession? = null

    override suspend fun startDiscoveryOnly(session: OfflineSession) {
        val running = currentSession
        val active = _state.value == VoiceTransportState.CONNECTED || _state.value == VoiceTransportState.STARTING
        if (active && running != null && running.sessionId != session.sessionId) {
            stop()
        }
        startMutex.withLock {
            if (_state.value == VoiceTransportState.CONNECTED || _state.value == VoiceTransportState.STARTING) {
                if (currentSession?.sessionId == session.sessionId) return@withLock
            }

        if (!gateAllowed()) {
            _state.value = VoiceTransportState.FAILED
            return@withLock
        }

        _state.value = VoiceTransportState.STARTING
        currentSession = session

        val currentIdentity = identityProvider()
        identity = currentIdentity

        val sManager = SessionManager(session, currentIdentity.deviceId, clock)
        sessionManager = sManager

        val gRepo = MeshGraphRepository(identity.deviceId, identity.displayName, clock)
        graphRepository = gRepo

        val rTable = RoutingTable()
        routingTable = rTable

        val dupCache = DuplicatePacketCache(clock = clock)
        val ttlPolicy = TtlPolicy(clock = clock)

        val jManager = MultiSourceJitterBufferManager(clock = clock)
        jitterManager = jManager

        val uLink = linkFactory()
        link = uLink

        val fEngine = ForwardingEngine(
            selfId = identity.deviceId,
            selfName = identity.displayName,
            sessionManager = sManager,
            graphRepository = gRepo,
            routingTable = rTable,
            duplicateCache = dupCache,
            ttlPolicy = ttlPolicy,
            link = uLink,
            clock = clock,
            onLocalVoicePacket = { pkt ->
                jManager.onVoicePacket(pkt.sourceId, pkt.sequence, pkt.payload, pkt.epoch)
            }
        )
        forwardingEngine = fEngine

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        transportScope = scope

        // Start incoming frame listener BEFORE starting link (D6)
        frameListenerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            uLink.incomingFrames.collect { frame: MeshFrame ->
                fEngine.onIncomingFrame(frame)
            }
        }

        uLink.start()
        if (uLink.state.value != com.ridervoice.mesh.link.MeshLinkState.RUNNING) {
            frameListenerJob?.cancel()
            frameListenerJob = null
            scope.cancel()
            transportScope = null
            link = null
            _state.value = VoiceTransportState.FAILED
            _isLinkAlive.value = false
            if (locksHeld) {
                locks.release()
                locksHeld = false
            }
            return@withLock
        }



        locks.acquireMulticast()
        multicastHeld = true
        wifiWatcher.start()

        linkSupervisorJob = scope.launch {
            uLink.state.collectLatest { linkState ->
                val isRunning = linkState == MeshLinkState.RUNNING
                _isLinkAlive.value = isRunning
                if (!isRunning && (_state.value == VoiceTransportState.CONNECTED || _state.value == VoiceTransportState.STARTING)) {
                    var backoffMs = 250L
                    val failureStartMs = clock()
                    while (isActive && uLink.state.value != MeshLinkState.RUNNING && (_state.value == VoiceTransportState.CONNECTED || _state.value == VoiceTransportState.STARTING)) {
                        val now = clock()
                        if (now - failureStartMs >= 30_000L) {
                            MeshLog.w("MeshTransport", "Link supervisor: persistent restart failure for 30s -> marking FAILED")
                            _state.value = VoiceTransportState.FAILED
                            _isLinkAlive.value = false
                            break
                        }
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(4_000L)
                        linkRestartMutex.withLock {
                            try {
                                uLink.stop()
                                uLink.start()
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                MeshLog.w("MeshTransport", "Link supervisor restart failed: ${e.message}")
                            }
                        }
                    }
                    _isLinkAlive.value = (uLink.state.value == MeshLinkState.RUNNING)
                }
            }
        }

        wifiWatcherJob = scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            wifiWatcher.wifiEvents.debounce(500L).collect { event ->
                if (_state.value == VoiceTransportState.CONNECTED) {
                    MeshLog.d("MeshTransport", "Wi-Fi change ($event): restarting link to rebind socket")
                    linkRestartMutex.withLock {
                        try {
                            uLink.stop()
                            uLink.start()
                            _isLinkAlive.value = (uLink.state.value == MeshLinkState.RUNNING)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            MeshLog.w("MeshTransport", "Link restart on Wi-Fi change failed: ${e.message}")
                        }
                    }
                }
            }
        }

        // Periodic discovery / heartbeat job (every 1.5s)
        heartbeatJob = scope.launch {
            while (isActive) {
                val broadcastEndpoints = broadcastProvider()
                fEngine.originateHello(broadcastEndpoints)
                delay(helloIntervalMs.get())
            }
        }        // Periodic graph expiry sweep (every 1.0s)
        expireJob = scope.launch {
            while (isActive) {
                delay(500L)
                val expired = gRepo.expire()
                if (expired) {
                    rTable.recompute(gRepo.currentSnapshot(), clock())
                }
                updateProjectedPeers(gRepo.currentSnapshot())
            }
        }

        // Project mesh peers to UnifiedVoicePeer flow and monitor route availability
        peerProjectorJob = scope.launch {
            gRepo.snapshots.collect { snapshot ->
                updateProjectedPeers(snapshot)
            }
        }

        updateProjectedPeers(gRepo.currentSnapshot())

        // Periodic session expiry sweep (WP-39 / A23)
        val remaining = session.expiresAt - clock()
        if (remaining > 0L) {
            scope.launch {
                delay(remaining)
                MeshLog.w("MeshTransport", "session expired id=${session.sessionId.take(8)}")
                stop()
            }
        }

        _state.value = VoiceTransportState.CONNECTED
        }
    }

    private fun updateProjectedPeers(snapshot: com.ridervoice.mesh.graph.MeshGraphSnapshot) {
        val sManager = sessionManager ?: return
        val rTable = routingTable ?: return
        val fEngine = forwardingEngine ?: return
        val now = clock()
        val routes = rTable.routes.value
        _hasValidRoute.value = routes.isNotEmpty()

        val speakers = audioPlayback?.activeSpeakers?.value ?: emptySet()
        _activeSpeakers.value = speakers

        val offlineDevices = com.ridervoice.mesh.transport.UnifiedVoicePeer.projectMeshGraph(snapshot, routes, speakers, now)
        _participants.value = offlineDevices.map { com.ridervoice.mesh.transport.UnifiedVoicePeer.fromOffline(it, now) }

        val cnt = fEngine.counters()
        diagnosticsRepo.update(
            selfDeviceId = identity.deviceId,
            sessionId = sManager.session.sessionId,
            memberCount = sManager.members().size,
            graph = snapshot,
            routes = routes,
            receivedVoiceFrames = cnt.received,
            forwardedVoiceFrames = cnt.forwarded,
            droppedDuplicates = cnt.dup,
            droppedInvalid = cnt.invalid,
            droppedTtl = cnt.ttl
        )
    }

    private fun failEnable(failure: EnableAudioResult.Failure): EnableAudioResult.Failure {
        audioCapture?.stop()
        audioCapture = null
        audioPlayback?.stop()
        audioPlayback = null
        routeController?.releaseCommunicationMode()
        if (locksHeld) {
            locks.release()
            locksHeld = false
        }
        audioOwnershipArbiter.releaseSync(AudioOwner.OFFLINE_MESH)
        _micState.value = MicState.OFF
        return failure
    }

    override suspend fun enableAudio(): EnableAudioResult = startMutex.withLock {
        if (_state.value != VoiceTransportState.CONNECTED) {
            return@withLock failEnable(EnableAudioResult.Failure("Mesh transport must be in discovery/CONNECTED state before enabling audio"))
        }
        if (audioPlayback != null && audioOwnershipArbiter.owner.value == AudioOwner.OFFLINE_MESH) {
            return@withLock EnableAudioResult.Success // Already enabled
        }

        if (!audioOwnershipArbiter.tryClaimOfflineMesh()) {
            return@withLock failEnable(EnableAudioResult.Failure.OwnershipDenied)
        }

        val jManager = jitterManager ?: return@withLock failEnable(EnableAudioResult.Failure("JitterManager not initialized"))
        val fEngine = forwardingEngine ?: return@withLock failEnable(EnableAudioResult.Failure("ForwardingEngine not initialized"))

        val aPlayback = OfflineAudioPlayback(
            jitterManager = jManager,
            gateAllowed = gateAllowed,
            dispatcher = dispatcher
        )
        audioPlayback = aPlayback
        aPlayback.setDeafened(_deafened.value)
        aPlayback.start()

        if (!locksHeld) {
            locks.acquire()
            locksHeld = true
        }
        routeController?.acquireCommunicationMode()

        val aCapture = OfflineAudioCapture(
            gateAllowed = gateAllowed,
            canClaimAudio = { audioOwnershipArbiter.owner.value == AudioOwner.OFFLINE_MESH },
            hasMicPermission = hasMicPermission,
            onHardwareBusy = {
                _micState.value = MicState.HARDWARE_BUSY
                audioCapture?.stop()
                audioCapture = null
                transportScope?.launch {
                    audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)
                }
            },
            dispatcher = dispatcher,
            audioRecordFactory = audioRecordFactory,
            onPcmFrame = { pcm ->
                fEngine.originateVoice(pcm)
            }
        )
        aCapture.frameGate = { pcm: ByteArray -> micController.shouldTransmitPcmFrame(pcm) }
        audioCapture = aCapture
        aCapture.setMicEnabled(micWanted)

        val wantCapture = micWanted || micController.micToggleOn.value || micController.pttHeld.value || micController.voxEnabled.value
        if (wantCapture) {
            _micState.value = MicState.STARTING
            val res = aCapture.start()
            _micState.value = res.toMicState()
            if (res == CaptureStart.HardwareFailed) {
                return@withLock failEnable(EnableAudioResult.Failure.HardwareBusy)
            }
            if (res == CaptureStart.GateClosed) {
                return@withLock failEnable(EnableAudioResult.Failure.GateClosed)
            }
            if (res == CaptureStart.NoPermission) {
                return@withLock failEnable(EnableAudioResult.Failure("NO_PERMISSION"))
            }
            if (res == CaptureStart.NotOwner) {
                return@withLock failEnable(EnableAudioResult.Failure.OwnershipDenied)
            }
        }
        voxBridge.attach(audioOwnershipArbiter.owner.value == AudioOwner.LIVEKIT)
        EnableAudioResult.Success
    }

    override suspend fun disableAudio() = startMutex.withLock {
        voxBridge.detach()
        routeController?.releaseCommunicationMode()
        audioCapture?.stop()
        audioCapture = null
        _micState.value = MicState.OFF

        audioPlayback?.stop()
        audioPlayback = null

        if (locksHeld) {
            locks.release()
            locksHeld = false
        }
        audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)
    }

    override suspend fun pauseCaptureOnly() {
        startMutex.withLock {
            audioCapture?.stop()
            _micState.value = MicState.MUTED
        }
    }

    override suspend fun resumeCaptureOnly() {
        startMutex.withLock {
            if (audioOwnershipArbiter.owner.value == AudioOwner.OFFLINE_MESH) {
                val cap = audioCapture
                if (cap != null) {
                    val wantCapture = micWanted || micController.micToggleOn.value || micController.pttHeld.value || micController.voxEnabled.value
                    if (wantCapture) {
                        _micState.value = MicState.STARTING
                        val res = cap.start()
                        _micState.value = res.toMicState()
                    } else {
                        _micState.value = MicState.OFF
                    }
                }
            }
        }
    }

    override suspend fun start(session: VoiceSession) {
        val offlineSession = when (session) {
            is VoiceSession.Offline -> session.session
            is VoiceSession.Online -> throw IllegalArgumentException("OfflineMeshVoiceTransport cannot start online session")
        }
        startDiscoveryOnly(offlineSession)
        if (_state.value == VoiceTransportState.CONNECTED) {
            enableAudio()
        }
    }

    override suspend fun stop() = startMutex.withLock {
        voxBridge.detach()
        _state.value = VoiceTransportState.STOPPED
        runCatching { forwardingEngine?.originateBye() }

        linkSupervisorJob?.cancel()
        linkSupervisorJob = null
        wifiWatcherJob?.cancel()
        wifiWatcherJob = null
        wifiWatcher.stop()
        if (multicastHeld) {
            locks.releaseMulticast()
            multicastHeld = false
        }
        _isLinkAlive.value = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        frameListenerJob?.cancel()
        frameListenerJob = null
        peerProjectorJob?.cancel()
        peerProjectorJob = null
        expireJob?.cancel()
        expireJob = null

        audioCapture?.stop()
        audioCapture = null

        audioPlayback?.stop()
        audioPlayback = null
        routeController?.releaseCommunicationMode()

        link?.stop()
        link = null

        transportScope?.cancel()
        transportScope = null
        sessionManager = null
        graphRepository = null
        routingTable = null
        forwardingEngine = null
        jitterManager = null
        currentSession = null

        _hasValidRoute.value = false
        _participants.value = emptyList()
        _activeSpeakers.value = emptySet()
        diagnosticsRepo.reset()
        if (locksHeld) {
            locks.release()
            locksHeld = false
        }
        audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)

        micController.reset()
        micWanted = false
        paused = false
        _micState.value = MicState.OFF
    }

    private var micWanted = false
    private var paused = false

    override suspend fun pause() {
        if (paused || _state.value != VoiceTransportState.CONNECTED) return
        paused = true
        _micState.value = MicState.MUTED
        audioCapture?.stop()
        audioPlayback?.stop()
        if (locksHeld) {
            locks.release()
            locksHeld = false
        }
        audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)
    }

    override suspend fun resume() {
        if (!paused) return
        if (!audioOwnershipArbiter.tryClaimOfflineMesh()) return
        paused = false
        if (!locksHeld) {
            locks.acquire()
            locksHeld = true
        }
        audioPlayback?.start()
        if (micWanted) {
            val res = audioCapture?.start()
            _micState.value = res.toMicState()
        }
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) = startMutex.withLock {
        micWanted = enabled
        micController.setMicToggled(enabled)
        if (paused) return@withLock
        val cap = audioCapture
        if (cap != null) {
            val want = MicRules.captureWanted(MicInputs(true, enabled, micController.pttHeld.value, micController.voxEnabled.value, micController.voxArmed.value))
            cap.setMicEnabled(want)
            if (want) {
                val res = cap.start()
                _micState.value = res.toMicState()
            } else {
                cap.stop()
                _micState.value = if (paused) MicState.MUTED else MicState.OFF
            }
        } else {
            _micState.value = if (enabled) (if (hasMicPermission()) MicState.STARTING else MicState.PERMISSION_DENIED) else MicState.OFF
        }
    }

    override suspend fun setDeafened(deafened: Boolean) {
        _deafened.value = deafened
        audioPlayback?.setDeafened(deafened)
    }

    override suspend fun setAudioRoute(route: AudioRoute) {
        _audioRoute.value = route
        routeController?.setRoute(route)
        onAudioRouteChanged(route)
    }

    fun isCapturing(): Boolean = audioCapture?.isCapturing?.value == true
    fun isPlaying(): Boolean = audioPlayback?.isPlaying?.value == true
}
private fun CaptureStart?.toMicState(paused: Boolean = false): MicState = when (this) {
    CaptureStart.Started -> MicState.ON
    CaptureStart.NoPermission -> MicState.PERMISSION_DENIED
    CaptureStart.NotOwner -> MicState.OWNERSHIP_DENIED
    CaptureStart.HardwareFailed -> MicState.HARDWARE_BUSY
    CaptureStart.GateClosed, null -> if (paused) MicState.MUTED else MicState.OFF
}
