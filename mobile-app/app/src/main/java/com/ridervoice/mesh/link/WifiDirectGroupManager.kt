package com.ridervoice.mesh.link

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ridervoice.mesh.MeshLog
import com.ridervoice.mesh.OfflineMeshGate
import com.ridervoice.mesh.session.OfflineSession
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic Wi-Fi Direct group credentials shared automatically across all members
 * of a ride via their synchronized [OfflineSession] (room pairing/backup key).
 *
 * Because Host and Guests derive the exact same `DIRECT-RV-...` SSID and WPA2 passphrase,
 * Android 10+ (API 29+) `WifiP2pConfig.Builder` creates and joins the group silently with
 * zero OS consent popups and zero manual hotspot configuration.
 */
data class WifiDirectCredentials(
    val networkName: String,
    val passphrase: String
)

object WifiDirectCredentialsFactory {
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun derive(session: OfflineSession): WifiDirectCredentials? {
        if (session.isClosed) return null
        val authKeyBytes = try {
            session.authKey()
        } catch (_: IllegalStateException) {
            return null
        }
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(authKeyBytes, "HmacSHA256"))
        }
        val digest = mac.doFinal("RV-P2P-V1|${session.sessionId}".toByteArray(Charsets.UTF_8))
        val suffix = encodeAlnum(digest, offset = 0, length = 8)
        val passphrase = encodeAlnum(digest, offset = 8, length = 16)
        return WifiDirectCredentials(
            networkName = "DIRECT-RV-$suffix",
            passphrase = passphrase
        )
    }

    private fun encodeAlnum(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val b = bytes[(offset + i) % bytes.size].toInt() and 0xFF
            sb.append(BASE62[b % BASE62.length])
        }
        return sb.toString()
    }
}

enum class WifiDirectStatus {
    IDLE,
    UNSUPPORTED,
    DISABLED_WIFI_OFF,
    MISSING_PERMISSION,
    CREATING_GROUP,
    CONNECTING,
    CONNECTED,
    FAILED
}

interface WifiDirectGroupController {
    val status: StateFlow<WifiDirectStatus>
    val isGroupConnected: StateFlow<Boolean>
    val p2pEvents: Flow<WifiChangeEvent>

    fun ensureActive(session: OfflineSession, isHost: Boolean)
    fun stop()
}

object NoOpWifiDirectGroupController : WifiDirectGroupController {
    override val status: StateFlow<WifiDirectStatus> = MutableStateFlow(WifiDirectStatus.IDLE)
    override val isGroupConnected: StateFlow<Boolean> = MutableStateFlow(false)
    override val p2pEvents: Flow<WifiChangeEvent> = emptyFlow()
    override fun ensureActive(session: OfflineSession, isHost: Boolean) {}
    override fun stop() {}
}

/**
 * Autonomous Wi-Fi Direct (`WifiP2pManager`) lifecycle manager for Android 10+ (API 29+).
 * Forms a shared `192.168.49.x` subnet in Lobby / Live Ride while mobile data stays online,
 * allowing instant failover to UDP mesh voice when cellular/router internet drops.
 */
class AndroidWifiDirectGroupController(
    private val context: Context,
    private val gateOpen: () -> Boolean = { OfflineMeshGate.isBuildGateOpen() },
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : WifiDirectGroupController {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val p2pManager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _status = MutableStateFlow(WifiDirectStatus.IDLE)
    override val status: StateFlow<WifiDirectStatus> = _status.asStateFlow()

    private val _isGroupConnected = MutableStateFlow(false)
    override val isGroupConnected: StateFlow<Boolean> = _isGroupConnected.asStateFlow()

    private val _p2pEvents = MutableSharedFlow<WifiChangeEvent>(extraBufferCapacity = 16)
    override val p2pEvents: Flow<WifiChangeEvent> = _p2pEvents.asSharedFlow()

    private val stateLock = Any()
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var activeSessionId: String? = null
    private var activeCreds: WifiDirectCredentials? = null
    private var activeIsHost: Boolean = false
    private var retryJob: Job? = null

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val p2pState = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    if (p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        retriggerIfWaiting()
                    } else {
                        _isGroupConnected.value = false
                        if (activeCreds != null) {
                            _status.value = WifiDirectStatus.DISABLED_WIFI_OFF
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = extractP2pInfo(intent)
                    handleConnectionInfoUpdate(info)
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val wifiState = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN
                    )
                    if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                        retriggerIfWaiting()
                    } else if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                        _isGroupConnected.value = false
                        if (activeCreds != null) {
                            _status.value = WifiDirectStatus.DISABLED_WIFI_OFF
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractP2pInfo(intent: Intent): WifiP2pInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
        } else {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
        }
    }

    override fun ensureActive(session: OfflineSession, isHost: Boolean) {
        if (!gateOpen()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || p2pManager == null) {
            _status.value = WifiDirectStatus.UNSUPPORTED
            return
        }
        val creds = WifiDirectCredentialsFactory.derive(session) ?: return
        synchronized(stateLock) {
            registerReceiverLocked()
            val unchanged = activeSessionId == session.sessionId &&
                activeIsHost == isHost &&
                activeCreds == creds &&
                (_isGroupConnected.value || retryJob?.isActive == true)
            if (unchanged) return

            activeSessionId = session.sessionId
            activeCreds = creds
            activeIsHost = isHost
            if (channel == null) {
                channel = p2pManager.initialize(appContext, Looper.getMainLooper(), null)
            }
            launchSetupLoopLocked(creds, isHost)
        }
    }

    override fun stop() {
        val ch: WifiP2pManager.Channel?
        synchronized(stateLock) {
            retryJob?.cancel()
            retryJob = null
            activeSessionId = null
            activeCreds = null
            ch = channel
            unregisterReceiverLocked()
        }
        _isGroupConnected.value = false
        _status.value = WifiDirectStatus.IDLE
        val mgr = p2pManager
        if (mgr != null && ch != null) {
            runCatching { mgr.cancelConnect(ch, null) }
            runCatching { mgr.removeGroup(ch, null) }
        }
    }

    private fun retriggerIfWaiting() {
        synchronized(stateLock) {
            val creds = activeCreds ?: return
            if (_isGroupConnected.value || retryJob?.isActive == true) return
            launchSetupLoopLocked(creds, activeIsHost)
        }
    }

    private fun launchSetupLoopLocked(creds: WifiDirectCredentials, isHost: Boolean) {
        retryJob?.cancel()
        retryJob = scope.launch {
            if (!isWifiRadioEnabled()) {
                _status.value = WifiDirectStatus.DISABLED_WIFI_OFF
                return@launch
            }
            if (!hasRequiredPermissions()) {
                _status.value = WifiDirectStatus.MISSING_PERMISSION
                return@launch
            }
            val ch = channel ?: return@launch
            val mgr = p2pManager ?: return@launch

            if (!isHost) {
                // Allow the Host's createGroup call to establish the P2P SSID first.
                delay(1_500L)
            }

            var backoffMs = if (isHost) 2_500L else 3_000L
            var attempts = 0
            while (isActive && !_isGroupConnected.value && activeCreds == creds) {
                if (!isWifiRadioEnabled()) {
                    _status.value = WifiDirectStatus.DISABLED_WIFI_OFF
                    return@launch
                }
                if (!hasRequiredPermissions()) {
                    _status.value = WifiDirectStatus.MISSING_PERMISSION
                    return@launch
                }

                attempts++
                if (isHost) {
                    _status.value = WifiDirectStatus.CREATING_GROUP
                    ensureHostGroup(mgr, ch, creds)
                } else {
                    _status.value = WifiDirectStatus.CONNECTING
                    connectClientToGroup(mgr, ch, creds)
                }

                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(12_000L)
                if (attempts >= 8 && !_isGroupConnected.value) {
                    _status.value = WifiDirectStatus.FAILED
                }
            }
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun ensureHostGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        creds: WifiDirectCredentials
    ) {
        mgr.requestGroupInfo(ch) { group ->
            if (group != null && group.networkName == creds.networkName) {
                markConnected()
                return@requestGroupInfo
            }
            val startGroup = {
                val cfg = WifiP2pConfig.Builder()
                    .setNetworkName(creds.networkName)
                    .setPassphrase(creds.passphrase)
                    .build()
                mgr.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        MeshLog.d("WifiDirect", "Host P2P group initiated (${creds.networkName})")
                        mgr.requestConnectionInfo(ch) { info ->
                            if (info?.groupFormed == true) markConnected()
                        }
                    }

                    override fun onFailure(reason: Int) {
                        MeshLog.w("WifiDirect", "Host createGroup failed reason=$reason")
                    }
                })
            }
            if (group != null) {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        startGroup()
                    }

                    override fun onFailure(reason: Int) {
                        startGroup()
                    }
                })
            } else {
                startGroup()
            }
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun connectClientToGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        creds: WifiDirectCredentials
    ) {
        val cfg = WifiP2pConfig.Builder()
            .setNetworkName(creds.networkName)
            .setPassphrase(creds.passphrase)
            .build()
        mgr.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                MeshLog.d("WifiDirect", "Client silent P2P connect initiated (${creds.networkName})")
            }

            override fun onFailure(reason: Int) {
                MeshLog.w("WifiDirect", "Client P2P connect failed reason=$reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionInfoUpdate(info: WifiP2pInfo?) {
        val mgr = p2pManager ?: return
        val ch = channel ?: return
        val targetCreds = activeCreds ?: return

        if (info?.groupFormed == true) {
            mgr.requestGroupInfo(ch) { group ->
                if (group == null || group.networkName == targetCreds.networkName) {
                    markConnected()
                }
            }
        } else {
            val wasConnected = _isGroupConnected.getAndSetFalse()
            if (wasConnected) {
                _p2pEvents.tryEmit(WifiChangeEvent.Changed)
                retriggerIfWaiting()
            }
        }
    }

    private fun markConnected() {
        val changed = !_isGroupConnected.value
        _isGroupConnected.value = true
        _status.value = WifiDirectStatus.CONNECTED
        if (changed) {
            _p2pEvents.tryEmit(WifiChangeEvent.Available)
        } else {
            _p2pEvents.tryEmit(WifiChangeEvent.Changed)
        }
    }

    private fun isWifiRadioEnabled(): Boolean = wifiManager?.isWifiEnabled != false

    private fun hasRequiredPermissions(): Boolean {
        val hasChangeWifi = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CHANGE_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasChangeWifi) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun registerReceiverLocked() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            appContext,
            p2pReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterReceiverLocked() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(p2pReceiver) }
        receiverRegistered = false
    }

    private fun MutableStateFlow<Boolean>.getAndSetFalse(): Boolean {
        val prev = value
        value = false
        return prev
    }
}
