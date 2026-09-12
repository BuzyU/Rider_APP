package com.ridervoice.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NetworkEngineState {
    LIVEKIT_ONLINE,
    WIFI_DIRECT_OFFLINE,
    TRANSITIONING
}

class NetworkHandoffManager(
    private val context: Context,
    private val liveKitEngine: VoxEngine,
    private val localMeshEngine: LocalMeshVoiceEngine,
    private val wifiDirectManager: WifiDirectManager
) {
    private val TAG = "NetworkHandoffManager"

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _engineState = MutableStateFlow(NetworkEngineState.LIVEKIT_ONLINE)
    val engineState = _engineState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Internet available. Switching to LiveKit...")
                switchToOnlineEngine()
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Internet LOST! Triggering fallback to Offline Mesh...")
                switchToOfflineEngine()
            }
        })
    }

    private fun switchToOnlineEngine() {
        if (_engineState.value == NetworkEngineState.LIVEKIT_ONLINE) return

        scope.launch {
            _engineState.value = NetworkEngineState.TRANSITIONING
            Log.d(TAG, "Tearing down Wi-Fi Direct Mesh...")

            localMeshEngine.stopLocalAudio()
            wifiDirectManager.cleanup()

            Log.d(TAG, "Reconnecting to LiveKit SFU...")

            _engineState.value = NetworkEngineState.LIVEKIT_ONLINE
        }
    }

    private fun switchToOfflineEngine() {
        if (_engineState.value == NetworkEngineState.WIFI_DIRECT_OFFLINE) return

        scope.launch {
            _engineState.value = NetworkEngineState.TRANSITIONING
            Log.d(TAG, "Disconnecting from LiveKit...")

            liveKitEngine.setVoxEnabled(false)

            Log.d(TAG, "Initializing Wi-Fi Direct Mesh...")
            wifiDirectManager.initialize()
            localMeshEngine.initialize()
            localMeshEngine.startLocalAudio()

            val amIOwner = wifiDirectManager.electGroupOwner(emptyList(), "my_id")
            if (amIOwner) {
                wifiDirectManager.startSignalingServer()
            }

            _engineState.value = NetworkEngineState.WIFI_DIRECT_OFFLINE
        }
    }
}
