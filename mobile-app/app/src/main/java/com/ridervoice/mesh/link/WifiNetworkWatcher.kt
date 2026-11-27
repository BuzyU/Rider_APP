package com.ridervoice.mesh.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

sealed class WifiChangeEvent {
    data object Available : WifiChangeEvent()
    data object Lost : WifiChangeEvent()
    data object Changed : WifiChangeEvent()
}

interface WifiNetworkWatcher {
    val wifiEvents: Flow<WifiChangeEvent>
    fun start() {}
    fun stop() {}
}

object NoOpWifiNetworkWatcher : WifiNetworkWatcher {
    override val wifiEvents: Flow<WifiChangeEvent> = emptyFlow()
    override fun start() {}
    override fun stop() {}
}

class AndroidWifiNetworkWatcher(
    private val context: Context,
    private val externalEvents: Flow<WifiChangeEvent>? = null
) : WifiNetworkWatcher {
    private val _wifiEvents = MutableSharedFlow<WifiChangeEvent>(extraBufferCapacity = 16)
    override val wifiEvents: Flow<WifiChangeEvent> = _wifiEvents.asSharedFlow()

    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var externalJob: Job? = null

    @Synchronized
    override fun start() {
        if (externalJob == null && externalEvents != null) {
            externalJob = scope.launch {
                externalEvents.collect { evt ->
                    _wifiEvents.tryEmit(evt)
                }
            }
        }
        if (callback != null || cm == null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _wifiEvents.tryEmit(WifiChangeEvent.Available)
            }

            override fun onLost(network: Network) {
                _wifiEvents.tryEmit(WifiChangeEvent.Lost)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                _wifiEvents.tryEmit(WifiChangeEvent.Changed)
            }
        }
        callback = cb
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm?.registerNetworkCallback(request, cb)
        }
    }

    @Synchronized
    override fun stop() {
        externalJob?.cancel()
        externalJob = null
        callback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        callback = null
    }
}

