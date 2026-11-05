package com.ridervoice.mesh.link

import android.os.Looper
import com.ridervoice.mesh.MeshConfig
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MulticastLockLifecycleTest {

    private class FullTrackingLocks : NetworkLocks {
        var multicastAcquireCount = 0
        var multicastReleaseCount = 0
        var wifiAcquireCount = 0
        var wifiReleaseCount = 0

        override fun acquireMulticast() {
            multicastAcquireCount++
        }

        override fun releaseMulticast() {
            multicastReleaseCount++
        }

        override fun acquire() {
            wifiAcquireCount++
        }

        override fun release() {
            wifiReleaseCount++
        }
    }

    private class ThreadGuardedFakeLink(
        private val onMainThreadDetected: () -> Unit
    ) : MeshLink {
        override val linkId: String = "guarded-link"
        private val _state = MutableStateFlow(MeshLinkState.RUNNING)
        override val state: StateFlow<MeshLinkState> = _state.asStateFlow()
        override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()

        override suspend fun start() {
            _state.value = MeshLinkState.RUNNING
        }

        override suspend fun stop() {
            _state.value = MeshLinkState.STOPPED
        }

        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
            val currentThread = Thread.currentThread()
            if (currentThread.name.equals("main", ignoreCase = true) ||
                (runCatching { Looper.getMainLooper()?.thread == currentThread }.getOrDefault(false))) {
                onMainThreadDetected()
            }
            return true
        }

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
    fun multicastLockAcquiredOnDiscoveryStart_releasedOnStop_wifiLockOnlyOnAudio() = runBlocking(Dispatchers.Default) {
        val locks = FullTrackingLocks()
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "Rider1")

        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            locks = locks,
            dispatcher = Dispatchers.Default
        )

        val session = OfflineSession.create(1000L)

        // 1. Discovery only: MulticastLock acquired, WifiLock NOT acquired
        transport.startDiscoveryOnly(session)
        assertEquals("Multicast lock must be acquired on discovery start", 1, locks.multicastAcquireCount)
        assertEquals("Multicast lock must not be released while running", 0, locks.multicastReleaseCount)
        assertEquals("WifiLock must NOT be acquired in discovery-only mode", 0, locks.wifiAcquireCount)

        // 2. Enable audio: WifiLock acquired, MulticastLock remains held
        transport.enableAudio()
        assertEquals("WifiLock must be acquired when audio is enabled", 1, locks.wifiAcquireCount)
        assertEquals("Multicast lock remains held", 1, locks.multicastAcquireCount)
        assertEquals("Multicast lock not released", 0, locks.multicastReleaseCount)

        // 3. Disable audio: WifiLock released, MulticastLock remains held
        transport.disableAudio()
        assertEquals("WifiLock released when audio disabled", 1, locks.wifiReleaseCount)
        assertEquals("Multicast lock still held during discovery after audio disabled", 0, locks.multicastReleaseCount)

        // 4. Stop: MulticastLock released
        transport.stop()
        assertEquals("Multicast lock released on transport stop", 1, locks.multicastReleaseCount)
    }

    @Test
    fun sendIsNeverInvokedOnMainThread() = runBlocking(Dispatchers.Default) {
        val mainThreadCalled = AtomicBoolean(false)
        val guardedLink = ThreadGuardedFakeLink {
            mainThreadCalled.set(true)
        }
        val arbiter = AudioOwnershipArbiter()
        val identity = MeshIdentity(UUID.randomUUID().toString(), "Rider1")

        val transport = OfflineMeshVoiceTransport(
            identity = identity,
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            linkFactory = { guardedLink },
            dispatcher = Dispatchers.IO
        )

        val session = OfflineSession.create(1000L)
        transport.startDiscoveryOnly(session)

        // Allow heartbeat job to originate HELLOs and invoke link.send
        delay(500L)

        assertFalse("link.send() must NEVER be called on the main/UI thread", mainThreadCalled.get())

        transport.stop()
    }
}

