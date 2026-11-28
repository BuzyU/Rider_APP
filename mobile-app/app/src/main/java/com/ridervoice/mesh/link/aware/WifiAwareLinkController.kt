package com.ridervoice.mesh.link.aware

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.session.OfflineSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

enum class WifiAwareStatus {
    IDLE,
    UNSUPPORTED,
    DISABLED_WIFI_OFF,
    MISSING_PERMISSION,
    UNAVAILABLE,
    ATTACHING,
    DISCOVERING,
    ACTIVE_CHAIN,
    FAILED
}

data class AwareRuntimeContext(
    val localMemberId: String,
    val meshParticipants: List<String>,
    val plannedChain: List<String>,
    val localLatE6: Int = 0,
    val localLonE6: Int = 0,
    val hasValidGps: Boolean = false,
    val isLiveOfflineVoice: Boolean = false,
    val directTimeoutMs: Long = MeshConfig.ACTIVE_NEIGHBOR_TIMEOUT_MS,
    val graphAdjacency: Map<String, Set<String>> = emptyMap(),
    val reachableMemberIds: Set<String> = emptySet(),
    val p2pFallbackReady: Boolean = false,
    val expectUdpTraffic: Boolean = true
)

interface WifiAwareLinkController {
    val status: StateFlow<WifiAwareStatus>
    val isAwareAvailable: StateFlow<Boolean>
    val activePeerCount: StateFlow<Int>
    val discoveredPeerCount: StateFlow<Int>
    val maxNdpSessions: StateFlow<Int>
    val activePeerEndpoints: StateFlow<List<MeshEndpoint>>
    val incomingFrames: Flow<MeshFrame>
    var onEndpointReady: (() -> Unit)?
        get() = null
        set(_) {}

    fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean
    fun handlesEndpoint(endpoint: MeshEndpoint): Boolean
    fun datagramRates(): Pair<Int, Int> = Pair(0, 0)
    fun byteRates(): Pair<Int, Int> = Pair(0, 0)
    fun noteAuthenticatedUdpFrom(endpoint: MeshEndpoint, nowMs: Long) {}
    fun ensureActive(session: OfflineSession, runtimeContext: AwareRuntimeContext)
    fun pauseForFallback() { stop() }
    fun stop()
}

object NoOpWifiAwareLinkController : WifiAwareLinkController {
    override val status: StateFlow<WifiAwareStatus> = MutableStateFlow(WifiAwareStatus.IDLE)
    override val isAwareAvailable: StateFlow<Boolean> = MutableStateFlow(false)
    override val activePeerCount: StateFlow<Int> = MutableStateFlow(0)
    override val discoveredPeerCount: StateFlow<Int> = MutableStateFlow(0)
    override val maxNdpSessions: StateFlow<Int> = MutableStateFlow(0)
    override val activePeerEndpoints: StateFlow<List<MeshEndpoint>> = MutableStateFlow(emptyList())
    override val incomingFrames: Flow<MeshFrame> = emptyFlow()

    override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean = false
    override fun handlesEndpoint(endpoint: MeshEndpoint): Boolean = false
    override fun ensureActive(session: OfflineSession, runtimeContext: AwareRuntimeContext) {}
    override fun stop() {}
}
