package com.ridervoice.mesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.EnableAudioResult
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
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeshApplicationScope

@HiltViewModel
class OfflineMeshViewModel @Inject constructor(
    val transport: OfflineMeshVoiceTransport,
    val arbiter: AudioOwnershipArbiter,
    val holder: OfflineSessionHolder,
    @MeshApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    constructor(
        transport: OfflineMeshVoiceTransport,
        arbiter: AudioOwnershipArbiter
    ) : this(transport, arbiter, OfflineSessionHolder(), CoroutineScope(SupervisorJob() + Dispatchers.IO))

    constructor(
        transport: OfflineMeshVoiceTransport,
        arbiter: AudioOwnershipArbiter,
        appScope: CoroutineScope
    ) : this(transport, arbiter, OfflineSessionHolder(), appScope)

    constructor(
        transport: OfflineMeshVoiceTransport,
        arbiter: AudioOwnershipArbiter,
        holder: OfflineSessionHolder
    ) : this(transport, arbiter, holder, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val _liveKitConflictError = MutableStateFlow(false)
    val liveKitConflictError: StateFlow<Boolean> = _liveKitConflictError.asStateFlow()

    private val _isDerivingKey = MutableStateFlow(false)
    val isDerivingKey: StateFlow<Boolean> = _isDerivingKey.asStateFlow()

    private val _sessionError = MutableStateFlow(false)
    val sessionError: StateFlow<Boolean> = _sessionError.asStateFlow()

    val activeSession: StateFlow<OfflineSession?> = holder.current
    val backupSession: StateFlow<OfflineSession?> = holder.backup
    val audioFailure: StateFlow<EnableAudioResult.Failure?> = transport.audioFailure

    @Volatile private var startedStandaloneSession: Boolean = false

    fun isMicOwnedByLiveKit(): Boolean = arbiter.owner.value == AudioOwner.LIVEKIT

    fun createNewSession(onReady: (String) -> Unit) =
        createNewSession(saveAsBackup = false, onReady = onReady)

    fun createNewSession(saveAsBackup: Boolean, onReady: (String) -> Unit = {}) {
        if (_isDerivingKey.value) return
        viewModelScope.launch {
            _isDerivingKey.value = true
            _sessionError.value = false
            val now = System.currentTimeMillis()
            val (primary, backupCopy) = withContext(Dispatchers.Default) {
                val created = OfflineSession.create(now)
                val backup = if (saveAsBackup) {
                    OfflineSession.fromCode(created.pairingCode, now)
                } else {
                    null
                }
                created to backup
            }
            holder.setCurrent(primary)
            if (backupCopy != null) {
                holder.setBackup(backupCopy)
            }
            _isDerivingKey.value = false
            onReady(primary.pairingCode)
        }
    }

    fun saveCurrentGeneratedAsBackupAndFinish(onSaved: () -> Unit) {
        if (_isDerivingKey.value) return
        val current = holder.current.value ?: return
        val code = current.pairingCode
        if (code.isBlank()) return
        viewModelScope.launch {
            _isDerivingKey.value = true
            val backupCopy = withContext(Dispatchers.Default) {
                OfflineSession.fromCode(code, System.currentTimeMillis())
            }
            if (backupCopy != null) {
                holder.setBackup(backupCopy)
            }
            if (!startedStandaloneSession) {
                holder.clearCurrent()
            }
            _isDerivingKey.value = false
            onSaved()
        }
    }

    fun joinWithCode(rawCode: String, onReady: (String) -> Unit, onInvalid: () -> Unit) =
        joinWithCode(rawCode = rawCode, saveAsBackupOnly = false, onReady = onReady, onInvalid = onInvalid)

    fun joinWithCode(
        rawCode: String,
        saveAsBackupOnly: Boolean,
        onReady: (String) -> Unit,
        onInvalid: () -> Unit
    ) {
        if (_isDerivingKey.value) return
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
            if (saveAsBackupOnly) {
                holder.setBackup(session)
            } else {
                holder.setCurrent(session)
            }
            onReady(session.pairingCode)
        }
    }

    fun clearSavedBackup() {
        holder.clearBackup()
    }

    fun clearSessionError() {
        _sessionError.value = false
    }


    suspend fun startSession(session: OfflineSession) {
        if (isMicOwnedByLiveKit()) {
            _liveKitConflictError.value = true
            return
        }
        _liveKitConflictError.value = false
        startedStandaloneSession = true
        holder.setCurrent(session)
        transport.start(VoiceSession.Offline(session))
    }

    fun stopSession() {
        startedStandaloneSession = false
        holder.clearCurrent()
        appScope.launch {
            transport.stop()
            arbiter.release(AudioOwner.OFFLINE_MESH)
        }
    }

    fun leave() {
        startedStandaloneSession = false
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
        if (startedStandaloneSession || (holder.current.value != null && !isMicOwnedByLiveKit())) {
            stopSession()
        }
    }
}
