package com.ridervoice.mesh.transport

import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.MeshSettingsSnapshot
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.OfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.switching.OfflineAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSessionCoordinatorAvailabilityTest {

    private class TestSecretProvider(initialSession: OfflineSession? = null) : OfflineSecretProvider {
        val _backupSession = MutableStateFlow(initialSession)
        override val backupSession: StateFlow<OfflineSession?> = _backupSession.asStateFlow()
        override fun getSessionForOnlineRide(roomId: String): OfflineSession? = _backupSession.value
    }

    private class FakeVoiceTransport : VoiceTransport {
        override val state: StateFlow<VoiceTransportState> = MutableStateFlow(VoiceTransportState.STOPPED)
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.NONE)
        override val micState: StateFlow<MicState> = MutableStateFlow(MicState.OFF)
        override suspend fun start(session: VoiceSession) {}
        override suspend fun stop() {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {}
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }

    @Test
    fun availabilityTest_availableWhenBackupPresentAndRideActive() {
        val mockSettings = object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = true
            override val warmStandbyEnabled = true
            override val settingsFlow = MutableStateFlow(MeshSettingsSnapshot(true, true, true)).asStateFlow()
            override fun setOfflineMeshEnabled(enabled: Boolean) {}
            override fun setAutomaticFallbackEnabled(enabled: Boolean) {}
            override fun setWarmStandbyEnabled(enabled: Boolean) {}
        }
        OfflineMeshGate.install(mockSettings)

        try {
            val nowMs = 1_000_000L
            val session = OfflineSession.create(now = nowMs, iterations = 1)
            val secretProvider = TestSecretProvider(initialSession = session)

            val coordinator = VoiceSessionCoordinator(
                liveKitTransport = FakeVoiceTransport(),
                meshTransport = FakeVoiceTransport(),
                audioOwnershipArbiter = AudioOwnershipArbiter(),
                secretProvider = secretProvider,
                clock = { nowMs },
                dispatcher = Dispatchers.Unconfined
            )

            // Without online ride, availability is NO_SHARED_SECRET
            assertEquals(OfflineAvailability.NO_SHARED_SECRET, coordinator.offlineAvailability.value)

            // When online ride starts, availability becomes AVAILABLE
            val onlineRide = VoiceSession.Online(
                url = "wss://test.ridervoice.app",
                roomId = "room-123",
                tokenProvider = { "fake-token" }
            )
            coordinator.setOnlineSession(onlineRide)
            assertEquals(OfflineAvailability.AVAILABLE, coordinator.offlineAvailability.value)

            // When ride ends, availability reverts to NO_SHARED_SECRET
            coordinator.setOnlineSession(null)
            assertEquals(OfflineAvailability.NO_SHARED_SECRET, coordinator.offlineAvailability.value)
        } finally {
            OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
        }
    }
}
