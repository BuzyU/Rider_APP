package com.ridervoice.mesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class OfflineMeshViewModel @Inject constructor(
    val transport: OfflineMeshVoiceTransport,
    val arbiter: AudioOwnershipArbiter,
    val holder: OfflineSessionHolder
) : ViewModel() {

    private var appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    constructor(
        transport: OfflineMeshVoiceTransport,
        arbiter: AudioOwnershipArbiter
    ) : this(transport, arbiter, OfflineSessionHolder())

    constructor(
        transport: OfflineMeshVoiceTransport,
        arbiter: AudioOwnershipArbiter,
        appScope: CoroutineScope
    ) : this(transport, arbiter, OfflineSessionHolder()) {
        this.appScope = appScope
    }

    constructor(
        transport: OfflineMeshVoiceTransport,
        arbiter: AudioOwnershipArbiter,
        holder: OfflineSessionHolder,
        appScope: CoroutineScope
    ) : this(transport, arbiter, holder) {
        this.appScope = appScope
    }

    private val _liveKitConflictError = MutableStateFlow(false)
    val liveKitConflictError: StateFlow<Boolean> = _liveKitConflictError.asStateFlow()

    private val _isDerivingKey = MutableStateFlow(false)
    val isDerivingKey: StateFlow<Boolean> = _isDerivingKey.asStateFlow()

    private val _sessionError = MutableStateFlow(false)
    val sessionError: StateFlow<Boolean> = _sessionError.asStateFlow()

    val activeSession: StateFlow<OfflineSession?> = holder.current

    fun isMicOwnedByLiveKit(): Boolean = arbiter.owner.value == AudioOwner.LIVEKIT

    fun createNewSession(onReady: (String) -> Unit) {
        viewModelScope.launch {
            _isDerivingKey.value = true
            _sessionError.value = false
            val session = withContext(Dispatchers.Default) {
                OfflineSession.create(System.currentTimeMillis())
            }
            _isDerivingKey.value = false
            holder.setCurrent(session)
            onReady(session.pairingCode)
        }
    }

    fun joinWithCode(rawCode: String, onReady: (String) -> Unit, onInvalid: () -> Unit) {
        viewModelScope.launch {
            _isDerivingKey.value = true
            _sessionError.value = false
            val session = withContext(Dispatchers.Default) {
                OfflineSession.fromCode(rawCode, System.currentTimeMillis())
            }
            _isDerivingKey.value = false
            if (session == null) {
                _sessionError.value = true
                onInvalid()
                return@launch
            }
            holder.setCurrent(session)
            onReady(session.pairingCode)
        }
    }

    fun resolveAndStartSession(sessionCode: String, onInvalid: () -> Unit = {}) {
        viewModelScope.launch {
            val current = holder.current.value
            if (current != null && current.pairingCode == sessionCode) {
                startSession(current)
                return@launch
            }

            _isDerivingKey.value = true
            _sessionError.value = false
            val session = withContext(Dispatchers.Default) {
                OfflineSession.fromCode(sessionCode, System.currentTimeMillis())
            }
            _isDerivingKey.value = false

            if (session == null) {
                _sessionError.value = true
                onInvalid()
                return@launch
            }

            holder.setCurrent(session)
            startSession(session)
        }
    }

    suspend fun startSession(session: OfflineSession) {
        if (isMicOwnedByLiveKit()) {
            _liveKitConflictError.value = true
            return
        }
        _liveKitConflictError.value = false
        holder.setCurrent(session)
        transport.start(VoiceSession.Offline(session))
    }

    fun stopSession() {
        holder.clearCurrent()
        appScope.launch {
            transport.stop()
            arbiter.release(AudioOwner.OFFLINE_MESH)
        }
    }

    fun leave() {
        holder.clearCurrent()
        appScope.launch {
            transport.stop()
            arbiter.release(AudioOwner.OFFLINE_MESH)
        }
    }

    fun clearConflictError() {
        _liveKitConflictError.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
