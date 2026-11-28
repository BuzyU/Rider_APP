package com.ridervoice.mesh.link.aware

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.MeshLog
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.convoy.ConvoyHash
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.session.OfflineSession
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * Android API 29+ Wi-Fi Aware (NAN) Bus/Chain link controller.
 *
 * Establishes simultaneous IPv6 link-local UDP Data Paths (NDPs) to adjacent riders in the convoy
 * without requiring Mobile Hotspot or tearing down cellular 4G/5G connectivity.
 */
class AndroidWifiAwareLinkController(
    private val context: Context,
    private val gateOpen: () -> Boolean = { OfflineMeshGate.isBuildGateOpen() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : WifiAwareLinkController {

    companion object {
        private const val TAG = "WifiAwareLink"
        private const val BEACON_UPDATE_MIN_INTERVAL_MS = 1_500L
        private const val PLANNER_TICK_MS = 1_000L
        private const val DEFAULT_MAX_NDPS = 2
        private const val SESSION_HEALTH_GRACE_MS = 5_000L
        private const val HAL_TEARDOWN_SETTLE_MS = 250L
        private const val RESPONDER_ARM_YIELD_MS = 50L
        private val TERMINAL_PRESERVED_STATUSES = setOf(
            WifiAwareStatus.UNSUPPORTED,
            WifiAwareStatus.DISABLED_WIFI_OFF,
            WifiAwareStatus.MISSING_PERMISSION,
            WifiAwareStatus.UNAVAILABLE,
            WifiAwareStatus.FAILED
        )
    }

    private data class PeerRecord(
        val subPeerHandle: PeerHandle,
        val memberId: String,
        val memberTag: ByteArray,
        val rosterIdx: Int,
        var componentRootTag: ByteArray,
        var activeNdpCount: Int,
        var maxNdpSessions: Int,
        var latE6: Int,
        var lonE6: Int,
        var hasValidGps: Boolean,
        val discoveredAtMs: Long,
        @Volatile var lastBeaconMs: Long,
        @Volatile var rejectedUntilMs: Long = 0L,
        var peerIpv6: InetAddress? = null,
        var peerUdpPort: Int = 0
    )

    private class NdpChannel(
        val peerId: String,
        val peerHandle: PeerHandle,
        val isInitiator: Boolean,
        val epoch: Int,
        val establishedAtMs: Long,
        var socket: DatagramSocket? = null,
        var localPort: Int = 0,
        var network: Network? = null,
        @Volatile var networkCallback: ConnectivityManager.NetworkCallback? = null,
        val unregistered: AtomicBoolean = AtomicBoolean(false),
        var receiveJob: Job? = null,
        @Volatile var peerIpv6: InetAddress? = null,
        @Volatile var peerUdpPort: Int = 0,
        @Volatile private var _lastUdpAliveMs: Long = 0L,
        @Volatile var activeEndpoint: MeshEndpoint? = null,
        @Volatile var state: AwareChainLinkPlanner.AwareLinkState = AwareChainLinkPlanner.AwareLinkState.CONNECTING
    ) {
        var lastUdpAliveMs: Long
            get() = _lastUdpAliveMs
            set(value) { _lastUdpAliveMs = value }
    }

    private val appContext = context.applicationContext ?: context
    private val awareManager = runCatching {
        appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }.getOrNull()
    private val wifiManager = runCatching {
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }.getOrNull()
    private val connectivityManager = runCatching {
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }.getOrNull()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _status = MutableStateFlow(WifiAwareStatus.IDLE)
    override val status: StateFlow<WifiAwareStatus> = _status.asStateFlow()

    private val _isAwareAvailable = MutableStateFlow(false)
    override val isAwareAvailable: StateFlow<Boolean> = _isAwareAvailable.asStateFlow()

    private val _activePeerCount = MutableStateFlow(0)
    override val activePeerCount: StateFlow<Int> = _activePeerCount.asStateFlow()

    private val _discoveredPeerCount = MutableStateFlow(0)
    override val discoveredPeerCount: StateFlow<Int> = _discoveredPeerCount.asStateFlow()

    private val _maxNdpSessions = MutableStateFlow(0)
    override val maxNdpSessions: StateFlow<Int> = _maxNdpSessions.asStateFlow()

    private val _activePeerEndpoints = MutableStateFlow<List<MeshEndpoint>>(emptyList())
    override val activePeerEndpoints: StateFlow<List<MeshEndpoint>> = _activePeerEndpoints.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 256)
    override val incomingFrames: Flow<MeshFrame> = _incomingFrames.asSharedFlow()

    @Volatile
    override var onEndpointReady: (() -> Unit)? = null

    private val stateLock = Any()
    private val channelLock = Any()
    private var receiverRegistered = false
    private var handlerThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    @Volatile private var activeSession: OfflineSession? = null
    @Volatile private var activeServiceName: String? = null
    @Volatile private var latestContext: AwareRuntimeContext? = null

    @Volatile private var awareSession: WifiAwareSession? = null
    @Volatile private var publishSession: PublishDiscoverySession? = null
    @Volatile private var subscribeSession: SubscribeDiscoverySession? = null
    @Volatile private var attachInFlight = false
    @Volatile private var attachedAtMs = 0L
    private var plannerJob: Job? = null

    private val peersByMemberId = ConcurrentHashMap<String, PeerRecord>()
    private val memberIdByTagHex = ConcurrentHashMap<String, String>()
    private val channelsByPeerId = ConcurrentHashMap<String, NdpChannel>()
    private val channelByEndpoint = ConcurrentHashMap<MeshEndpoint, NdpChannel>()
    private val pendingAcceptPeers = ConcurrentHashMap.newKeySet<String>()

    private val messageIdSeq = AtomicInteger(1)
    private val ndpEpochSeq = AtomicInteger(1)
    private var lastBeaconSentMs = 0L
    private var lastBeaconBytes: ByteArray? = null

    private val txCount = AtomicLong(0L)
    private val rxCount = AtomicLong(0L)
    private val txBytes = AtomicLong(0L)
    private val rxBytes = AtomicLong(0L)
    @Volatile private var lastRateCalcMs = 0L
    @Volatile private var lastTxSnapshot = 0L
    @Volatile private var lastRxSnapshot = 0L
    @Volatile private var currentTxRate = 0
    @Volatile private var currentRxRate = 0
    @Volatile private var lastTxBytesSnapshot = 0L
    @Volatile private var lastRxBytesSnapshot = 0L
    @Volatile private var currentTxByteRate = 0
    @Volatile private var currentRxByteRate = 0

    private val awareReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    if (state == WifiManager.WIFI_STATE_DISABLED) {
                        handleRadioUnavailable(WifiAwareStatus.DISABLED_WIFI_OFF)
                    } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                        retriggerIfActive()
                    }
                }
                WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED -> {
                    val available = runCatching { awareManager?.isAvailable == true }.getOrDefault(false)
                    _isAwareAvailable.value = available
                    if (!available) {
                        val target = if (!isWifiRadioEnabled()) {
                            WifiAwareStatus.DISABLED_WIFI_OFF
                        } else {
                            WifiAwareStatus.UNAVAILABLE
                        }
                        handleRadioUnavailable(target)
                    } else {
                        retriggerIfActive()
                    }
                }
            }
        }
    }

    override fun ensureActive(session: OfflineSession, runtimeContext: AwareRuntimeContext) {
        if (!gateOpen()) return
        if (!hasHardwareFeature() || sdkInt < Build.VERSION_CODES.Q || awareManager == null || connectivityManager == null) {
            _isAwareAvailable.value = false
            _status.value = WifiAwareStatus.UNSUPPORTED
            return
        }
        val serviceName = WifiAwareCredentials.deriveServiceName(session) ?: return

        synchronized(stateLock) {
            registerReceiverLocked()
            val sessionChanged = activeSession?.sessionId != session.sessionId || activeServiceName != serviceName
            activeSession = session
            activeServiceName = serviceName
            latestContext = runtimeContext

            if (!isWifiRadioEnabled()) {
                _status.value = WifiAwareStatus.DISABLED_WIFI_OFF
                return
            }
            if (!hasRequiredPermissions()) {
                _status.value = WifiAwareStatus.MISSING_PERMISSION
                return
            }
            val avail = runCatching { awareManager?.isAvailable == true }.getOrDefault(false)
            _isAwareAvailable.value = avail
            if (!avail) {
                _status.value = WifiAwareStatus.UNAVAILABLE
                return
            }

            updateHardwareCharacteristics()

            val brokenDiscovery = awareSession != null && !attachInFlight &&
                (publishSession == null || subscribeSession == null) &&
                (clock() - attachedAtMs) > SESSION_HEALTH_GRACE_MS

            if (sessionChanged || brokenDiscovery) {
                teardownAwareSessionsLocked()
                attachIfNeededLocked()
            } else if (awareSession == null && !attachInFlight) {
                attachIfNeededLocked()
            }

            if (plannerJob?.isActive != true) {
                startPlannerLoopLocked()
            }
        }
    }

    override fun pauseForFallback() {
        synchronized(stateLock) {
            plannerJob?.cancel()
            plannerJob = null
            lastBeaconBytes = null
            teardownAwareSessionsLocked()
            // Keep BroadcastReceiver + activeSession registered for automatic radio recovery
            if (_status.value !in TERMINAL_PRESERVED_STATUSES) {
                _status.value = WifiAwareStatus.IDLE
            }
        }
        _activePeerCount.value = 0
        _discoveredPeerCount.value = 0
        _activePeerEndpoints.value = emptyList()
    }

    override fun stop() {
        synchronized(stateLock) {
            plannerJob?.cancel()
            plannerJob = null
            activeSession = null
            activeServiceName = null
            latestContext = null
            lastBeaconBytes = null
            teardownAwareSessionsLocked()
            unregisterReceiverLocked()
            handlerThread?.quitSafely()
            handlerThread = null
            callbackHandler = null
        }
        _status.value = WifiAwareStatus.IDLE
        _activePeerCount.value = 0
        _discoveredPeerCount.value = 0
        _activePeerEndpoints.value = emptyList()
    }

    override fun handlesEndpoint(endpoint: MeshEndpoint): Boolean {
        synchronized(channelLock) {
            if (channelByEndpoint.containsKey(endpoint)) return true
            val strippedTarget = stripIpv6Zone(endpoint.host) ?: return false
            return channelsByPeerId.values.any { ch ->
                ch.state == AwareChainLinkPlanner.AwareLinkState.ACTIVE &&
                    stripIpv6Zone(ch.peerIpv6?.hostAddress) == strippedTarget
            }
        }
    }

    override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
        if (!gateOpen()) return false
        val channel = findChannelForEndpoint(endpoint) ?: return false
        val sock = channel.socket ?: return false
        val peerAddr = channel.peerIpv6 ?: return false
        val targetPort = if (channel.peerUdpPort > 0) channel.peerUdpPort else endpoint.port
        if (targetPort <= 0) return false
        return try {
            val pkt = DatagramPacket(payload, payload.size, peerAddr, targetPort)
            sock.send(pkt)
            txCount.incrementAndGet()
            txBytes.addAndGet(payload.size.toLong())
            true
        } catch (e: Exception) {
            MeshLog.w(TAG, "Aware UDP send failed to ${channel.peerId.take(8)}: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun noteAuthenticatedUdpFrom(endpoint: MeshEndpoint, nowMs: Long) {
        findChannelForEndpoint(endpoint)?.lastUdpAliveMs = nowMs
    }

    private fun findChannelForEndpoint(endpoint: MeshEndpoint): NdpChannel? {
        synchronized(channelLock) {
            channelByEndpoint[endpoint]?.let { return it }
            val exactHostMatch = channelsByPeerId.values.firstOrNull { ch ->
                ch.state == AwareChainLinkPlanner.AwareLinkState.ACTIVE &&
                    ch.peerIpv6?.hostAddress == endpoint.host
            }
            if (exactHostMatch != null) return exactHostMatch
            val stripped = stripIpv6Zone(endpoint.host) ?: return null
            return channelsByPeerId.values.firstOrNull { ch ->
                ch.state == AwareChainLinkPlanner.AwareLinkState.ACTIVE &&
                    stripIpv6Zone(ch.peerIpv6?.hostAddress) == stripped
            }
        }
    }

    private fun stripIpv6Zone(host: String?): String? {
        if (host.isNullOrBlank() || !host.contains(':')) return null
        val pct = host.indexOf('%')
        return if (pct >= 0) host.substring(0, pct) else host
    }

    override fun datagramRates(): Pair<Int, Int> = calculateRates(clock())
    override fun byteRates(): Pair<Int, Int> = calculateByteRates(clock())

    private fun calculateRates(nowMs: Long): Pair<Int, Int> = synchronized(this) {
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
        Pair(currentTxRate, currentRxRate)
    }

    private fun calculateByteRates(nowMs: Long): Pair<Int, Int> = synchronized(this) {
        if (nowMs - lastRateCalcMs >= 1000L) calculateRates(nowMs)
        Pair(currentTxByteRate, currentRxByteRate)
    }

    private fun handleRadioUnavailable(targetStatus: WifiAwareStatus) {
        synchronized(stateLock) {
            teardownAwareSessionsLocked()
            if (activeSession != null) {
                _status.value = targetStatus
            }
        }
        _activePeerCount.value = 0
        _discoveredPeerCount.value = 0
        _activePeerEndpoints.value = emptyList()
    }

    private fun retriggerIfActive() {
        synchronized(stateLock) {
            val sess = activeSession ?: return
            val ctx = latestContext ?: return
            scope.launch { ensureActive(sess, ctx) }
        }
    }

    private fun updateHardwareCharacteristics() {
        val mgr = awareManager ?: return
        val maxNdps = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                mgr.characteristics?.numberOfSupportedDataPaths ?: DEFAULT_MAX_NDPS
            } else {
                DEFAULT_MAX_NDPS
            }
        }.getOrDefault(DEFAULT_MAX_NDPS).coerceAtLeast(1)
        _maxNdpSessions.value = maxNdps
    }

    private fun getOrCreateHandlerLocked(): Handler {
        callbackHandler?.let { return it }
        val thread = HandlerThread("RiderVoice-WifiAware").apply { start() }
        handlerThread = thread
        return Handler(thread.looper).also { callbackHandler = it }
    }

    private fun buildPublishConfig(serviceName: String, beaconBytes: ByteArray): PublishConfig {
        return PublishConfig.Builder()
            .setServiceName(serviceName)
            .setServiceSpecificInfo(beaconBytes)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun attachIfNeededLocked() {
        val mgr = awareManager ?: return
        val session = activeSession ?: return
        val serviceName = activeServiceName ?: return
        if (awareSession != null || attachInFlight) return
        attachInFlight = true
        _status.value = WifiAwareStatus.ATTACHING
        val handler = getOrCreateHandlerLocked()

        try {
            mgr.attach(object : AttachCallback() {
                override fun onAttached(sessionObj: WifiAwareSession) {
                    synchronized(stateLock) {
                        attachInFlight = false
                        if (activeSession?.sessionId != session.sessionId) {
                            runCatching { sessionObj.close() }
                            return
                        }
                        awareSession = sessionObj
                        attachedAtMs = clock()
                        _status.value = WifiAwareStatus.DISCOVERING
                        startPublishAndSubscribeLocked(sessionObj, serviceName, handler)
                    }
                }

                override fun onAttachFailed() {
                    synchronized(stateLock) {
                        attachInFlight = false
                        awareSession = null
                        _status.value = WifiAwareStatus.FAILED
                    }
                }
            }, handler)
        } catch (e: Exception) {
            attachInFlight = false
            MeshLog.w(TAG, "Wi-Fi Aware attach failed: ${e.javaClass.simpleName}")
            _status.value = WifiAwareStatus.FAILED
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun startPublishAndSubscribeLocked(
        sessionObj: WifiAwareSession,
        serviceName: String,
        handler: Handler
    ) {
        val initialBeacon = buildLocalBeaconBytes()
        lastBeaconBytes = initialBeacon
        lastBeaconSentMs = clock()

        val pubConfig = buildPublishConfig(serviceName, initialBeacon)
        runCatching {
            sessionObj.publish(pubConfig, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    synchronized(stateLock) {
                        if (awareSession !== sessionObj) {
                            runCatching { session.close() }
                            return
                        }
                        publishSession = session
                    }
                    MeshLog.d(TAG, "Wi-Fi Aware publish started ($serviceName)")
                }

                override fun onSessionConfigFailed() {
                    synchronized(stateLock) {
                        if (awareSession !== sessionObj) return
                        publishSession = null
                        _status.value = WifiAwareStatus.FAILED
                    }
                    MeshLog.w(TAG, "Wi-Fi Aware publish config failed ($serviceName)")
                }

                override fun onSessionTerminated() {
                    synchronized(stateLock) {
                        if (awareSession !== sessionObj) return
                        publishSession = null
                    }
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    handleL2Message(peerHandle, message, viaPublish = true)
                }
            }, handler)
        }

        val subConfig = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()
        runCatching {
            sessionObj.subscribe(subConfig, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    synchronized(stateLock) {
                        if (awareSession !== sessionObj) {
                            runCatching { session.close() }
                            return
                        }
                        subscribeSession = session
                    }
                    MeshLog.d(TAG, "Wi-Fi Aware subscribe started ($serviceName)")
                }

                override fun onSessionConfigFailed() {
                    synchronized(stateLock) {
                        if (awareSession !== sessionObj) return
                        subscribeSession = null
                        _status.value = WifiAwareStatus.FAILED
                    }
                    MeshLog.w(TAG, "Wi-Fi Aware subscribe config failed ($serviceName)")
                }

                override fun onSessionTerminated() {
                    synchronized(stateLock) {
                        if (awareSession !== sessionObj) return
                        subscribeSession = null
                    }
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: MutableList<ByteArray>?
                ) {
                    if (serviceSpecificInfo != null) {
                        handleDiscoveredBeacon(peerHandle, serviceSpecificInfo)
                    }
                }

                override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
                    val now = clock()
                    peersByMemberId.values.firstOrNull { it.subPeerHandle == peerHandle }?.let { rec ->
                        rec.lastBeaconMs = minOf(rec.lastBeaconMs, now - MeshConfig.AWARE_BEACON_TTL_MS - 1L)
                    }
                    pruneAndUpdateDiscoveredCount(now)
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    handleL2Message(peerHandle, message, viaPublish = false)
                }
            }, handler)
        }
    }

    private fun pruneAndUpdateDiscoveredCount(nowMs: Long) {
        val hardExpireMs = MeshConfig.AWARE_BEACON_TTL_MS * 2L
        peersByMemberId.entries.removeIf { nowMs - it.value.lastBeaconMs > hardExpireMs }
        _discoveredPeerCount.value = peersByMemberId.values.count {
            (nowMs - it.lastBeaconMs) <= MeshConfig.AWARE_BEACON_TTL_MS
        }
    }

    private fun handleDiscoveredBeacon(peerHandle: PeerHandle, rawBeacon: ByteArray) {
        val beacon = AwareDiscoveryBeacon.decode(rawBeacon) ?: return
        val session = activeSession ?: return
        val ctx = latestContext ?: return
        val memberId = resolveMemberId(session.sessionId, ctx.meshParticipants, beacon.memberTag) ?: return
        if (memberId == ctx.localMemberId) return

        val now = clock()
        val hex = beacon.memberTag.toHex()
        memberIdByTagHex[hex] = memberId
        val existing = peersByMemberId[memberId]
        if (existing == null) {
            peersByMemberId[memberId] = PeerRecord(
                subPeerHandle = peerHandle,
                memberId = memberId,
                memberTag = beacon.memberTag,
                rosterIdx = beacon.rosterIdx,
                componentRootTag = beacon.componentRootTag,
                activeNdpCount = beacon.activeNdpCount,
                maxNdpSessions = beacon.maxNdpSessions.coerceAtLeast(1),
                latE6 = beacon.latE6,
                lonE6 = beacon.lonE6,
                hasValidGps = beacon.hasValidGps,
                discoveredAtMs = now,
                lastBeaconMs = now
            )
        } else {
            peersByMemberId[memberId] = existing.copy(
                subPeerHandle = peerHandle,
                rosterIdx = beacon.rosterIdx,
                componentRootTag = beacon.componentRootTag,
                activeNdpCount = beacon.activeNdpCount,
                maxNdpSessions = beacon.maxNdpSessions.coerceAtLeast(1),
                latE6 = beacon.latE6,
                lonE6 = beacon.lonE6,
                hasValidGps = beacon.hasValidGps,
                lastBeaconMs = now
            )
        }
        pruneAndUpdateDiscoveredCount(now)
    }

    private fun handleL2Message(peerHandle: PeerHandle, message: ByteArray, viaPublish: Boolean) {
        val decoded = AwareSignalingMessage.decode(message) ?: return
        val session = activeSession ?: return
        val ctx = latestContext ?: return
        val senderId = memberIdByTagHex[decoded.senderMemberTag.toHex()]
            ?: resolveMemberId(session.sessionId, ctx.meshParticipants, decoded.senderMemberTag)
            ?: return
        if (senderId == ctx.localMemberId) return

        when (decoded) {
            is AwareSignalingMessage.ConnectReq -> {
                if (!viaPublish) return
                val decision = AwareChainLinkPlanner.evaluateIncoming(
                    fromPeerId = senderId,
                    input = buildPlannerInput(session.sessionId, ctx, clock())
                )
                when (decision) {
                    is AwareChainLinkPlanner.IncomingDecision.Accept -> {
                        acceptIncomingNdp(session, ctx, senderId, peerHandle, decoded.udpPort, decoded.epoch)
                    }
                    is AwareChainLinkPlanner.IncomingDecision.AcceptAfterEvict -> {
                        if (!pendingAcceptPeers.add(senderId)) return
                        evictChannel(decision.evictPeerId, AwareSignalingMessage.REASON_REPLACED)
                        scope.launch {
                            try {
                                delay(HAL_TEARDOWN_SETTLE_MS)
                                val curSess = activeSession
                                val curCtx = latestContext
                                if (curSess != null && curSess.sessionId == session.sessionId && curCtx != null) {
                                    acceptIncomingNdp(curSess, curCtx, senderId, peerHandle, decoded.udpPort, decoded.epoch)
                                }
                            } finally {
                                pendingAcceptPeers.remove(senderId)
                            }
                        }
                    }
                    is AwareChainLinkPlanner.IncomingDecision.RejectFull -> {
                        sendL2ToPeer(publishSession, peerHandle, buildRejectBytes(session.sessionId, ctx.localMemberId, AwareSignalingMessage.REASON_FULL))
                    }
                    is AwareChainLinkPlanner.IncomingDecision.RejectNotNeeded -> {
                        sendL2ToPeer(publishSession, peerHandle, buildRejectBytes(session.sessionId, ctx.localMemberId, AwareSignalingMessage.REASON_NOT_NEEDED))
                    }
                }
            }
            is AwareSignalingMessage.ConnectAck -> {
                if (viaPublish) return
                val ch = synchronized(channelLock) { channelsByPeerId[senderId] } ?: return
                if (ch.epoch != decoded.epoch || !ch.isInitiator) return
                ch.peerUdpPort = decoded.udpPort
                startInitiatorNdp(session, ctx, senderId, peerHandle, ch)
            }
            is AwareSignalingMessage.ConnectReject -> {
                peersByMemberId[senderId]?.let { it.rejectedUntilMs = clock() + MeshConfig.AWARE_REJECT_COOLDOWN_MS }
                evictChannel(senderId, sendTeardown = false, reasonCode = decoded.reasonCode)
            }
            is AwareSignalingMessage.LinkNudge -> {
                if (viaPublish && WifiAwareCredentials.isInitiator(ctx.localMemberId, senderId)) {
                    initiateNdpIfEligible(session, ctx, senderId)
                }
            }
            is AwareSignalingMessage.LinkTeardown -> {
                evictChannel(senderId, sendTeardown = false, reasonCode = decoded.reasonCode)
            }
        }
    }

    private fun startPlannerLoopLocked() {
        plannerJob?.cancel()
        plannerJob = scope.launch {
            while (isActive) {
                delay(PLANNER_TICK_MS)
                val session = activeSession ?: break
                val svcName = activeServiceName ?: continue
                val ctx = latestContext ?: continue
                if (!isWifiRadioEnabled() || !hasRequiredPermissions()) continue

                val now = clock()
                if (awareSession != null && !attachInFlight &&
                    (publishSession == null || subscribeSession == null) &&
                    (now - attachedAtMs) > SESSION_HEALTH_GRACE_MS
                ) {
                    synchronized(stateLock) {
                        teardownAwareSessionsLocked()
                        attachIfNeededLocked()
                    }
                    continue
                }

                val pub = publishSession
                if (pub != null && (now - lastBeaconSentMs) >= BEACON_UPDATE_MIN_INTERVAL_MS) {
                    val newBeacon = buildLocalBeaconBytes()
                    if (lastBeaconBytes == null || !newBeacon.contentEquals(lastBeaconBytes)) {
                        val updated = runCatching {
                            pub.updatePublish(buildPublishConfig(svcName, newBeacon))
                        }.isSuccess
                        if (updated) {
                            lastBeaconBytes = newBeacon
                            lastBeaconSentMs = now
                        }
                    }
                }

                val decision = AwareChainLinkPlanner.planLinks(buildPlannerInput(session.sessionId, ctx, now))
                for (evictId in decision.peersToEvict) {
                    evictChannel(evictId, AwareSignalingMessage.REASON_REPLACED)
                }
                for (initId in decision.peersToInitiate) {
                    initiateNdpIfEligible(session, ctx, initId)
                }
                for (nudgeId in decision.peersToNudge) {
                    val peer = peersByMemberId[nudgeId] ?: continue
                    val idx = ctx.meshParticipants.indexOf(ctx.localMemberId).let { if (it >= 0) it else 0xFF }
                    val myTag = ConvoyHash.computeProposerTag(ConvoyHash.stableSessionUuid(session.sessionId), ctx.localMemberId)
                    sendL2ToPeer(subscribeSession, peer.subPeerHandle, AwareSignalingMessage.LinkNudge(myTag, idx).encode())
                }

                pruneAndUpdateDiscoveredCount(now)
                publishEndpointMetrics()
            }
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun initiateNdpIfEligible(
        session: OfflineSession,
        ctx: AwareRuntimeContext,
        peerId: String
    ) {
        if (sdkInt < Build.VERSION_CODES.Q) return
        val sub = subscribeSession ?: return
        val peer = peersByMemberId[peerId] ?: return

        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                runCatching { receiveBufferSize = 256 * 1024 }
                runCatching { sendBufferSize = 128 * 1024 }
                bind(InetSocketAddress(InetAddress.getByAddress(ByteArray(16)), 0))
            }
        } catch (e: Exception) {
            MeshLog.w(TAG, "Failed opening initiator UDP socket: ${e.javaClass.simpleName}")
            return
        }

        val epoch = ndpEpochSeq.incrementAndGet()
        val channel = NdpChannel(
            peerId = peerId,
            peerHandle = peer.subPeerHandle,
            isInitiator = true,
            epoch = epoch,
            establishedAtMs = clock(),
            socket = socket,
            localPort = socket.localPort,
            state = AwareChainLinkPlanner.AwareLinkState.CONNECTING
        )
        val maxNdps = _maxNdpSessions.value.let { if (it > 0) it else DEFAULT_MAX_NDPS }.coerceIn(1, 3)
        val inserted = synchronized(channelLock) {
            val reserved = pendingAcceptPeers.count { it != peerId && !channelsByPeerId.containsKey(it) }
            if (channelsByPeerId.containsKey(peerId) || (channelsByPeerId.size + reserved) >= maxNdps) {
                false
            } else {
                channelsByPeerId[peerId] = channel
                true
            }
        }
        if (!inserted) {
            runCatching { socket.close() }
            return
        }

        val myIdx = ctx.meshParticipants.indexOf(ctx.localMemberId).let { if (it >= 0) it else 0xFF }
        val myTag = ConvoyHash.computeProposerTag(ConvoyHash.stableSessionUuid(session.sessionId), ctx.localMemberId)
        val reqBytes = AwareSignalingMessage.ConnectReq(myTag, myIdx, socket.localPort, epoch).encode()
        if (!sendL2ToPeer(sub, peer.subPeerHandle, reqBytes)) {
            evictChannel(peerId, sendTeardown = false, reasonCode = AwareSignalingMessage.REASON_ZOMBIE)
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun startInitiatorNdp(
        session: OfflineSession,
        ctx: AwareRuntimeContext,
        peerId: String,
        peerHandle: PeerHandle,
        channel: NdpChannel
    ) {
        if (sdkInt < Build.VERSION_CODES.Q) return
        val sub = subscribeSession ?: return
        val cm = connectivityManager ?: return
        val psk = WifiAwareCredentials.derivePairPassphrase(session, ctx.localMemberId, peerId) ?: return

        val spec = WifiAwareNetworkSpecifier.Builder(sub, peerHandle)
            .setPskPassphrase(psk)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()
        val cb = buildNetworkCallback(peerId, channel)
        channel.networkCallback = cb
        val handler = synchronized(stateLock) { getOrCreateHandlerLocked() }
        try {
            cm.requestNetwork(req, cb, handler, MeshConfig.AWARE_CONNECT_TIMEOUT_MS.toInt())
        } catch (e: Exception) {
            MeshLog.w(TAG, "requestNetwork (initiator) failed: ${e.javaClass.simpleName}")
            evictChannel(peerId, sendTeardown = false, reasonCode = AwareSignalingMessage.REASON_ZOMBIE)
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun acceptIncomingNdp(
        session: OfflineSession,
        ctx: AwareRuntimeContext,
        peerId: String,
        peerHandle: PeerHandle,
        remoteUdpPort: Int,
        epoch: Int
    ) {
        if (sdkInt < Build.VERSION_CODES.Q) return
        val pub = publishSession ?: return
        val cm = connectivityManager ?: return
        val psk = WifiAwareCredentials.derivePairPassphrase(session, ctx.localMemberId, peerId) ?: return

        val existing = synchronized(channelLock) { channelsByPeerId[peerId] }
        if (existing != null) {
            if (existing.epoch == epoch && existing.state == AwareChainLinkPlanner.AwareLinkState.ACTIVE) return
            evictChannel(peerId, sendTeardown = false, reasonCode = AwareSignalingMessage.REASON_REPLACED)
        }

        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                runCatching { receiveBufferSize = 256 * 1024 }
                runCatching { sendBufferSize = 128 * 1024 }
                bind(InetSocketAddress(InetAddress.getByAddress(ByteArray(16)), 0))
            }
        } catch (e: Exception) {
            MeshLog.w(TAG, "Failed opening responder UDP socket: ${e.javaClass.simpleName}")
            return
        }

        val channel = NdpChannel(
            peerId = peerId,
            peerHandle = peerHandle,
            isInitiator = false,
            epoch = epoch,
            establishedAtMs = clock(),
            socket = socket,
            localPort = socket.localPort,
            peerUdpPort = remoteUdpPort,
            state = AwareChainLinkPlanner.AwareLinkState.CONNECTING
        )
        val maxNdps = _maxNdpSessions.value.let { if (it > 0) it else DEFAULT_MAX_NDPS }.coerceIn(1, 3)
        val accepted = synchronized(channelLock) {
            val reservedOther = pendingAcceptPeers.count { it != peerId && !channelsByPeerId.containsKey(it) }
            if (!channelsByPeerId.containsKey(peerId) && (channelsByPeerId.size + reservedOther) >= maxNdps) {
                false
            } else {
                channelsByPeerId[peerId] = channel
                true
            }
        }
        if (!accepted) {
            runCatching { socket.close() }
            sendL2ToPeer(pub, peerHandle, buildRejectBytes(session.sessionId, ctx.localMemberId, AwareSignalingMessage.REASON_FULL))
            return
        }

        val spec = WifiAwareNetworkSpecifier.Builder(pub, peerHandle)
            .setPskPassphrase(psk)
            .setPort(socket.localPort)
            .setTransportProtocol(OsConstants.IPPROTO_UDP)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()
        val cb = buildNetworkCallback(peerId, channel)
        channel.networkCallback = cb
        val handler = synchronized(stateLock) { getOrCreateHandlerLocked() }
        try {
            cm.requestNetwork(req, cb, handler, MeshConfig.AWARE_CONNECT_TIMEOUT_MS.toInt())
            val myIdx = ctx.meshParticipants.indexOf(ctx.localMemberId).let { if (it >= 0) it else 0xFF }
            val myTag = ConvoyHash.computeProposerTag(ConvoyHash.stableSessionUuid(session.sessionId), ctx.localMemberId)
            val ackBytes = AwareSignalingMessage.ConnectAck(myTag, myIdx, socket.localPort, epoch).encode()
            // Brief yield so ConnectivityService finishes arming Responder's pending NDP before Initiator transmits NDP_INITIATE
            scope.launch {
                delay(RESPONDER_ARM_YIELD_MS)
                val stillValid = synchronized(channelLock) {
                    !channel.unregistered.get() && channelsByPeerId[peerId] === channel
                }
                if (stillValid) {
                    sendL2ToPeer(publishSession, peerHandle, ackBytes)
                }
            }
        } catch (e: Exception) {
            MeshLog.w(TAG, "requestNetwork (responder) failed: ${e.javaClass.simpleName}")
            evictChannel(peerId, sendTeardown = false, reasonCode = AwareSignalingMessage.REASON_ZOMBIE)
        }
    }

    @SuppressLint("NewApi")
    private fun buildNetworkCallback(peerId: String, channel: NdpChannel): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val sock = synchronized(channelLock) {
                    if (channel.unregistered.get() || channelsByPeerId[peerId] !== channel) return
                    channel.network = network
                    channel.socket
                }
                if (sock == null || sock.isClosed) {
                    evictChannel(peerId, AwareSignalingMessage.REASON_ZOMBIE, sendTeardown = false)
                    return
                }
                val bindResult = runCatching { network.bindSocket(sock) }
                if (bindResult.isFailure) {
                    MeshLog.w(TAG, "network.bindSocket failed for ${peerId.take(8)}: ${bindResult.exceptionOrNull()?.javaClass?.simpleName}")
                    evictChannel(peerId, AwareSignalingMessage.REASON_ZOMBIE, sendTeardown = true)
                    return
                }
                synchronized(channelLock) {
                    if (channel.unregistered.get() || channelsByPeerId[peerId] !== channel) return
                    channel.state = AwareChainLinkPlanner.AwareLinkState.ACTIVE
                    channel.lastUdpAliveMs = clock()
                }
                startReceiveLoop(channel)
                publishEndpointMetrics()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (sdkInt < Build.VERSION_CODES.Q) return
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val addr: Inet6Address? = runCatching { info.peerIpv6Addr }.getOrNull()
                if (addr != null) {
                    var isNewEp = false
                    var effectivePort = 0
                    synchronized(channelLock) {
                        if (channel.unregistered.get() || channelsByPeerId[peerId] !== channel) return
                        channel.peerIpv6 = addr
                        val portFromInfo = runCatching { info.port }.getOrDefault(0)
                        if (portFromInfo > 0 && (channel.isInitiator || channel.peerUdpPort <= 0)) {
                            channel.peerUdpPort = portFromInfo
                        }
                        effectivePort = if (channel.peerUdpPort > 0) channel.peerUdpPort else channel.localPort
                        val host = addr.hostAddress ?: return
                        val ep = MeshEndpoint(host, effectivePort)
                        isNewEp = channel.activeEndpoint == null
                        channel.activeEndpoint?.let { old -> channelByEndpoint.remove(old) }
                        channel.activeEndpoint = ep
                        channelByEndpoint[ep] = channel
                        peersByMemberId[peerId]?.let { rec ->
                            rec.peerIpv6 = addr
                            rec.peerUdpPort = effectivePort
                        }
                    }
                    publishEndpointMetrics()
                    if (isNewEp && effectivePort > 0) {
                        onEndpointReady?.invoke()
                    }
                }
            }

            override fun onLost(network: Network) {
                val isCurrent = synchronized(channelLock) {
                    !channel.unregistered.get() && channelsByPeerId[peerId] === channel
                }
                if (isCurrent) {
                    evictChannel(peerId, sendTeardown = false, reasonCode = AwareSignalingMessage.REASON_ZOMBIE)
                }
            }

            override fun onUnavailable() {
                val isCurrent = synchronized(channelLock) {
                    !channel.unregistered.get() && channelsByPeerId[peerId] === channel
                }
                if (isCurrent) {
                    evictChannel(peerId, sendTeardown = false, reasonCode = AwareSignalingMessage.REASON_ZOMBIE)
                }
            }
        }
    }

    private fun startReceiveLoop(channel: NdpChannel) {
        if (channel.receiveJob?.isActive == true) return
        val sock = channel.socket ?: return
        channel.receiveJob = scope.launch {
            val buf = ByteArray(2048)
            while (isActive && !sock.isClosed && !channel.unregistered.get()) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    rxCount.incrementAndGet()
                    rxBytes.addAndGet(pkt.length.toLong())
                    val srcAddr = pkt.address ?: continue
                    val host = srcAddr.hostAddress ?: continue
                    val ep = MeshEndpoint(host, pkt.port)
                    var epUpdated = false
                    synchronized(channelLock) {
                        if (!channel.unregistered.get() && channelsByPeerId[channel.peerId] === channel) {
                            if (channel.peerIpv6 == null) {
                                channel.peerIpv6 = srcAddr
                            }
                            if (channel.peerUdpPort <= 0 && pkt.port > 0) {
                                channel.peerUdpPort = pkt.port
                            }
                            if (channel.activeEndpoint != ep) {
                                channel.activeEndpoint?.let { old -> channelByEndpoint.remove(old) }
                                channel.activeEndpoint = ep
                                channelByEndpoint[ep] = channel
                                epUpdated = true
                            }
                        }
                    }
                    if (epUpdated) publishEndpointMetrics()
                    val copy = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                    _incomingFrames.tryEmit(MeshFrame(ep, copy))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (sock.isClosed || !isActive || channel.unregistered.get()) break
                }
            }
        }
    }

    private fun evictChannel(peerId: String, reasonCode: Int, sendTeardown: Boolean = true) {
        val ch = synchronized(channelLock) {
            val removed = channelsByPeerId.remove(peerId) ?: return
            removed.unregistered.set(true)
            removed.activeEndpoint?.let { channelByEndpoint.remove(it) }
            removed.activeEndpoint = null
            removed
        }
        if (sendTeardown) {
            val session = activeSession
            val ctx = latestContext
            if (session != null && ctx != null) {
                val myTag = ConvoyHash.computeProposerTag(ConvoyHash.stableSessionUuid(session.sessionId), ctx.localMemberId)
                val discSession: DiscoverySession? = if (ch.isInitiator) subscribeSession else publishSession
                sendL2ToPeer(discSession, ch.peerHandle, AwareSignalingMessage.LinkTeardown(myTag, reasonCode).encode())
            }
        }
        ch.receiveJob?.cancel()
        ch.receiveJob = null
        runCatching { ch.socket?.close() }
        ch.socket = null
        val cb = ch.networkCallback
        if (cb != null) {
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        publishEndpointMetrics()
    }

    private fun publishEndpointMetrics() {
        val eps: List<MeshEndpoint>
        val activeCount: Int
        synchronized(channelLock) {
            eps = channelsByPeerId.values
                .filter { it.state == AwareChainLinkPlanner.AwareLinkState.ACTIVE }
                .mapNotNull { it.activeEndpoint }
            activeCount = channelsByPeerId.values.count { it.state == AwareChainLinkPlanner.AwareLinkState.ACTIVE }
        }
        _activePeerEndpoints.value = eps
        _activePeerCount.value = activeCount
        val curStatus = _status.value
        if (curStatus in setOf(WifiAwareStatus.DISCOVERING, WifiAwareStatus.ACTIVE_CHAIN)) {
            _status.value = if (activeCount > 0) WifiAwareStatus.ACTIVE_CHAIN else WifiAwareStatus.DISCOVERING
        }
    }

    private fun teardownAwareSessionsLocked() {
        val peerIds = synchronized(channelLock) { channelsByPeerId.keys.toList() }
        for (peerId in peerIds) {
            evictChannel(peerId, AwareSignalingMessage.REASON_REPLACED, sendTeardown = true)
        }
        synchronized(channelLock) {
            channelByEndpoint.clear()
        }
        pendingAcceptPeers.clear()
        peersByMemberId.clear()
        memberIdByTagHex.clear()
        runCatching { publishSession?.close() }
        publishSession = null
        runCatching { subscribeSession?.close() }
        subscribeSession = null
        runCatching { awareSession?.close() }
        awareSession = null
        attachInFlight = false
    }

    private fun buildPlannerInput(
        sessionId: String,
        ctx: AwareRuntimeContext,
        nowMs: Long
    ): AwareChainLinkPlanner.PlannerInput {
        val uuid = ConvoyHash.stableSessionUuid(sessionId)
        val localIdx = ctx.meshParticipants.indexOf(ctx.localMemberId).let { if (it >= 0) it else 0xFF }
        val clampedCap = _maxNdpSessions.value.let { if (it > 0) it else DEFAULT_MAX_NDPS }.coerceIn(1, 3)
        val reservedPending = pendingAcceptPeers.count { p -> !channelsByPeerId.containsKey(p) }
        val maxNdps = (clampedCap - reservedPending).coerceAtLeast(0)

        val activeMap: Map<String, AwareChainLinkPlanner.ActiveAwareLink>
        synchronized(channelLock) {
            activeMap = channelsByPeerId.mapValues { (id, ch) ->
                AwareChainLinkPlanner.ActiveAwareLink(
                    peerId = id,
                    state = ch.state,
                    establishedAtMs = ch.establishedAtMs,
                    lastUdpAliveMs = ch.lastUdpAliveMs,
                    isInitiator = ch.isInitiator
                )
            }
        }

        // In Lobby (expectUdpTraffic == false), synthesize adjacency from local active NDPs so connected peers aren't treated as isolated
        val adj: Map<String, Set<String>>
        val reachable: Set<String>
        if (!ctx.expectUdpTraffic && ctx.graphAdjacency.isEmpty()) {
            val activeIds = activeMap.values
                .filter { it.state == AwareChainLinkPlanner.AwareLinkState.ACTIVE }
                .map { it.peerId }
                .toSet()
            val synthAdj = HashMap<String, MutableSet<String>>()
            if (activeIds.isNotEmpty()) {
                synthAdj[ctx.localMemberId] = activeIds.toMutableSet()
                activeIds.forEach { p -> synthAdj[p] = mutableSetOf(ctx.localMemberId) }
            }
            adj = synthAdj
            reachable = activeIds + ctx.localMemberId
        } else {
            adj = ctx.graphAdjacency
            reachable = ctx.reachableMemberIds.ifEmpty { setOf(ctx.localMemberId) }
        }

        val compRoot = computeComponentRootTag(uuid, ctx.localMemberId, reachable)
        val cosLat = cos(Math.toRadians(ctx.localLatE6 / 1_000_000.0))

        val discMap = LinkedHashMap<String, AwareChainLinkPlanner.DiscoveredAwarePeer>(peersByMemberId.size)
        for ((id, rec) in peersByMemberId) {
            val distM = if (ctx.hasValidGps && rec.hasValidGps) {
                val dx = ((rec.lonE6 - ctx.localLonE6) / 1_000_000.0) * 111_320.0 * cosLat
                val dy = ((rec.latE6 - ctx.localLatE6) / 1_000_000.0) * 111_132.0
                hypot(dx, dy)
            } else {
                null
            }
            discMap[id] = AwareChainLinkPlanner.DiscoveredAwarePeer(
                memberId = id,
                rosterIdx = rec.rosterIdx,
                componentRootTag = rec.componentRootTag,
                activeNdpCount = rec.activeNdpCount,
                maxNdpSessions = rec.maxNdpSessions,
                distanceM = distM,
                discoveredAtMs = rec.discoveredAtMs,
                lastBeaconMs = rec.lastBeaconMs,
                rejectedUntilMs = rec.rejectedUntilMs
            )
        }

        return AwareChainLinkPlanner.PlannerInput(
            localMemberId = ctx.localMemberId,
            localRosterIdx = localIdx,
            localComponentRootTag = compRoot,
            maxNdpSessions = maxNdps,
            plannedChain = ctx.plannedChain,
            discoveredPeers = discMap,
            activeLinks = activeMap,
            graphAdjacency = adj,
            reachableMemberIds = reachable,
            nowMs = nowMs,
            directTimeoutMs = ctx.directTimeoutMs,
            isLiveOfflineVoice = ctx.isLiveOfflineVoice,
            expectUdpTraffic = ctx.expectUdpTraffic
        )
    }

    private fun buildLocalBeaconBytes(): ByteArray {
        val sess = activeSession
        val ctx = latestContext
        if (sess == null || ctx == null) {
            return ByteArray(AwareDiscoveryBeacon.ENCODED_SIZE_BYTES)
        }
        val uuid = ConvoyHash.stableSessionUuid(sess.sessionId)
        val myIdx = ctx.meshParticipants.indexOf(ctx.localMemberId).let { if (it >= 0) it else 0xFF }
        val myTag = ConvoyHash.computeProposerTag(uuid, ctx.localMemberId)

        var connectedMask = 0
        val activeCountSnapshot: Int
        val activeDirectIds = LinkedHashSet<String>()
        synchronized(channelLock) {
            activeCountSnapshot = channelsByPeerId.size
            for ((peerId, ch) in channelsByPeerId) {
                if (ch.state == AwareChainLinkPlanner.AwareLinkState.ACTIVE) {
                    activeDirectIds.add(peerId)
                    val pIdx = ctx.meshParticipants.indexOf(peerId)
                    if (pIdx in 0..15) connectedMask = connectedMask or (1 shl pIdx)
                }
            }
        }
        val effectiveReachable = if (ctx.expectUdpTraffic) {
            ctx.reachableMemberIds
        } else {
            ctx.reachableMemberIds + activeDirectIds
        }
        val compRoot = computeComponentRootTag(uuid, ctx.localMemberId, effectiveReachable)
        var flags = 0
        if (ctx.hasValidGps) flags = flags or AwareDiscoveryBeacon.FLAG_HAS_GPS
        if (ctx.isLiveOfflineVoice) flags = flags or AwareDiscoveryBeacon.FLAG_OFFLINE_VOICE
        if (ctx.p2pFallbackReady) flags = flags or AwareDiscoveryBeacon.FLAG_P2P_FALLBACK_READY

        var reachableMask = 0
        for (memberId in effectiveReachable) {
            val rIdx = ctx.meshParticipants.indexOf(memberId)
            if (rIdx in 0..15) reachableMask = reachableMask or (1 shl rIdx)
        }
        if (myIdx in 0..15) reachableMask = reachableMask or (1 shl myIdx)

        return AwareDiscoveryBeacon(
            rosterIdx = myIdx,
            flags = flags,
            activeNdpCount = activeCountSnapshot.coerceIn(0, 15),
            maxNdpSessions = _maxNdpSessions.value.coerceIn(1, 15),
            memberTag = myTag,
            componentRootTag = compRoot,
            latE6 = if (ctx.hasValidGps) ctx.localLatE6 else 0,
            lonE6 = if (ctx.hasValidGps) ctx.localLonE6 else 0,
            connectedMask = connectedMask,
            reachableMask = reachableMask
        ).encode()
    }

    private fun computeComponentRootTag(
        sessionUuid: UUID,
        localMemberId: String,
        reachableIds: Set<String>
    ): ByteArray {
        var best = ConvoyHash.computeProposerTag(sessionUuid, localMemberId)
        for (id in reachableIds) {
            val tag = ConvoyHash.computeProposerTag(sessionUuid, id)
            if (ConvoyHash.compareUnsignedBytes(tag, best) < 0) {
                best = tag
            }
        }
        return best
    }

    private fun resolveMemberId(
        sessionId: String,
        participants: List<String>,
        tag8: ByteArray
    ): String? {
        val uuid = ConvoyHash.stableSessionUuid(sessionId)
        for (uid in participants) {
            if (ConvoyHash.computeProposerTag(uuid, uid).contentEquals(tag8)) return uid
        }
        return null
    }

    private fun buildRejectBytes(sessionId: String, localMemberId: String, reason: Int): ByteArray {
        val tag = ConvoyHash.computeProposerTag(ConvoyHash.stableSessionUuid(sessionId), localMemberId)
        return AwareSignalingMessage.ConnectReject(tag, reason).encode()
    }

    private fun sendL2ToPeer(session: DiscoverySession?, peerHandle: PeerHandle, bytes: ByteArray): Boolean {
        session ?: return false
        return try {
            session.sendMessage(peerHandle, messageIdSeq.incrementAndGet(), bytes)
            true
        } catch (e: Exception) {
            MeshLog.w(TAG, "L2 sendMessage failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun hasHardwareFeature(): Boolean =
        runCatching { appContext.packageManager?.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) == true }
            .getOrDefault(false)

    private fun isWifiRadioEnabled(): Boolean = runCatching {
        wifiManager?.isWifiEnabled != false
    }.getOrDefault(true)

    @SuppressLint("InlinedApi")
    private fun hasRequiredPermissions(): Boolean {
        val changeWifi = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.CHANGE_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val nearbyOrLoc = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
        return changeWifi && nearbyOrLoc
    }

    private fun registerReceiverLocked() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(awareReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(awareReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private fun unregisterReceiverLocked() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(awareReceiver) }
        receiverRegistered = false
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
