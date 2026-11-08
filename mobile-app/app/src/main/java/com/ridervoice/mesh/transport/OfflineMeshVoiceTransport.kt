package com.ridervoice.mesh.transport

import androidx.annotation.VisibleForTesting
import com.ridervoice.mesh.audio.AudioTrackSink
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Full offline mesh implementation of [VoiceTransport] and [MeshControl].
 * Manages the mesh network lifecycle, forwarding engine, jitter buffer, and audio capture/playback.
 */
class OfflineMeshVoiceTransport(
    identity: MeshIdentity? = null,
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
    private val audioTrackFactory: (() -> AudioTrackSink?)? = null,
    val routeController: MeshAudioRouteController? = null,
    val hardwarePttPort: HardwarePttPort? = null,
    private val identityProvider: () -> MeshIdentity = { identity ?: error("Identity not provided") },
    private val peerFilterPort: com.ridervoice.mesh.forwarding.PeerFilterPort =
        com.ridervoice.mesh.forwarding.PeerFilterPort { id ->
            com.ridervoice.mesh.forwarding.PeerFilterHolder.port?.shouldAccept(id) ?: true
        },
    var onlineIdentitySupplier: () -> String? = { null }
) : VoiceTransport, MeshControl {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var sessionExpiryJob: Job? = null
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
    private var activeIdentity: MeshIdentity? = identity

    val currentIdentity: MeshIdentity
        get() = activeIdentity ?: identityProvider().also { activeIdentity = it }

    private val peerProjector by lazy { MeshPeerProjector(currentIdentity, diagnosticsRepo, clock) }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var lastForwardingEngineForTest: ForwardingEngine? = null

    override val isLinkAlive: StateFlow<Boolean> = _isLinkAlive.asStateFlow()

    val helloIntervalMs = java.util.concurrent.atomic.AtomicLong(MeshConfig.HELLO_INTERVAL_MS)
    override fun setHelloIntervalMs(intervalMs: Long) {
        helloIntervalMs.set(intervalMs)
    }

    private val _audioRoute = MutableStateFlow(AudioRoute.SPEAKER)
    val audioRoute: StateFlow<AudioRoute> = routeController?.activeRoute ?: _audioRoute.asStateFlow()

    private val _deafened = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> = _deafened.asStateFlow()

    override val micController: MicController = MicControllerImpl(
        audioEnabled = { audioPlayback != null },
        applyCapture = { wantCapture ->
            startMutex.withLock {
                if (wantCapture != micWanted) return@withLock
                micWanted = wantCapture
                val cap = audioCapture
                if (cap != null) {
                    cap.setMicEnabled(wantCapture)
                    if (wantCapture) {
                        if (!paused && _state.value == VoiceTransportState.CONNECTED &&
                            audioOwnershipArbiter.owner.value != AudioOwner.OFFLINE_MESH &&
                            audioOwnershipArbiter.owner.value != AudioOwner.LIVEKIT
                        ) {
                            audioOwnershipArbiter.tryClaimOfflineMesh()
                        }
                        val res = cap.start()
                        _micState.value = res.toMicState()
                    } else {
                        cap.stop()
                        _micState.value = if (paused) MicState.MUTED else MicState.OFF
                    }
                } else {
                    _micState.value = if (wantCapture) (if (hasMicPermission()) MicState.STARTING else MicState.PERMISSION_DENIED) else MicState.OFF
                }
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
    private var playbackSpeakersJob: Job? = null
    private var voiceTxJob: Job? = null
    private var voiceTxChannel: Channel<ByteArray>? = null

    @Volatile private var cachedLocalIp: String = ""
    @Volatile private var cachedBroadcastAddr: String = "255.255.255.255"
    @Volatile private var zeroNeighborsSinceMs: Long = clock()

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
            zeroNeighborsSinceMs = clock()

            val currentIdentity = identityProvider()
            activeIdentity = currentIdentity

            val sManager = SessionManager(session, currentIdentity.deviceId, clock)
            sessionManager = sManager

            val gRepo = MeshGraphRepository(
                selfId = currentIdentity.deviceId,
                selfName = currentIdentity.displayName,
                clock = clock,
                selfOnlineTagProvider = {
                    onlineIdentitySupplier()?.let { onlineId ->
                        com.ridervoice.mesh.protocol.PayloadCrypto.computeOnlineIdTag(session.payloadKey(), onlineId)
                    }
                }
            )
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
                selfId = currentIdentity.deviceId,
                selfName = currentIdentity.displayName,
                sessionManager = sManager,
                graphRepository = gRepo,
                routingTable = rTable,
                duplicateCache = dupCache,
                ttlPolicy = ttlPolicy,
                link = uLink,
                clock = clock,
                onLocalVoicePacket = { pkt ->
                    jManager.onVoicePacket(pkt.sourceId, pkt.sequence, pkt.payload, pkt.epoch)
                },
                peerFilterPort = peerFilterPort,
                onlineIdentitySupplier = onlineIdentitySupplier
            )
            forwardingEngine = fEngine
            lastForwardingEngineForTest = fEngine

            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            transportScope = scope

            // Start incoming frame listener BEFORE starting link
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
                    updateProjectedPeers(gRepo.currentSnapshot())
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
                                cachedLocalIp = UdpMeshLink.getLocalIpAddress() ?: ""
                                cachedBroadcastAddr = UdpMeshLink.getLocalBroadcastAddress()
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
                cachedLocalIp = UdpMeshLink.getLocalIpAddress() ?: ""
                cachedBroadcastAddr = UdpMeshLink.getLocalBroadcastAddress()
                while (isActive) {
                    val broadcastEndpoints = broadcastProvider()
                    cachedBroadcastAddr = broadcastEndpoints.firstOrNull()?.host ?: cachedBroadcastAddr
                    if (cachedLocalIp.isBlank()) {
                        cachedLocalIp = UdpMeshLink.getLocalIpAddress() ?: ""
                    }
                    fEngine.originateHello(broadcastEndpoints)
                    delay(helloIntervalMs.get())
                }
            }

            // Periodic graph expiry sweep (every 500ms)
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

            // Periodic session expiry sweep
            sessionExpiryJob?.cancel()
            val remaining = session.expiresAt - clock()
            if (remaining > 0L) {
                sessionExpiryJob = scope.launch {
                    delay(remaining)
                    MeshLog.w("MeshTransport", "session expired id=${session.sessionId.take(8)}")
                    stop()
                }
            }

            _state.value = VoiceTransportState.CONNECTED
        }
    }

    private fun updateProjectedPeers(snapshot: com.ridervoice.mesh.graph.MeshGraphSnapshot) {
        val now = clock()
        val speakers = audioPlayback?.activeSpeakers?.value ?: emptySet()
        val directAlive = graphRepository?.directNeighbors()?.count { (now - it.lastSeen) <= 10_000L } ?: 0
        val endpointAlive = link?.endpoints()?.size ?: 0
        val authNeighbors10s = maxOf(directAlive, endpointAlive)
        if (authNeighbors10s > 0) {
            zeroNeighborsSinceMs = now
        }
        val zeroSecs = if (authNeighbors10s > 0) 0L else ((now - zeroNeighborsSinceMs) / 1000L).coerceAtLeast(0L)

        val rates = link?.datagramRates() ?: Pair(0, 0)
        val jStats = jitterManager?.stats()
        val underruns = audioPlayback?.underrunCount ?: 0L

        val result = peerProjector.project(
            snapshot = snapshot,
            sessionManager = sessionManager,
            routingTable = routingTable,
            forwardingEngine = forwardingEngine,
            speakers = speakers,
            linkState = link?.state?.value ?: MeshLinkState.STOPPED,
            localIp = cachedLocalIp,
            broadcastAddress = cachedBroadcastAddr,
            authenticatedNeighborsLast10s = authNeighbors10s,
            secondsWithZeroNeighbours = zeroSecs,
            selfIdOverride = currentIdentity.deviceId,
            txDatagramsPerSec = rates.first,
            rxDatagramsPerSec = rates.second,
            jitterStats = jStats,
            playoutUnderruns = underruns
        ) ?: return

        _hasValidRoute.value = result.hasValidRoute
        _activeSpeakers.value = result.activeSpeakers
        _participants.value = result.participants
    }

    private fun failEnable(failure: EnableAudioResult.Failure): EnableAudioResult.Failure {
        playbackSpeakersJob?.cancel()
        playbackSpeakersJob = null
        voiceTxJob?.cancel()
        voiceTxJob = null
        voiceTxChannel?.close()
        voiceTxChannel = null
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

        val roomAlreadyOwnedPttSession = (hardwarePttPort?.isSessionActive == true) ||
            (hardwarePttPort?.onMicToggleRequest != null) ||
            (audioOwnershipArbiter.owner.value == AudioOwner.LIVEKIT)

        if (!audioOwnershipArbiter.tryClaimOfflineMesh()) {
            return@withLock failEnable(EnableAudioResult.Failure.OwnershipDenied)
        }

        val jManager = jitterManager ?: return@withLock failEnable(EnableAudioResult.Failure("JitterManager not initialized"))
        val fEngine = forwardingEngine ?: return@withLock failEnable(EnableAudioResult.Failure("ForwardingEngine not initialized"))

        val aPlayback = OfflineAudioPlayback(
            jitterManager = jManager,
            gateAllowed = gateAllowed,
            dispatcher = dispatcher,
            audioTrackFactory = audioTrackFactory
        )
        audioPlayback = aPlayback
        aPlayback.setDeafened(_deafened.value)
        val playbackStarted = aPlayback.start()
        val hasHardwareAudioTrack = try {
            android.media.AudioTrack.getMinBufferSize(
                MeshConfig.SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            ) > 0
        } catch (_: Exception) {
            false
        }
        if (!playbackStarted && (audioTrackFactory != null || hasHardwareAudioTrack)) {
            return@withLock failEnable(EnableAudioResult.Failure.HardwareBusy)
        }

        playbackSpeakersJob?.cancel()
        playbackSpeakersJob = transportScope?.launch {
            aPlayback.activeSpeakers.collectLatest { speakers ->
                _activeSpeakers.value = speakers
                graphRepository?.currentSnapshot()?.let { updateProjectedPeers(it) }
            }
        }

        if (!locksHeld) {
            locks.acquire()
            locksHeld = true
        }
        routeController?.acquireCommunicationMode()
        routeController?.activeRoute?.value?.let { _audioRoute.value = it }

        val txChannel = Channel<ByteArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        voiceTxChannel = txChannel
        voiceTxJob?.cancel()
        voiceTxJob = transportScope?.launch {
            for (pcm in txChannel) {
                fEngine.originateVoice(pcm)
            }
        }

        val aCapture = OfflineAudioCapture(
            gateAllowed = gateAllowed,
            canClaimAudio = { audioOwnershipArbiter.owner.value == AudioOwner.OFFLINE_MESH },
            hasMicPermission = hasMicPermission,
            onHardwareBusy = {
                _micState.value = MicState.HARDWARE_BUSY
                // Stop AudioRecord cleanly without nulling audioCapture so the next PTT press
                // or mic toggle can self-heal and restart capture.
                audioCapture?.stop()
            },
            dispatcher = dispatcher,
            audioRecordFactory = audioRecordFactory,
            onPcmFrame = { pcm ->
                val ch = voiceTxChannel
                if (ch != null) {
                    ch.trySend(pcm)
                } else {
                    fEngine.originateVoice(pcm)
                }
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
        voxBridge.attach(roomAlreadyOwnedPttSession)
        EnableAudioResult.Success
    }

    override suspend fun disableAudio() = startMutex.withLock {
        voxBridge.detach()
        routeController?.releaseCommunicationMode()
        playbackSpeakersJob?.cancel()
        playbackSpeakersJob = null
        voiceTxJob?.cancel()
        voiceTxJob = null
        voiceTxChannel?.close()
        voiceTxChannel = null
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
            val res = enableAudio()
            if (res is EnableAudioResult.Failure) {
                _state.value = VoiceTransportState.FAILED
            }
        }
    }

    override suspend fun stop() = startMutex.withLock {
        withContext(NonCancellable) {
            voxBridge.detach()
            _state.value = VoiceTransportState.STOPPED
            runCatching { forwardingEngine?.originateBye() }

            sessionExpiryJob?.cancel()
            sessionExpiryJob = null

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
            playbackSpeakersJob?.cancel()
            playbackSpeakersJob = null
            voiceTxJob?.cancel()
            voiceTxJob = null
            voiceTxChannel?.close()
            voiceTxChannel = null

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
            forwardingEngine?.close()
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
                if (_state.value == VoiceTransportState.CONNECTED &&
                    audioOwnershipArbiter.owner.value != AudioOwner.OFFLINE_MESH &&
                    audioOwnershipArbiter.owner.value != AudioOwner.LIVEKIT
                ) {
                    audioOwnershipArbiter.tryClaimOfflineMesh()
                }
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

    fun close() {
        voxBridge.close()
        transportScope?.cancel()
        scope.cancel()
    }
}

private fun CaptureStart?.toMicState(paused: Boolean = false): MicState = when (this) {
    CaptureStart.Started -> MicState.ON
    CaptureStart.NoPermission -> MicState.PERMISSION_DENIED
    CaptureStart.NotOwner -> MicState.OWNERSHIP_DENIED
    CaptureStart.HardwareFailed -> MicState.HARDWARE_BUSY
    CaptureStart.GateClosed, null -> if (paused) MicState.MUTED else MicState.OFF
}
