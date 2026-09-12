package com.ridervoice.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class NetworkHealth {
    CONNECTED, DEGRADED, RECONNECTING, DISCONNECTED
}

@Singleton
class NetworkResilienceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkHealth = MutableStateFlow(NetworkHealth.CONNECTED)
    val networkHealth: StateFlow<NetworkHealth> = _networkHealth

    private var transitionJob: Job? = null
    private val HYSTERESIS_MS = 3000L

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {

                transitionJob?.cancel()
                transitionJob = CoroutineScope(Dispatchers.IO).launch {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (isDegraded(caps)) {
                        _networkHealth.value = NetworkHealth.DEGRADED
                    } else {
                        _networkHealth.value = NetworkHealth.RECONNECTING
                        delay(HYSTERESIS_MS)
                        _networkHealth.value = NetworkHealth.CONNECTED
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (isDegraded(networkCapabilities)) {
                    _networkHealth.value = NetworkHealth.DEGRADED
                }
            }

            override fun onLost(network: Network) {

                transitionJob?.cancel()
                _networkHealth.value = NetworkHealth.RECONNECTING
            }
        })
    }

    private fun isDegraded(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return true

        val hasGoodTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                               caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isCongested = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)

        return !hasGoodTransport || isCongested
    }
}
