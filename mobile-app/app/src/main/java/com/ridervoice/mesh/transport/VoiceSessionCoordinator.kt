package com.ridervoice.mesh.transport

import com.ridervoice.mesh.MeshLog
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.OfflineAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stage 2 Coordinator managing the active voice transport and ensuring atomic transitions.
 *
 * Guarantees:
 * - Exactly one transport is active at any time.
 * - Previous transport is completely stopped before a new transport starts.
 * - Audio ownership is strictly arbitrated to prevent dual AudioRecord/WebRTC contention.
 * - Single-capture quiet guard: verifies LiveKit capture quiet (muted && VOX stopped) for >= 400 ms.
 * - Safe fallback to mesh with automatic rollback on failure (D3).
 * - Safe recovery to LiveKit with automatic rollback to mesh on failure (D3).
 */
class VoiceSessionCoordinator(
    val liveKitTransport: VoiceTransport,
    val meshTransport: VoiceTransport,
    val audioOwnershipArbiter: AudioOwnershipArbiter,
    val secretProvider: OfflineSecretProvider = NullOfflineSecretProvider(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        if (exception is CancellationException) throw exception
        MeshLog.w("VoiceSessionCoordinator", "unhandled coroutine error: ${exception.message}")
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler)
    private val switchMutex = Mutex()
    @Volatile private var lastOnlineSession: VoiceSession.Online? = null
    private val onlineSession = MutableStateFlow<VoiceSession.Online?>(null)

    private fun availability(ride: VoiceSession.Online?, s: OfflineSession?, now: Long): OfflineAvailability = when {
        !OfflineMeshGate.isOfflineAllowed() -> OfflineAvailability.DISABLED
        ride != null && s != null && !s.isExpired(now) -> OfflineAvailability.AVAILABLE
        else -> {
            val roomId = ride?.roomId
            val secret = if (roomId != null) secretProvider.getSecretForOnlineRide(roomId) else null
            if (ride != null && secret != null) OfflineAvailability.AVAILABLE else OfflineAvailability.NO_SHARED_SECRET
        }
    }

    private val _offlineAvailability = MutableStateFlow(availability(null, secretProvider.backupSession.value, clock()))
    val offlineAvailability: StateFlow<OfflineAvailability> = _offlineAvailability.asStateFlow()

    private val _activeKind = MutableStateFlow(PeerTransportKind.ONLINE_LIVEKIT)
    val activeKind: StateFlow<PeerTransportKind> = _activeKind.asStateFlow()

    private val _activeTransport = MutableStateFlow(liveKitTransport)
    val activeTransport: StateFlow<VoiceTransport> = _activeTransport.asStateFlow()

    private val _transportState = MutableStateFlow(VoiceTransportState.STOPPED)
    val transportState: StateFlow<VoiceTransportState> = _transportState.asStateFlow()

    private val _participants = MutableStateFlow<List<UnifiedVoicePeer>>(emptyList())
    val participants: StateFlow<List<UnifiedVoicePeer>> = _participants.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    init {
        scope.launch {
            combine(secretProvider.backupSession, onlineSession, OfflineMeshGate.settingsFlow) { s, ride, _ -> s to ride }
                .collectLatest { (s, ride) ->
                    _offlineAvailability.value = availability(ride, s, clock())
                    if (s != null && !s.isExpired(clock())) {
                        delay((s.expiresAt - clock()).coerceAtLeast(0L)) // flips at expiry (A33)
                        _offlineAvailability.value = availability(ride, s, clock())
                    }
                }
        }

        // Dynamically proxy state from whichever transport is currently active
        scope.launch {
            _activeTransport.collectLatest { transport ->
                launch {
                    transport.state.collectLatest { _transportState.value = it }
                }
                launch {
                    transport.participants.collectLatest { _participants.value = it }
                }
                launch {
                    transport.activeSpeakers.collectLatest { _activeSpeakers.value = it }
                }
            }
        }
    }

    fun setOnlineSession(session: VoiceSession.Online?) {
        lastOnlineSession = session
        onlineSession.value = session
        _offlineAvailability.value = availability(session, secretProvider.backupSession.value, clock())
    }

    suspend fun ensureWarmStandbyDiscovery(session: OfflineSession) {
        val mesh = meshTransport as? MeshControl ?: return
        if (meshTransport.state.value == VoiceTransportState.STOPPED) {
            mesh.startDiscoveryOnly(session)
        }
    }

    /**
     * Atomically switches active transport.
     */
    suspend fun switchToTransport(
        targetKind: PeerTransportKind,
        newSession: VoiceSession
    ) = switchMutex.withLock {
        val current = _activeTransport.value
        val next = when (targetKind) {
            PeerTransportKind.ONLINE_LIVEKIT -> liveKitTransport
            PeerTransportKind.OFFLINE_MESH -> meshTransport
        }

        if (current !== next) {
            current.stop()
        }

        _activeKind.value = targetKind
        _activeTransport.value = next
        if (newSession is VoiceSession.Online) {
            setOnlineSession(newSession)
        }
        next.start(newSession)
    }

    /**
     * Option B: Fallback to offline mesh when online degradation occurs (D3).
     *
     * Precondition: mesh must report a valid route.
     * Suspends LiveKit, verifies 400ms quiet window, claims OFFLINE_MESH, and enables mesh audio.
     * On failure: rolls back to LiveKit with retained online session.
     */
    suspend fun fallbackToMesh(): Boolean = switchMutex.withLock {
        val mesh = meshTransport as? MeshControl ?: return@withLock false
        if (!mesh.hasValidRoute.value) return@withLock false
        val priorMicWanted = liveKitTransport.micState.value == MicState.ON
        val lk = liveKitTransport as? LiveKitVoiceTransport
        val priorVoxActive = lk?.isVoxRunning == true || lk?.savedVoxRunningBeforePause == true
        liveKitTransport.pause()
        if (!verifyLiveKitQuiet(2_000L)) { rollbackToLiveKit(); return@withLock false }
        if (!audioOwnershipArbiter.handoffLiveKitToOfflineMesh { }) { rollbackToLiveKit(); return@withLock false }
        val ok = runCatching { mesh.enableAudio() }.getOrElse { EnableAudioResult.Failure(it.javaClass.simpleName) } is EnableAudioResult.Success &&
            meshTransport.micState.value != MicState.HARDWARE_BUSY
        if (!ok) { rollbackToLiveKit(); return@withLock false } // NEVER liveKitTransport.stop() here (A9)
        _activeKind.value = PeerTransportKind.OFFLINE_MESH
        _activeTransport.value = meshTransport
        if (priorMicWanted) runCatching { meshTransport.setMicrophoneEnabled(true) }
        if (priorVoxActive) {
            (meshTransport as? MeshControl)?.micController?.setVoxEnabled(true)
        }
        true
    }

    private suspend fun rollbackToLiveKit() = withContext(NonCancellable) {
        (meshTransport as? MeshControl)?.let { runCatching { it.disableAudio() } }
        audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)
        audioOwnershipArbiter.claimLiveKit()
        runCatching { liveKitTransport.resume() }
    }

    private suspend fun verifyLiveKitQuiet(timeoutMs: Long = 2_000L): Boolean {
        val lk = liveKitTransport as? LiveKitVoiceTransport
        if (lk == null) {
            delay(200L)
            delay(200L)
            return true
        }

        var quietSinceMs: Long? = null
        val startMs = clock()

        while (clock() - startMs < timeoutMs) {
            val quiet = lk.isCaptureQuiet()
            val now = clock()
            if (quiet) {
                if (quietSinceMs == null) {
                    quietSinceMs = now
                } else if (now - quietSinceMs >= 400L) {
                    return true
                }
            } else {
                quietSinceMs = null
                lk.reassertPauseMute()
            }
            delay(50L)
        }
        return false
    }

    companion object {
        const val RECOVERY_CONNECT_TIMEOUT_MS = 15_000L
    }

    suspend fun recoverToLiveKit(): Boolean = switchMutex.withLock {
        val mesh = meshTransport as? MeshControl ?: return@withLock false
        val online = lastOnlineSession



        mesh.pauseCaptureOnly() // (a) stop offline AudioRecord, KEEP playback (no dead air)
        if (liveKitTransport.state.value != VoiceTransportState.CONNECTED) { // (b)
            if (liveKitTransport is LiveKitVoiceTransport) liveKitTransport.triggerBackgroundReconnect()
            else if (online != null) runCatching { liveKitTransport.start(online) }
        }
        val connected = withTimeoutOrNull(RECOVERY_CONNECT_TIMEOUT_MS) { // (c) 15_000
            liveKitTransport.state.first { it == VoiceTransportState.CONNECTED }
        } != null
        if (!connected) {
            withContext(NonCancellable) { mesh.resumeCaptureOnly() } // stay on mesh, playback never stopped
            return@withLock false
        }
        audioOwnershipArbiter.claimLiveKit() // (d)
        liveKitTransport.resume()
        val meshMicCtrl = (meshTransport as? MeshControl)?.micController
        if (meshMicCtrl != null) {
            val meshMicWanted = meshMicCtrl.micToggleOn.value || meshMicCtrl.pttHeld.value
            runCatching { liveKitTransport.setMicrophoneEnabled(meshMicWanted) }
        }
        withContext(NonCancellable) { mesh.disableAudio() } // (e) offline playback stops only now
        _activeKind.value = PeerTransportKind.ONLINE_LIVEKIT // (f)
        _activeTransport.value = liveKitTransport
        true
    }

    suspend fun stopCurrent() = switchMutex.withLock {
        _activeTransport.value.stop()
        audioOwnershipArbiter.releaseAll()
    }
}
