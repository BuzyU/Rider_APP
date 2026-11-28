package com.ridervoice.mesh.link

import com.ridervoice.mesh.link.aware.WifiAwareLinkController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Multiplexes UDP-over-Wi-Fi/P2P ([primaryLink]) with multi-peer Wi-Fi Aware IPv6 NDPs
 * ([awareController]) behind a single [MeshLink] contract.
 */
class CompositeMeshLink(
    private val primaryLink: MeshLink,
    private val awareController: WifiAwareLinkController,
    private val monotonicClock: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : MeshLink {

    override val linkId: String = "${primaryLink.linkId}+aware"

    private val _state = MutableStateFlow(MeshLinkState.STOPPED)
    override val state: StateFlow<MeshLinkState> = _state.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 256)
    override val incomingFrames: Flow<MeshFrame> = _incomingFrames.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var primaryForwardJob: Job? = null
    private var awareForwardJob: Job? = null
    private var stateForwardJob: Job? = null
    @Volatile
    var onNewAwareEndpoint: (() -> Unit)? = null

    fun setExpiryWindowProvider(provider: () -> Long) {
        (primaryLink as? UdpMeshLink)?.setExpiryWindowProvider(provider)
    }

    override suspend fun start() {
        awareController.onEndpointReady = { onNewAwareEndpoint?.invoke() }
        if (primaryForwardJob == null) {
            primaryForwardJob = scope.launch {
                try {
                    primaryLink.incomingFrames.collect { frame ->
                        _incomingFrames.tryEmit(frame)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        if (awareForwardJob == null) {
            awareForwardJob = scope.launch {
                try {
                    awareController.incomingFrames.collect { frame ->
                        _incomingFrames.tryEmit(frame)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        if (stateForwardJob == null) {
            stateForwardJob = scope.launch {
                try {
                    kotlinx.coroutines.flow.combine(
                        primaryLink.state,
                        awareController.activePeerCount
                    ) { pState, awareCount -> pState to awareCount }
                        .collect { refreshAggregateState() }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        primaryLink.start()
        refreshAggregateState()
    }

    private fun refreshAggregateState() {
        val pState = primaryLink.state.value
        val awareActive = awareController.activePeerCount.value > 0
        _state.value = when {
            pState == MeshLinkState.RUNNING || awareActive -> MeshLinkState.RUNNING
            else -> pState
        }
    }

    override suspend fun stop() {
        primaryForwardJob?.cancel()
        primaryForwardJob = null
        awareForwardJob?.cancel()
        awareForwardJob = null
        stateForwardJob?.cancel()
        stateForwardJob = null
        awareController.onEndpointReady = null
        primaryLink.stop()
        _state.value = MeshLinkState.STOPPED
    }

    override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
        return if (awareController.handlesEndpoint(endpoint)) {
            awareController.send(endpoint, payload)
        } else {
            primaryLink.send(endpoint, payload)
        }
    }

    override fun endpoints(): Set<MeshEndpoint> {
        val primaryEps = primaryLink.endpoints()
        val awareEps = awareController.activePeerEndpoints.value
        if (awareEps.isEmpty()) return primaryEps
        return LinkedHashSet<MeshEndpoint>(primaryEps.size + awareEps.size).apply {
            addAll(primaryEps)
            addAll(awareEps)
        }
    }

    override fun noteAuthenticatedEndpoint(endpoint: MeshEndpoint) {
        if (awareController.handlesEndpoint(endpoint)) {
            awareController.noteAuthenticatedUdpFrom(endpoint, monotonicClock())
        } else {
            primaryLink.noteAuthenticatedEndpoint(endpoint)
        }
    }

    override fun datagramRates(): Pair<Int, Int> {
        val (pTx, pRx) = primaryLink.datagramRates()
        val (aTx, aRx) = awareController.datagramRates()
        return Pair(pTx + aTx, pRx + aRx)
    }

    override fun byteRates(): Pair<Int, Int> {
        val (pTx, pRx) = primaryLink.byteRates()
        val (aTx, aRx) = awareController.byteRates()
        return Pair(pTx + aTx, pRx + aRx)
    }
}
