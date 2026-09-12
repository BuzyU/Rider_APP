package com.ridervoice.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RiderLocation(val id: String, val lat: Double, val lng: Double)

class WifiDirectManager(
    private val context: Context,
    private val localMeshVoiceEngine: LocalMeshVoiceEngine
) {
    private val TAG = "WifiDirectManager"

    private val manager: WifiP2pManager? by lazy(LazyThreadSafetyMode.NONE) {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _isGroupOwner = MutableStateFlow(false)
    val isGroupOwner = _isGroupOwner.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    private val connectedClients = mutableListOf<Socket>()

    fun initialize() {
        channel = manager?.initialize(context, Looper.getMainLooper(), null)
        Log.d(TAG, "WifiDirectManager initialized")
    }

    fun electGroupOwner(riders: List<RiderLocation>, myId: String): Boolean {
        if (riders.isEmpty()) return true

        var x = 0.0
        var y = 0.0
        var z = 0.0

        for (rider in riders) {
            val lat = Math.toRadians(rider.lat)
            val lng = Math.toRadians(rider.lng)
            x += cos(lat) * cos(lng)
            y += cos(lat) * sin(lng)
            z += sin(lat)
        }

        val total = riders.size
        x /= total
        y /= total
        z /= total

        val centralLng = atan2(y, x)
        val hyp = sqrt(x * x + y * y)
        val centralLat = atan2(z, hyp)

        val centerLatDeg = Math.toDegrees(centralLat)
        val centerLngDeg = Math.toDegrees(centralLng)

        var closestId = myId
        var minDistance = Double.MAX_VALUE

        for (rider in riders) {
            val dist = calculateDistance(centerLatDeg, centerLngDeg, rider.lat, rider.lng)
            if (dist < minDistance) {
                minDistance = dist
                closestId = rider.id
            }
        }

        val amIOwner = (closestId == myId)
        _isGroupOwner.value = amIOwner
        return amIOwner
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3
        val phi1 = lat1 * Math.PI / 180
        val phi2 = lat2 * Math.PI / 180
        val deltaPhi = (lat2 - lat1) * Math.PI / 180
        val deltaLambda = (lon2 - lon1) * Math.PI / 180

        val a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
                Math.cos(phi1) * Math.cos(phi2) *
                Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return R * c
    }

    fun startSignalingServer() {
        if (!_isGroupOwner.value) return

        scope.launch {
            try {
                serverSocket = ServerSocket(8888)
                Log.d(TAG, "Signaling server started on port 8888")

                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    connectedClients.add(client)
                    Log.d(TAG, "Client connected: ${client.inetAddress.hostAddress}")

                    launch { handleClientSignaling(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Signaling server error", e)
            }
        }
    }

    private suspend fun handleClientSignaling(client: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = ObjectInputStream(client.getInputStream())
                val output = ObjectOutputStream(client.getOutputStream())

                while (isActive && !client.isClosed) {
                    val payload = input.readUTF()
                    Log.d(TAG, "Received signaling payload: $payload")

                }
            } catch (e: Exception) {
                Log.e(TAG, "Client disconnected", e)
            } finally {
                client.close()
                connectedClients.remove(client)
            }
        }
    }

    fun cleanup() {
        scope.cancel()
        serverSocket?.close()
        connectedClients.forEach { it.close() }
        connectedClients.clear()

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        }
    }
}
