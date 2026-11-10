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
    val droppedStaleTimestamp: Long = 0L,
    val droppedBadVersion: Long = 0L,
    val droppedUnknownSource: Long = 0L,
    val droppedOversize: Long = 0L,
    val droppedStaleEpoch: Long = 0L,
    val idCollision: Long = 0L,
    val droppedAtLinkBuffer: Long = 0L,
    val graphVersion: Long = 0L,
    val linkState: String = "STOPPED",
    val localIp: String = "",
    val broadcastAddress: String = "",
    val authenticatedNeighborsLast10s: Int = 0,
    val isolationWarningHint: String? = null,
    val txDatagramsPerSec: Int = 0,
    val rxDatagramsPerSec: Int = 0,
    val jitterLateDropped: Long = 0L,
    val jitterOverflowDropped: Long = 0L,
    val jitterConcealed: Long = 0L,
    val playoutUnderruns: Long = 0L,
    val activeSourceCount: Int = 0,
    val txBytesPerSec: Int = 0,
    val rxBytesPerSec: Int = 0,
    val lastVoiceDatagramBytes: Long = 0L,
    val avgVoiceDatagramBytes: Long = 0L,
    val jitterDepthFrames: Int = 0,
    val jitterTargetFrames: Int = 0,
    val trackUnderrunCount: Long = 0L,
    val concealedMidTalkspurt: Long = 0L,
    val captureToSendMs: Double = 0.0,
    val rxToPlayoutMs: Double = 0.0
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
        droppedTtl: Long,
        droppedStaleTimestamp: Long = 0L,
        droppedBadVersion: Long = 0L,
        droppedUnknownSource: Long = 0L,
        droppedOversize: Long = 0L,
        droppedStaleEpoch: Long = 0L,
        idCollision: Long = 0L,
        linkState: String = "RUNNING",
        localIp: String = "",
        broadcastAddress: String = "",
        authenticatedNeighborsLast10s: Int = 0,
        isolationWarningHint: String? = null,
        txDatagramsPerSec: Int = 0,
        rxDatagramsPerSec: Int = 0,
        jitterLateDropped: Long = 0L,
        jitterOverflowDropped: Long = 0L,
        jitterConcealed: Long = 0L,
        playoutUnderruns: Long = 0L,
        activeSourceCount: Int = 0,
        txBytesPerSec: Int = 0,
        rxBytesPerSec: Int = 0,
        lastVoiceDatagramBytes: Long = 0L,
        avgVoiceDatagramBytes: Long = 0L,
        jitterDepthFrames: Int = 0,
        jitterTargetFrames: Int = 0,
        trackUnderrunCount: Long = 0L,
        concealedMidTalkspurt: Long = 0L,
        captureToSendMs: Double = 0.0,
        rxToPlayoutMs: Double = 0.0
    ) {
        val neighborsCount = if (authenticatedNeighborsLast10s > 0) authenticatedNeighborsLast10s
        else graph.edges.count { (it.from == selfDeviceId || it.to == selfDeviceId) && it.endpoint != null }

        _snapshot.value = MeshDiagnosticsSnapshot(
            selfDeviceId = selfDeviceId,
            sessionId = sessionId,
            memberCount = memberCount,
            directNeighborCount = neighborsCount,
            routeCount = routes.size,
            maxHopCount = routes.values.maxOfOrNull { it.hopCount } ?: 0,
            receivedVoiceFrames = receivedVoiceFrames,
            forwardedVoiceFrames = forwardedVoiceFrames,
            droppedDuplicates = droppedDuplicates,
            droppedInvalid = droppedInvalid,
            droppedTtl = droppedTtl,
            droppedStaleTimestamp = droppedStaleTimestamp,
            droppedBadVersion = droppedBadVersion,
            droppedUnknownSource = droppedUnknownSource,
            droppedOversize = droppedOversize,
            droppedStaleEpoch = droppedStaleEpoch,
            idCollision = idCollision,
            droppedAtLinkBuffer = _droppedAtLinkBuffer.get(),
            graphVersion = graph.version,
            linkState = linkState,
            localIp = localIp,
            broadcastAddress = broadcastAddress,
            authenticatedNeighborsLast10s = neighborsCount,
            isolationWarningHint = isolationWarningHint,
            txDatagramsPerSec = txDatagramsPerSec,
            rxDatagramsPerSec = rxDatagramsPerSec,
            jitterLateDropped = jitterLateDropped,
            jitterOverflowDropped = jitterOverflowDropped,
            jitterConcealed = jitterConcealed,
            playoutUnderruns = playoutUnderruns,
            activeSourceCount = activeSourceCount,
            txBytesPerSec = txBytesPerSec,
            rxBytesPerSec = rxBytesPerSec,
            lastVoiceDatagramBytes = lastVoiceDatagramBytes,
            avgVoiceDatagramBytes = avgVoiceDatagramBytes,
            jitterDepthFrames = jitterDepthFrames,
            jitterTargetFrames = jitterTargetFrames,
            trackUnderrunCount = trackUnderrunCount,
            concealedMidTalkspurt = concealedMidTalkspurt,
            captureToSendMs = captureToSendMs,
            rxToPlayoutMs = rxToPlayoutMs
        )
    }

    fun reset() {
        _droppedAtLinkBuffer.set(0L)
        _snapshot.value = MeshDiagnosticsSnapshot()
    }
}
