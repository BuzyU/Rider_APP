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
    val arbiter: AudioOwnershipArbiter
) : ViewModel() {

    private var appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    constructor(
        transport: OfflineMeshVoiceTransport,
        arbiter: AudioOwnershipArbiter,
        appScope: CoroutineScope
    ) : this(transport, arbiter) {
        this.appScope = appScope
    }

    private val _liveKitConflictError = MutableStateFlow(false)
    val liveKitConflictError: StateFlow<Boolean> = _liveKitConflictError.asStateFlow()

    private val _isDerivingKey = MutableStateFlow(false)
    val isDerivingKey: StateFlow<Boolean> = _isDerivingKey.asStateFlow()

    private val _sessionError = MutableStateFlow(false)
    val sessionError: StateFlow<Boolean> = _sessionError.asStateFlow()

    private val _activeSession = MutableStateFlow<OfflineSession?>(null)
    val activeSession: StateFlow<OfflineSession?> = _activeSession.asStateFlow()

    fun isMicOwnedByLiveKit(): Boolean = arbiter.owner.value == AudioOwner.LIVEKIT

    fun createNewSession(onReady: (String) -> Unit) {
        viewModelScope.launch {
            _isDerivingKey.value = true
            _sessionError.value = false
            val session = withContext(Dispatchers.Default) {
                OfflineSession.create(System.currentTimeMillis())
            }
            _isDerivingKey.value = false
            _activeSession.value = session
            OfflineSessionHolder.set(session)
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
            _activeSession.value = session
            OfflineSessionHolder.set(session)
            onReady(session.pairingCode)
        }
    }

    fun resolveAndStartSession(sessionCode: String, onInvalid: () -> Unit = {}) {
        viewModelScope.launch {
            val current = _activeSession.value
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

            _activeSession.value = session
            OfflineSessionHolder.set(session)
            startSession(session)
        }
    }

    suspend fun startSession(session: OfflineSession) {
        if (isMicOwnedByLiveKit()) {
            _liveKitConflictError.value = true
            return
        }
        _liveKitConflictError.value = false
        _activeSession.value = session
        OfflineSessionHolder.set(session)
        transport.start(VoiceSession.Offline(session))
    }

    fun stopSession() {
        appScope.launch {
            transport.stop()
            arbiter.release(AudioOwner.OFFLINE_MESH)
            OfflineSessionHolder.clear()
            _activeSession.value = null
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
