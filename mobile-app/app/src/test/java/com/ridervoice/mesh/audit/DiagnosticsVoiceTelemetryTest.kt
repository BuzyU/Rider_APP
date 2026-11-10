package com.ridervoice.mesh.audit

import com.ridervoice.mesh.audio.JitterBufferStats
import com.ridervoice.mesh.diagnostics.MeshDiagnosticsRepository
import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.graph.MeshGraphSnapshot
import com.ridervoice.mesh.graph.MeshNodeInfo
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.SessionManager
import com.ridervoice.mesh.transport.MeshPeerProjector
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsVoiceTelemetryTest {

    @Test
    fun peerProjector_projectsVoiceTelemetry_intoSnapshot() {
        var now = 5_000L
        val identity = MeshIdentity(deviceId = "self-123", displayName = "Rider-1")
        val diagRepo = MeshDiagnosticsRepository()
        val projector = MeshPeerProjector(
            identity = identity,
            diagnosticsRepo = diagRepo,
            clock = { now }
        )

        val session = OfflineSession.create(now = now)
        val sessionManager = SessionManager(
            session = session,
            selfId = identity.deviceId,
            clock = { now }
        )
        val routingTable = mockk<RoutingTable>()
        every { routingTable.routes } returns MutableStateFlow(emptyMap())

        val forwardingEngine = mockk<ForwardingEngine>()
        every { forwardingEngine.counters() } returns ForwardingEngine.Counters(
            received = 10L,
            forwarded = 5L,
            dup = 1L,
            invalid = 0L,
            ttl = 0L,
            lastVoiceDatagramBytes = 747L,
            avgVoiceDatagramBytes = 747L,
            captureToSendMs = 12.5
        )

        val snapshot = MeshGraphSnapshot(
            selfId = identity.deviceId,
            nodes = mapOf(identity.deviceId to MeshNodeInfo(identity.deviceId, identity.displayName)),
            edges = emptyList(),
            version = 1L
        )

        val jStats = JitterBufferStats(
            lateDropped = 4L,
            overflowDropped = 2L,
            concealedFrames = 7L,
            activeSources = 3,
            maxDepthFrames = 4,
            targetFrames = 3
        )

        projector.project(
            snapshot = snapshot,
            sessionManager = sessionManager,
            routingTable = routingTable,
            forwardingEngine = forwardingEngine,
            speakers = emptySet(),
            linkState = MeshLinkState.RUNNING,
            localIp = "192.168.49.1",
            broadcastAddress = "192.168.49.255",
            authenticatedNeighborsLast10s = 1,
            secondsWithZeroNeighbours = 0L,
            selfIdOverride = identity.deviceId,
            txDatagramsPerSec = 45,
            rxDatagramsPerSec = 52,
            txBytesPerSec = 33615,
            rxBytesPerSec = 38844,
            jitterStats = jStats,
            playoutUnderruns = 9L,
            trackUnderrunCount = 2L,
            concealedMidTalkspurt = 1L,
            rxToPlayoutMs = 65.4
        )

        val diag = diagRepo.snapshot.value
        assertEquals("txBytesPerSec", 33615, diag.txBytesPerSec)
        assertEquals("rxBytesPerSec", 38844, diag.rxBytesPerSec)
        assertEquals("lastVoiceDatagramBytes", 747L, diag.lastVoiceDatagramBytes)
        assertEquals("avgVoiceDatagramBytes", 747L, diag.avgVoiceDatagramBytes)
        assertEquals("jitterDepthFrames", 4, diag.jitterDepthFrames)
        assertEquals("jitterTargetFrames", 3, diag.jitterTargetFrames)
        assertEquals("trackUnderrunCount", 2L, diag.trackUnderrunCount)
        assertEquals("concealedMidTalkspurt", 1L, diag.concealedMidTalkspurt)
        assertEquals(12.5, diag.captureToSendMs, 0.01)
        assertEquals(65.4, diag.rxToPlayoutMs, 0.01)
    }
}

