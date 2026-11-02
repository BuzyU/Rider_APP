package com.ridervoice.mesh.transport

import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface LiveKitPort {
    val connectionState: StateFlow<ConnectionState>
    val activeSpeaker: StateFlow<String?>
    val participants: StateFlow<List<Participant>>
    val isUserMuted: StateFlow<Boolean> get() = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> get() = MutableStateFlow(false)
    suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?)
    fun disconnect()
    fun setUserMuted(muted: Boolean)
    fun setDeafened(deafened: Boolean)
    fun onVoiceGate(open: Boolean) {}
    fun onNetworkRestored() {}
    fun stopVox() {}
}
