package com.ridervoice.mesh.transport

import com.ridervoice.models.Participant
import com.ridervoice.network.ConnectionState
import kotlinx.coroutines.flow.StateFlow

interface LiveKitPort {
    val connectionState: StateFlow<ConnectionState>
    val activeSpeaker: StateFlow<String?>
    val participants: StateFlow<List<Participant>>
    suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?)
    fun disconnect()
    fun setUserMuted(muted: Boolean)
    fun setDeafened(deafened: Boolean)
}

