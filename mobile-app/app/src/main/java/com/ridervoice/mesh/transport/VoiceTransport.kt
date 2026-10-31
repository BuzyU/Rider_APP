package com.ridervoice.mesh.transport

import com.ridervoice.mesh.session.OfflineSession
import kotlinx.coroutines.flow.StateFlow

enum class VoiceTransportState {
    STOPPED,
    STARTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

enum class AudioRoute {
    SPEAKER,
    BLUETOOTH,
    WIRED,
    EARPIECE
}

sealed class VoiceSession {
    data class Online(
        val url: String,
        val roomId: String,
        val tokenProvider: suspend () -> String,
        val expectedLkRoomName: String? = null
    ) : VoiceSession()

    data class Offline(
        val session: OfflineSession
    ) : VoiceSession()
}

interface VoiceTransport {
    val state: StateFlow<VoiceTransportState>
    val participants: StateFlow<List<UnifiedVoicePeer>>
    val activeSpeakers: StateFlow<Set<String>>
    val audioOwner: StateFlow<AudioOwner>

    suspend fun start(session: VoiceSession)
    suspend fun stop()

    suspend fun setMicrophoneEnabled(enabled: Boolean)
    suspend fun setDeafened(deafened: Boolean)
    suspend fun setAudioRoute(route: AudioRoute)
}

