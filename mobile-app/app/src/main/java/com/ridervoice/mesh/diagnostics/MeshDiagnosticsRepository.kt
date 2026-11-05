package com.ridervoice.mesh.diagnostics

import com.ridervoice.mesh.graph.MeshGraphSnapshot
import com.ridervoice.mesh.routing.RouteEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import java.util.concurrent.atomic.AtomicLong

data class MeshDiagnosticsSnapshot(
    val selfDeviceId: String = "",
    val sessionId: String = "",
    val memberCount: Int = 0,
    val directNeighborCount: Int = 0,
    val routeCount: Int = 0,
    val maxHopCount: Int = 0,
    val receivedVoiceFrames: Long = 0L,
    val forwardedVoiceFrames: Long = 0L,
    val droppedDuplicates: Long = 0L,
    val droppedInvalid: Long = 0L,
    val droppedTtl: Long = 0L,
    val droppedAtLinkBuffer: Long = 0L,
    val graphVersion: Long = 0L
)

class MeshDiagnosticsRepository {
    private val _snapshot = MutableStateFlow(MeshDiagnosticsSnapshot())
    val snapshot: StateFlow<MeshDiagnosticsSnapshot> = _snapshot.asStateFlow()
    private val _droppedAtLinkBuffer = AtomicLong(0L)

    fun recordBufferDrop() {
        _droppedAtLinkBuffer.incrementAndGet()
    }

    fun droppedAtLinkBuffer(): Long = _droppedAtLinkBuffer.get()

    fun update(
        selfDeviceId: String,
        sessionId: String,
        memberCount: Int,
        graph: MeshGraphSnapshot,
        routes: Map<String, RouteEntry>,
        receivedVoiceFrames: Long,
        forwardedVoiceFrames: Long,
        droppedDuplicates: Long,
        droppedInvalid: Long,
        droppedTtl: Long
    ) {
        _snapshot.value = MeshDiagnosticsSnapshot(
            selfDeviceId = selfDeviceId,
            sessionId = sessionId,
            memberCount = memberCount,
            directNeighborCount = graph.edges.count { (it.from == selfDeviceId || it.to == selfDeviceId) && it.endpoint != null },
            routeCount = routes.size,
            maxHopCount = routes.values.maxOfOrNull { it.hopCount } ?: 0,
            receivedVoiceFrames = receivedVoiceFrames,
            forwardedVoiceFrames = forwardedVoiceFrames,
            droppedDuplicates = droppedDuplicates,
            droppedInvalid = droppedInvalid,
            droppedTtl = droppedTtl,
            droppedAtLinkBuffer = _droppedAtLinkBuffer.get(),
            graphVersion = graph.version
        )
    }

    fun reset() {
        _droppedAtLinkBuffer.set(0L)
        _snapshot.value = MeshDiagnosticsSnapshot()
    }
}

