package com.ridervoice.mesh.transport

import androidx.annotation.VisibleForTesting
import com.ridervoice.mesh.MeshLog
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.NullOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.OfflineAvailability
import com.ridervoice.mesh.util.runCatchingCancellable
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
 * - Safe fallback to mesh with automatic rollback on failure.
 * - Safe recovery to LiveKit with automatic rollback to mesh on failure.
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
        else -> OfflineAvailability.NO_SHARED_SECRET
    }

    private val _offlineAvailability = MutableStateFlow(availability(null, secretProvider.backupSession.value, clock()))
    val offlineAvailability: StateFlow<OfflineAvailability> = _offlineAvailability.asStateFlow()

    private val _activeKind = MutableStateFlow(PeerTransportKind.ONLINE_LIVEKIT)
    val activeKind: StateFlow<PeerTransportKind> = _activeKind.asStateFlow()

    private val _activeTransport = MutableStateFlow(liveKitTransport)

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
                        delay((s.expiresAt - clock()).coerceAtLeast(0L))
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
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
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
     * Fallback to offline mesh when online degradation occurs.
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
        val priorDeafened = lk?.deafened?.value == true
        var committed = false
        try {
            liveKitTransport.pause()
            if (!verifyLiveKitQuiet(2_000L)) return@withLock false
            if (!audioOwnershipArbiter.handoffLiveKitToOfflineMesh { }) return@withLock false
            val ok = runCatchingCancellable { mesh.enableAudio() }.getOrElse { EnableAudioResult.Failure(EnableAudioResult.Failure.Code.UNEXPECTED, it.javaClass.simpleName) } is EnableAudioResult.Success &&
                meshTransport.micState.value != MicState.HARDWARE_BUSY
            if (!ok) return@withLock false
            _activeKind.value = PeerTransportKind.OFFLINE_MESH
            _activeTransport.value = meshTransport
            mesh.setHelloIntervalMs(750L)
            if (priorDeafened) {
                runCatchingCancellable { meshTransport.setDeafened(true) }
            }
            if (priorMicWanted) runCatchingCancellable { meshTransport.setMicrophoneEnabled(true) }
            if (priorVoxActive) {
                mesh.micController?.setVoxEnabled(true)
            }
            committed = true
            true
        } finally {
            if (!committed) withContext(NonCancellable) { rollbackToLiveKit() }
        }
    }

    private suspend fun rollbackToLiveKit() = withContext(NonCancellable) {
        (meshTransport as? MeshControl)?.setHelloIntervalMs(1500L)
        (meshTransport as? MeshControl)?.let { runCatchingCancellable { it.disableAudio() } }
        audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)
        audioOwnershipArbiter.claimLiveKit()
        runCatchingCancellable { liveKitTransport.resume() }
        _activeKind.value = PeerTransportKind.ONLINE_LIVEKIT
        _activeTransport.value = liveKitTransport
    }

    private suspend fun rollbackToMesh() = withContext(NonCancellable) {
        runCatchingCancellable { liveKitTransport.pause() }
        audioOwnershipArbiter.release(AudioOwner.LIVEKIT)
        audioOwnershipArbiter.tryClaimOfflineMesh()
        (meshTransport as? MeshControl)?.let { mesh ->
            runCatchingCancellable { mesh.enableAudio() }
            mesh.resumeCaptureOnly()
            mesh.setHelloIntervalMs(750L)
        }
        _activeKind.value = PeerTransportKind.OFFLINE_MESH
        _activeTransport.value = meshTransport
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
        const val RECOVERY_CONFIRM_TIMEOUT_MS = 3_000L
    }

    suspend fun recoverToLiveKit(): Boolean = switchMutex.withLock {
        if (liveKitTransport.state.value != VoiceTransportState.CONNECTED) return@withLock false
        val mesh = meshTransport as? MeshControl ?: return@withLock false
        val meshMicCtrl = mesh.micController
        val meshMicWanted = meshMicCtrl != null && (meshMicCtrl.micToggleOn.value || meshMicCtrl.pttHeld.value)
        var committed = false
        try {
            mesh.pauseCaptureOnly()
            if (!audioOwnershipArbiter.claimLiveKit(onStopOfflineFirst = { mesh.pauseCaptureOnly() })) return@withLock false
            liveKitTransport.resume()
            val confirmed = withTimeoutOrNull(RECOVERY_CONFIRM_TIMEOUT_MS) {
                liveKitTransport.state.first { it == VoiceTransportState.CONNECTED }
            } != null
            if (!confirmed) return@withLock false
            withContext(NonCancellable) { mesh.disableAudio() }
            if (meshMicCtrl != null) {
                runCatchingCancellable { liveKitTransport.setMicrophoneEnabled(meshMicWanted) }
            }
            mesh.setHelloIntervalMs(1500L)
            _activeKind.value = PeerTransportKind.ONLINE_LIVEKIT
            _activeTransport.value = liveKitTransport
            committed = true
            true
        } finally {
            if (!committed) withContext(NonCancellable) { rollbackToMesh() }
        }
    }

    suspend fun onOnlineRideEnded(keepStandaloneMesh: Boolean) = switchMutex.withLock {
        (liveKitTransport as? LiveKitVoiceTransport)?.resetCoordinatorPauseOnRideEnd()
        if (!keepStandaloneMesh) {
            _activeKind.value = PeerTransportKind.ONLINE_LIVEKIT
            _activeTransport.value = liveKitTransport
            if (meshTransport.state.value != VoiceTransportState.STOPPED) {
                runCatchingCancellable { meshTransport.stop() }
            }
            audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    suspend fun stopCurrent() = switchMutex.withLock {
        _activeTransport.value.stop()
        audioOwnershipArbiter.releaseAll()
    }
}
