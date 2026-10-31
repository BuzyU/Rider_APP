package com.ridervoice.mesh.transport

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.audio.MultiSourceJitterBufferManager
import com.ridervoice.mesh.audio.OfflineAudioCapture
import com.ridervoice.mesh.audio.OfflineAudioPlayback
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.forwarding.TtlPolicy
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.UdpMeshLink
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
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

/**
 * Full offline mesh implementation of [VoiceTransport].
 * Manages the mesh network lifecycle, forwarding engine, jitter buffer, and audio capture/playback.
 */
class OfflineMeshVoiceTransport(
    private val identity: MeshIdentity,
    private val audioOwnershipArbiter: AudioOwnershipArbiter,
    private val onAudioRouteChanged: (AudioRoute) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : VoiceTransport {

    private val _state = MutableStateFlow(VoiceTransportState.STOPPED)
    override val state: StateFlow<VoiceTransportState> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
    override val participants: StateFlow<List<UnifiedVoicePeer>> = _participants.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    override val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    override val audioOwner: StateFlow<AudioOwner> = audioOwnershipArbiter.owner

    private var transportScope: CoroutineScope? = null
    private var heartbeatJob: Job? = null
    private var frameListenerJob: Job? = null
    private var peerProjectorJob: Job? = null

    private var sessionManager: SessionManager? = null
    private var link: UdpMeshLink? = null
    private var graphRepository: MeshGraphRepository? = null
    private var routingTable: RoutingTable? = null
    private var forwardingEngine: ForwardingEngine? = null
    private var jitterManager: MultiSourceJitterBufferManager? = null
    private var audioCapture: OfflineAudioCapture? = null
    private var audioPlayback: OfflineAudioPlayback? = null

    private var currentSession: OfflineSession? = null

    override suspend fun start(session: VoiceSession) {
        val offlineSession = when (session) {
            is VoiceSession.Offline -> session.session
            is VoiceSession.Online -> throw IllegalArgumentException("OfflineMeshVoiceTransport cannot start online session")
        }

        if (!OfflineMeshGate.isOfflineAllowed()) {
            _state.value = VoiceTransportState.FAILED
            return
        }

        if (!audioOwnershipArbiter.tryClaimOfflineMesh()) {
            _state.value = VoiceTransportState.FAILED
            return
        }

        _state.value = VoiceTransportState.STARTING
        currentSession = offlineSession

        val sManager = SessionManager(offlineSession, identity.deviceId, clock)
        sessionManager = sManager

        val gRepo = MeshGraphRepository(identity.deviceId, identity.displayName, clock)
        graphRepository = gRepo

        val rTable = RoutingTable()
        routingTable = rTable

        val dupCache = DuplicatePacketCache(clock = clock)
        val ttlPolicy = TtlPolicy(clock = clock)

        val jManager = MultiSourceJitterBufferManager(clock = clock)
        jitterManager = jManager

        val uLink = UdpMeshLink(
            bindPort = MeshConfig.AUDIO_PORT,
            gateAllowed = { OfflineMeshGate.isOfflineAllowed() },
            dispatcher = dispatcher
        )
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
                jManager.onVoicePacket(pkt.sourceId, pkt.sequence, pkt.payload)
            }
        )
        forwardingEngine = fEngine

        val aCapture = OfflineAudioCapture(
            gateAllowed = { OfflineMeshGate.isOfflineAllowed() },
            canClaimAudio = { audioOwnershipArbiter.owner.value == AudioOwner.OFFLINE_MESH },
            dispatcher = dispatcher,
            onPcmFrame = { pcm ->
                fEngine.originateVoice(pcm)
            }
        )
        audioCapture = aCapture

        val aPlayback = OfflineAudioPlayback(
            jitterManager = jManager,
            gateAllowed = { OfflineMeshGate.isOfflineAllowed() },
            dispatcher = dispatcher
        )
        audioPlayback = aPlayback

        uLink.start()
        aPlayback.start()

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        transportScope = scope

        // Start incoming frame listener
        frameListenerJob = scope.launch {
            uLink.incomingFrames.collect { frame ->
                fEngine.onIncomingFrame(frame)
            }
        }

        // Start periodic discovery / heartbeat job (every 1.5s)
        heartbeatJob = scope.launch {
            val broadcastEndpoints = UdpMeshLink.discoverBroadcastEndpoints(MeshConfig.AUDIO_PORT)
            while (isActive) {
                fEngine.originateHello(broadcastEndpoints)
                delay(MeshConfig.HELLO_INTERVAL_MS)
            }
        }

        // Project mesh peers to UnifiedVoicePeer flow
        peerProjectorJob = scope.launch {
            while (isActive) {
                val now = clock()
                val snapshot = gRepo.currentSnapshot()
                val routes = rTable.routes.value
                val speakers = aPlayback.activeSpeakers.value
                _activeSpeakers.value = speakers

                val offlineDevices = UnifiedVoicePeer.projectMeshGraph(snapshot, routes, speakers, now)
                _participants.value = offlineDevices.map { UnifiedVoicePeer.fromOffline(it, now) }
                delay(500L)
            }
        }

        _state.value = VoiceTransportState.CONNECTED
    }

    override suspend fun stop() {
        _state.value = VoiceTransportState.STOPPED
        runCatching { forwardingEngine?.originateBye() }

        heartbeatJob?.cancel()
        heartbeatJob = null
        frameListenerJob?.cancel()
        frameListenerJob = null
        peerProjectorJob?.cancel()
        peerProjectorJob = null

        audioCapture?.stop()
        audioCapture = null

        audioPlayback?.stop()
        audioPlayback = null

        link?.stop()
        link = null

        transportScope = null
        sessionManager = null
        graphRepository = null
        routingTable = null
        forwardingEngine = null
        jitterManager = null
        currentSession = null

        _participants.value = emptyList()
        _activeSpeakers.value = emptySet()
        audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        val capture = audioCapture ?: return
        capture.setMicEnabled(enabled)
        if (enabled) {
            capture.start()
        } else {
            capture.stop()
        }
    }

    override suspend fun setDeafened(deafened: Boolean) {
        audioPlayback?.setDeafened(deafened)
    }

    override suspend fun setAudioRoute(route: AudioRoute) {
        onAudioRouteChanged(route)
    }

    fun isCapturing(): Boolean = audioCapture?.isCapturing?.value == true
    fun isPlaying(): Boolean = audioPlayback?.isPlaying?.value == true
}

