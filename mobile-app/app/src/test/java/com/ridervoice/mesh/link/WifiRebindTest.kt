package com.ridervoice.mesh.link

import com.ridervoice.mesh.MeshRuntimeSettings
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class WifiRebindTest {

    private class FakeWifiWatcher : WifiNetworkWatcher {
        private val _events = MutableSharedFlow<WifiChangeEvent>(extraBufferCapacity = 16)
        override val wifiEvents: Flow<WifiChangeEvent> = _events.asSharedFlow()
        var started = false
        var stopped = false

        override fun start() { started = true }
        override fun stop() { stopped = true }

        fun emit(event: WifiChangeEvent) {
            _events.tryEmit(event)
        }
    }

    private class RestartTrackingLink : MeshLink {
        override val linkId: String = "tracking-link"
        private val _state = MutableStateFlow(MeshLinkState.RUNNING)
        override val state: StateFlow<MeshLinkState> = _state.asStateFlow()
        override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
        var startCount = 0
        var stopCount = 0

        override suspend fun start() {
            startCount++
            _state.value = MeshLinkState.RUNNING
        }

        override suspend fun stop() {
            stopCount++
            _state.value = MeshLinkState.STOPPED
        }

        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = true
        override fun endpoints(): Set<MeshEndpoint> = emptySet()
    }

    @Before
    fun setUp() {
        OfflineMeshGate.install(object : MeshRuntimeSettings {
            override val offlineMeshEnabled = true
            override val automaticFallbackEnabled = true
            override val warmStandbyEnabled = true
        })
    }

    @After
    fun tearDown() {
        OfflineMeshGate.install(MeshRuntimeSettings.AllOff)
    }

    @Test
    fun wifiChangeEvents_debouncedToSingleRestart() = runBlocking(Dispatchers.Default) {
        val link = RestartTrackingLink()
        val watcher = FakeWifiWatcher()
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "Rider1")

        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { link },
            wifiWatcher = watcher,
            dispatcher = Dispatchers.Default
        )

        transport.startDiscoveryOnly(OfflineSession.create(1000L))
        assertEquals(1, link.startCount)
        assertEquals(0, link.stopCount)

        // Fire 3 rapid events within 200ms
        watcher.emit(WifiChangeEvent.Lost)
        delay(50L)
        watcher.emit(WifiChangeEvent.Available)
        delay(50L)
        watcher.emit(WifiChangeEvent.Changed)

        // Before debounce window (500ms) expires, restart should not have occurred yet
        delay(200L)
        assertEquals("Should not restart before debounce window expires", 1, link.startCount)

        // After debounce window passes
        delay(600L)
        assertEquals("Must restart exactly once after debounce expires", 2, link.startCount)
        assertEquals("Must stop exactly once before restarting", 1, link.stopCount)

        transport.stop()
        assertTrue("Watcher must be stopped when transport stops", watcher.stopped)
    }
}

