package com.example.intercommvp.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.example.intercommvp.model.IntercomDevice
import com.example.intercommvp.model.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object PeerDiscovery {
    const val DISCOVERY_PORT = 45455
    const val AUDIO_PORT = 45454
    const val MAX_REMOTE_PEERS = 5
    private const val MAGIC = "ICD2"
    private const val INTERVAL_MS = 1_500L
    private const val TIMEOUT_MS = 5_000L

    private val scope = CoroutineScope(Dispatchers.IO)
    private val peers = ConcurrentHashMap<String, SeenPeer>()
    private val _devices = MutableStateFlow<List<IntercomDevice>>(emptyList())
    val devices: StateFlow<List<IntercomDevice>> = _devices.asStateFlow()

    @Volatile
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var localId: String = ""
    private var localName: String = ""

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        localId = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: Build.SERIAL.ifBlank { Build.MODEL }
        localName = Build.MODEL.replace('_', ' ')
        acquireLocks(app)
        job = scope.launch {
            val discoverySocket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
            } catch (_: SocketException) {
                releaseLocks()
                return@launch
            }
            socket = discoverySocket
            try {
                while (isActive) {
                    sendHello(discoverySocket)
                    receiveAvailable(discoverySocket)
                    evictExpired()
                    delay(INTERVAL_MS)
                }
            } finally {
                discoverySocket.close()
                socket = null
                peers.clear()
                publish()
                releaseLocks()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
    }

    fun localDeviceId(): String = localId

    fun snapshotAddresses(): List<InetAddress> =
        peers.values
            .sortedBy { it.name.lowercase() }
            .take(MAX_REMOTE_PEERS)
            .map { it.address }
            .distinctBy { it.hostAddress }

    fun broadcastAddresses(): List<InetAddress> =
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.flatMap { network ->
                network.interfaceAddresses.asSequence()
                    .filter { it.address is Inet4Address && !network.isLoopback && !network.isVirtual }
                    .mapNotNull { it.broadcast }
            }
            ?.distinctBy { it.hostAddress }
            ?.toList()
            .orEmpty()
            .ifEmpty { listOf(InetAddress.getByName("255.255.255.255")) }

    private fun receiveAvailable(discoverySocket: DatagramSocket) {
        discoverySocket.soTimeout = 250
        val data = ByteArray(1024)
        while (true) {
            try {
                val packet = DatagramPacket(data, data.size)
                discoverySocket.receive(packet)
                parse(packet)?.let { peer ->
                    peers[peer.id] = peer
                    publish()
                    sendHello(discoverySocket, packet.address)
                }
            } catch (_: java.net.SocketTimeoutException) {
                return
            } catch (_: SocketException) {
                return
            }
        }
    }

    private fun sendHello(
        discoverySocket: DatagramSocket,
        address: InetAddress? = null
    ) {
        val message = "$MAGIC|$localId|$localName"
            .toByteArray(StandardCharsets.UTF_8)
        val targets = address?.let { listOf(it) } ?: broadcastAddresses()
        targets.forEach {
            discoverySocket.send(DatagramPacket(message, message.size, it, DISCOVERY_PORT))
        }
    }

    private fun parse(packet: DatagramPacket): SeenPeer? {
        val parts = String(
            packet.data,
            packet.offset,
            packet.length,
            StandardCharsets.UTF_8
        ).split('|', limit = 3)
        if (parts.size != 3 || parts[0] != MAGIC || parts[1].isBlank() || parts[1] == localId) {
            return null
        }
        return SeenPeer(parts[1], parts[2].ifBlank { "Intercom device" }, packet.address)
    }

    private fun evictExpired() {
        val cutoff = System.currentTimeMillis() - TIMEOUT_MS
        peers.entries.removeIf { it.value.lastSeen < cutoff }
        publish()
    }

    private fun publish() {
        _devices.value = peers.values
            .sortedBy { it.name.lowercase() }
            .take(MAX_REMOTE_PEERS)
            .map {
                IntercomDevice(
                    id = it.id,
                    name = it.name,
                    transport = TransportKind.WIFI,
                    online = true,
                    address = it.address.hostAddress
                )
            }
    }

    private fun acquireLocks(context: Context) {
        val wifi = context.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "intercommvp:network"
        ).apply { acquire() }
        multicastLock = wifi.createMulticastLock("intercommvp:multicast")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
        wifiLock = null
    }

    private data class SeenPeer(
        val id: String,
        val name: String,
        val address: InetAddress,
        val lastSeen: Long = System.currentTimeMillis()
    )
}
