package com.ridervoice.mesh.audio

import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioRoute
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import com.ridervoice.mesh.transport.VoiceTransport
import com.ridervoice.mesh.transport.VoiceTransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicRulesTest {

    private class FakeVoiceTransport : VoiceTransport {
        var isMicEnabled = false
        override val state: StateFlow<VoiceTransportState> = MutableStateFlow(VoiceTransportState.CONNECTED)
        override val participants: StateFlow<List<UnifiedVoicePeer>> = MutableStateFlow(emptyList())
        override val activeSpeakers: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val audioOwner: StateFlow<AudioOwner> = MutableStateFlow(AudioOwner.OFFLINE_MESH)
        override val micState: StateFlow<MicState> = MutableStateFlow(MicState.OFF)
        override suspend fun start(session: VoiceSession) {}
        override suspend fun stop() {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setMicrophoneEnabled(enabled: Boolean) {
            isMicEnabled = enabled
        }
        override suspend fun setDeafened(deafened: Boolean) {}
        override suspend fun setAudioRoute(route: AudioRoute) {}
    }

    @Test
    fun pttAndToggleAndVoxPermutations() = runBlocking {
        val transport = FakeVoiceTransport()
        val bridge = MeshVoxBridge(transport, dispatcher = Dispatchers.Unconfined)

        // Initial state: all closed
        assertFalse("Initially closed", bridge.isGateOpen.value)

        // 1. PTT hold & release
        bridge.setPttPressed(true)
        assertTrue("PTT hold must open mic", bridge.isGateOpen.value)
        assertTrue("Transport mic enabled", transport.isMicEnabled)

        bridge.setPttPressed(false)
        assertFalse("PTT release must close mic", bridge.isGateOpen.value)
        assertFalse("Transport mic disabled", transport.isMicEnabled)

        // 2. Toggle on & off
        bridge.setMicToggled(true)
        assertTrue("Toggle ON opens mic", bridge.isGateOpen.value)
        assertTrue("Transport mic enabled", transport.isMicEnabled)

        bridge.setMicToggled(false)
        assertFalse("Toggle OFF closes mic", bridge.isGateOpen.value)
        assertFalse("Transport mic disabled", transport.isMicEnabled)

        // 3. PTT while Toggle is ON: PTT release must NOT close mic
        bridge.setMicToggled(true)
        assertTrue(bridge.isGateOpen.value)
        bridge.setPttPressed(true)
        assertTrue(bridge.isGateOpen.value)
        bridge.setPttPressed(false)
        assertTrue("PTT release while toggle is ON must keep mic open", bridge.isGateOpen.value)
        assertTrue(transport.isMicEnabled)

        // 4. Toggle turned OFF while PTT is held: must remain open until PTT released
        bridge.setPttPressed(true)
        bridge.setMicToggled(false)
        assertTrue("Toggle OFF while PTT held must remain open", bridge.isGateOpen.value)
        assertTrue(transport.isMicEnabled)

        bridge.setPttPressed(false)
        assertFalse("Releasing PTT now closes mic", bridge.isGateOpen.value)
        assertFalse(transport.isMicEnabled)
    }
}
