package com.ridervoice.mesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OfflineMeshViewModel @Inject constructor(
    val transport: OfflineMeshVoiceTransport,
    val arbiter: AudioOwnershipArbiter
) : ViewModel() {

    private val _liveKitConflictError = MutableStateFlow(false)
    val liveKitConflictError: StateFlow<Boolean> = _liveKitConflictError.asStateFlow()

    fun isMicOwnedByLiveKit(): Boolean = arbiter.owner.value == AudioOwner.LIVEKIT

    suspend fun startSession(session: OfflineSession) {
        if (isMicOwnedByLiveKit()) {
            _liveKitConflictError.value = true
            return
        }
        _liveKitConflictError.value = false
        transport.start(VoiceSession.Offline(session))
    }

    fun stopSession() {
        viewModelScope.launch {
            transport.stop()
            arbiter.release(AudioOwner.OFFLINE_MESH)
        }
    }

    fun clearConflictError() {
        _liveKitConflictError.value = false
    }
}

