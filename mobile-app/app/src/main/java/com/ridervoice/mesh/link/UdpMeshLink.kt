package com.ridervoice.mesh.link

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.MeshLog
import com.ridervoice.mesh.OfflineMeshGate
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UDP implementation of [MeshLink] over a shared local subnet.
 * Never opens sockets if [gateAllowed] returns false.
 */
class UdpMeshLink(
    override val linkId: String = "udp-subnet",
    private val bindPort: Int = MeshConfig.MESH_PORT,
    private val gateAllowed: () -> Boolean = { OfflineMeshGate.isOfflineAllowed() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val expiryWindowMs: Long = MeshConfig.NEIGHBOR_TIMEOUT_MS,
    private val networkBinder: (DatagramSocket) -> Unit = {},
    private val onBufferDropped: () -> Unit = {}
) : MeshLink {

    private val _state = MutableStateFlow(MeshLinkState.STOPPED)
    override val state: StateFlow<MeshLinkState> = _state.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 256)
    override val incomingFrames: Flow<MeshFrame> = _incomingFrames.asSharedFlow()

    private val endpointTimestamps = ConcurrentHashMap<MeshEndpoint, Long>()
    private val resolvedAddresses = ConcurrentHashMap<String, InetAddress>()

    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        if (throwable is kotlinx.coroutines.CancellationException) throw throwable
        MeshLog.w("UdpMeshLink", "unhandled coroutine error: ${throwable.message}")
    }

    @Volatile
    private var socket: DatagramSocket? = null
    private var scope: CoroutineScope? = null
    private var receiveJob: Job? = null

    private val txCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val rxCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val txBytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val rxBytes = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var lastRateCalcMs = 0L
    @Volatile private var lastTxSnapshot = 0L
    @Volatile private var lastRxSnapshot = 0L
    @Volatile private var currentTxRate = 0
    @Volatile private var currentRxRate = 0
    @Volatile private var lastTxBytesSnapshot = 0L
    @Volatile private var lastRxBytesSnapshot = 0L
    @Volatile private var currentTxByteRate = 0
    @Volatile private var currentRxByteRate = 0

    val totalTxDatagrams: Long get() = txCount.get()
    val totalRxDatagrams: Long get() = rxCount.get()
    val totalTxBytes: Long get() = txBytes.get()
    val totalRxBytes: Long get() = rxBytes.get()

    override fun datagramRates(): Pair<Int, Int> = calculateDatagramRates(clock())
    override fun byteRates(): Pair<Int, Int> = calculateByteRates(clock())

    fun calculateDatagramRates(nowMs: Long = clock()): Pair<Int, Int> {
        synchronized(this) {
            val dt = nowMs - lastRateCalcMs
            if (dt >= 1000L) {
                val curTx = txCount.get()
                val curRx = rxCount.get()
                val curTxB = txBytes.get()
                val curRxB = rxBytes.get()
                currentTxRate = if (lastRateCalcMs == 0L) 0 else (((curTx - lastTxSnapshot) * 1000L) / dt).toInt()
                currentRxRate = if (lastRateCalcMs == 0L) 0 else (((curRx - lastRxSnapshot) * 1000L) / dt).toInt()
                currentTxByteRate = if (lastRateCalcMs == 0L) 0 else (((curTxB - lastTxBytesSnapshot) * 1000L) / dt).toInt()
                currentRxByteRate = if (lastRateCalcMs == 0L) 0 else (((curRxB - lastRxBytesSnapshot) * 1000L) / dt).toInt()
                lastRateCalcMs = nowMs
                lastTxSnapshot = curTx
                lastRxSnapshot = curRx
                lastTxBytesSnapshot = curTxB
                lastRxBytesSnapshot = curRxB
            }
            return Pair(currentTxRate, currentRxRate)
        }
    }

    fun calculateByteRates(nowMs: Long = clock()): Pair<Int, Int> {
        synchronized(this) {
            val dt = nowMs - lastRateCalcMs
            if (dt >= 1000L) {
                calculateDatagramRates(nowMs)
            }
            return Pair(currentTxByteRate, currentRxByteRate)
        }
    }

    override suspend fun start() {
        if (!gateAllowed()) {
            _state.value = MeshLinkState.STOPPED
            return
        }
        if (_state.value == MeshLinkState.RUNNING || _state.value == MeshLinkState.STARTING) return
        _state.value = MeshLinkState.STARTING

        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                runCatching { trafficClass = 0xB8 }
                runCatching { receiveBufferSize = 256 * 1024 }
                runCatching { sendBufferSize = 128 * 1024 }
                networkBinder(this)
                bind(InetSocketAddress(bindPort))
            }
            socket = sock
            val newScope = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler)
            scope = newScope
            _state.value = MeshLinkState.RUNNING

            receiveJob = newScope.launch {
                val buf = ByteArray(2048)
                var backoffMs = 100L
                while (isActive && _state.value == MeshLinkState.RUNNING) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
                        rxCount.incrementAndGet()
                        rxBytes.addAndGet(packet.length.toLong())
                        backoffMs = 100L
                        val host = packet.address?.hostAddress ?: continue
                        val from = MeshEndpoint(host, packet.port)
                        val copy = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        val emitted = _incomingFrames.tryEmit(MeshFrame(from, copy))
                        if (!emitted) {
                            onBufferDropped()
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (!isActive || _state.value != MeshLinkState.RUNNING) break
                        if (sock.isClosed) {
                            _state.value = MeshLinkState.FAILED
                            break
                        }
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(2_000L)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            MeshLog.w("UdpMeshLink", "Socket setup failed: ${e.javaClass.simpleName}")
            _state.value = MeshLinkState.FAILED
            closeSocket()
        }
    }

    override suspend fun stop() {
        _state.value = MeshLinkState.STOPPED
        receiveJob?.cancel()
        receiveJob = null
        scope?.cancel()
        scope = null
        closeSocket()
        endpointTimestamps.clear()
        resolvedAddresses.clear()
    }

    override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
        if (!gateAllowed() || _state.value != MeshLinkState.RUNNING) return false
        val sock = socket ?: return false
        return try {
            val addr = resolveEndpointAddress(endpoint.host)
            val pkt = DatagramPacket(payload, payload.size, addr, endpoint.port)
            sock.send(pkt)
            txCount.incrementAndGet()
            txBytes.addAndGet(payload.size.toLong())
            true
        } catch (e: Exception) {
            MeshLog.w("UdpMeshLink", "Send datagram failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun resolveEndpointAddress(host: String): InetAddress {
        resolvedAddresses[host]?.let { return it }
        val parsed = parseIpv4Literal(host) ?: InetAddress.getByName(host)
        resolvedAddresses[host] = parsed
        return parsed
    }

    private fun parseIpv4Literal(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val octet = parts[i].toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            bytes[i] = octet.toByte()
        }
        return InetAddress.getByAddress(host, bytes)
    }

    override fun endpoints(): Set<MeshEndpoint> {
        val now = clock()
        endpointTimestamps.entries.removeIf { now - it.value > expiryWindowMs }
        return endpointTimestamps.keys.toSet()
    }

    override fun noteAuthenticatedEndpoint(endpoint: MeshEndpoint) {
        registerEndpoint(endpoint)
    }

    fun registerEndpoint(endpoint: MeshEndpoint) {
        endpointTimestamps[endpoint] = clock()
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        fun getLocalIpAddress(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces()?.asSequence()
                    ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    ?.flatMap { nif ->
                        nif.interfaceAddresses.asSequence()
                            .filter { it.address is Inet4Address }
                            .mapNotNull { it.address?.hostAddress }
                    }
                    ?.firstOrNull()
            }.getOrNull()
        }

        fun getLocalBroadcastAddress(): String {
            return discoverBroadcastEndpoints().firstOrNull()?.host ?: "255.255.255.255"
        }

        fun discoverBroadcastEndpoints(port: Int = MeshConfig.MESH_PORT): List<MeshEndpoint> {
            return runCatching {
                NetworkInterface.getNetworkInterfaces()?.asSequence()
                    ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    ?.flatMap { nif ->
                        nif.interfaceAddresses.asSequence()
                            .filter { it.address is Inet4Address }
                            .mapNotNull { it.broadcast?.hostAddress }
                    }
                    ?.distinct()
                    ?.map { MeshEndpoint(it, port) }
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())
                .ifEmpty { listOf(MeshEndpoint("255.255.255.255", port)) }
        }
    }
}
