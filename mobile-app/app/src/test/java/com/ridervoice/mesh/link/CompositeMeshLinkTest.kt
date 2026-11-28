package com.ridervoice.mesh.link

import com.ridervoice.mesh.link.aware.AwareRuntimeContext
import com.ridervoice.mesh.link.aware.WifiAwareLinkController
import com.ridervoice.mesh.link.aware.WifiAwareStatus
import com.ridervoice.mesh.session.OfflineSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeMeshLinkTest {

    private class FakePrimaryLink : MeshLink {
        override val linkId = "fake-udp"
        val _state = MutableStateFlow(MeshLinkState.STOPPED)
        override val state: StateFlow<MeshLinkState> = _state.asStateFlow()
        val _in = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 16)
        override val incomingFrames: Flow<MeshFrame> = _in.asSharedFlow()
        val sent = ArrayList<Pair<MeshEndpoint, ByteArray>>()
        val noted = ArrayList<MeshEndpoint>()
        val eps = mutableSetOf(MeshEndpoint("192.168.49.1", 45454))

        override suspend fun start() { _state.value = MeshLinkState.RUNNING }
        override suspend fun stop() { _state.value = MeshLinkState.STOPPED }
        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
            sent.add(endpoint to payload); return true
        }
        override fun endpoints(): Set<MeshEndpoint> = eps
        override fun noteAuthenticatedEndpoint(endpoint: MeshEndpoint) { noted.add(endpoint) }
    }

    private class FakeAwareController : WifiAwareLinkController {
        override val status = MutableStateFlow(WifiAwareStatus.ACTIVE_CHAIN)
        override val isAwareAvailable = MutableStateFlow(true)
        override val activePeerCount = MutableStateFlow(1)
        override val discoveredPeerCount = MutableStateFlow(1)
        override val maxNdpSessions = MutableStateFlow(3)
        val awareEp = MeshEndpoint("fe80::1234", 49152)
        override val activePeerEndpoints = MutableStateFlow(listOf(awareEp))
        val _in = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 16)
        override val incomingFrames: Flow<MeshFrame> = _in.asSharedFlow()
        val sent = ArrayList<Pair<MeshEndpoint, ByteArray>>()
        val noted = ArrayList<Pair<MeshEndpoint, Long>>()

        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
            sent.add(endpoint to payload); return true
        }
        override fun handlesEndpoint(endpoint: MeshEndpoint): Boolean = endpoint == awareEp
        override fun noteAuthenticatedUdpFrom(endpoint: MeshEndpoint, nowMs: Long) { noted.add(endpoint to nowMs) }
        override fun ensureActive(session: OfflineSession, runtimeContext: AwareRuntimeContext) {}
        override fun stop() {}
    }

    @Test
    fun multiplexesIpv4AndIpv6TrafficAndEndpointsCleanly() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakePrimaryLink()
        val aware = FakeAwareController()
        val composite = CompositeMeshLink(primary, aware, monotonicClock = { 777L }, dispatcher = UnconfinedTestDispatcher(testScheduler))

        composite.start()
        assertEquals(MeshLinkState.RUNNING, composite.state.value)
        assertEquals(
            setOf(MeshEndpoint("192.168.49.1", 45454), aware.awareEp),
            composite.endpoints()
        )

        val p4 = byteArrayOf(1, 2)
        val p6 = byteArrayOf(3, 4)
        assertTrue(composite.send(MeshEndpoint("192.168.49.2", 45454), p4))
        assertTrue(composite.send(aware.awareEp, p6))
        assertEquals(1, primary.sent.size)
        assertEquals(1, aware.sent.size)

        composite.noteAuthenticatedEndpoint(aware.awareEp)
        assertEquals(aware.awareEp to 777L, aware.noted.single())

        var received: MeshFrame? = null
        val job = launch { received = composite.incomingFrames.first() }
        aware._in.tryEmit(MeshFrame(aware.awareEp, p6))
        job.join()
        assertEquals(aware.awareEp, received?.from)

        composite.stop()
        assertEquals(MeshLinkState.STOPPED, composite.state.value)
    }

    @Test
    fun nonAwareIpv6Endpoint_routesToPrimaryLinkInsteadOfBeingDroppedByAware() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakePrimaryLink()
        val aware = FakeAwareController()
        val composite = CompositeMeshLink(primary, aware, monotonicClock = { 888L }, dispatcher = UnconfinedTestDispatcher(testScheduler))
        composite.start()

        val otherIpv6 = MeshEndpoint("fe80::9999", 45454)
        assertTrue(composite.send(otherIpv6, byteArrayOf(9, 8)))
        assertEquals(1, primary.sent.size)
        assertEquals(otherIpv6, primary.sent.single().first)
        assertTrue(aware.sent.isEmpty())

        composite.noteAuthenticatedEndpoint(otherIpv6)
        assertEquals(listOf(otherIpv6), primary.noted)
        assertTrue(aware.noted.isEmpty())

        composite.stop()
    }

    @Test
    fun aggregateStateBecomesRunningWhenPrimaryStoppedOrFailedIfAwareHasActivePeers() = runTest(UnconfinedTestDispatcher()) {
        val primary = FakePrimaryLink()
        val aware = FakeAwareController()
        aware.activePeerCount.value = 0
        val composite = CompositeMeshLink(primary, aware, monotonicClock = { 999L }, dispatcher = UnconfinedTestDispatcher(testScheduler))
        composite.start()
        assertEquals(MeshLinkState.RUNNING, composite.state.value)

        primary._state.value = MeshLinkState.FAILED
        assertEquals(MeshLinkState.FAILED, composite.state.value)

        aware.activePeerCount.value = 2
        assertEquals(MeshLinkState.RUNNING, composite.state.value)

        aware.activePeerCount.value = 0
        assertEquals(MeshLinkState.FAILED, composite.state.value)

        composite.stop()
    }
}
