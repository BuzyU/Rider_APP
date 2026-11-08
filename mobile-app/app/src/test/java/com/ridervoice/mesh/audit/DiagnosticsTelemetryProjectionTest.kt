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

class DiagnosticsTelemetryProjectionTest {

    @Test
    fun peerProjector_projectsTelemetryCountersIntoDiagnosticsSnapshot() {
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

        val forwardingEngine = mockk<ForwardingEngine>(relaxed = true)

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
            activeSources = 3
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
            jitterStats = jStats,
            playoutUnderruns = 9L
        )

        val diag = diagRepo.snapshot.value
        assertEquals("txDatagramsPerSec", 45, diag.txDatagramsPerSec)
        assertEquals("rxDatagramsPerSec", 52, diag.rxDatagramsPerSec)
        assertEquals("jitterLateDropped", 4L, diag.jitterLateDropped)
        assertEquals("jitterOverflowDropped", 2L, diag.jitterOverflowDropped)
        assertEquals("jitterConcealed", 7L, diag.jitterConcealed)
        assertEquals("activeSourceCount", 3, diag.activeSourceCount)
        assertEquals("playoutUnderruns", 9L, diag.playoutUnderruns)
    }
}

