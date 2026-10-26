package com.example.intercommvp.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.intercommvp.R
import com.example.intercommvp.network.PeerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

enum class AudioRouteMode(val label: String) {
    SPEAKER("Speaker"),
    BLUETOOTH("Bluetooth earbuds")
}

class IntercomService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var receiveJob: Job? = null
    private var recordJob: Job? = null
    private var micEnabled = false
    private var routeMode = AudioRouteMode.SPEAKER
    private lateinit var audioManager: AudioManager

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        applyAudioRoute()
        createNotificationChannel()
        PeerDiscovery.start(this)
        startReceiving()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Stopping service because RECORD_AUDIO is not granted")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        if (intent?.action == ACTION_SET_MIC) {
            setMicEnabled(intent.getBooleanExtra(EXTRA_MIC_ENABLED, false))
        } else if (intent?.action == ACTION_SET_ROUTE) {
            setRoute(
                runCatching {
                    AudioRouteMode.valueOf(intent.getStringExtra(EXTRA_ROUTE) ?: AudioRouteMode.SPEAKER.name)
                }.getOrDefault(AudioRouteMode.SPEAKER)
            )
        }
        return START_STICKY
    }

    private fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        _micEnabled.value = enabled
        if (enabled) startRecording() else stopRecording()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification())
    }

    private fun setRoute(route: AudioRouteMode) {
        routeMode = route
        _routeMode.value = route
        applyAudioRoute()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification())
    }

    private fun startRecording() {
        if (recordJob?.isActive == true) return
        recordJob = scope.launch {
            val sampleRate = 16_000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return@launch
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                (minBuffer * 2).coerceAtLeast(4096)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                recorder.release()
                return@launch
            }
            val effects = listOfNotNull(
                AcousticEchoCanceler.create(recorder.audioSessionId),
                NoiseSuppressor.create(recorder.audioSessionId),
                AutomaticGainControl.create(recorder.audioSessionId)
            )
            effects.forEach { it.enabled = true }
            val socket = DatagramSocket()
            try {
                recorder.startRecording()
                val buffer = ByteArray((minBuffer * 2).coerceAtLeast(4096))
                var silenceDeadline = 0L
                while (isActive) {
                    val sampleCount = recorder.read(buffer, 0, buffer.size)
                    if (sampleCount <= 0) continue
                    val now = System.currentTimeMillis()
                    if (audioLevel(buffer, sampleCount) >= VAD_THRESHOLD) {
                        silenceDeadline = now + VAD_HANGOVER_MS
                    } else if (now > silenceDeadline) {
                        continue
                    }
                    val idBytes = PeerDiscovery.localDeviceId()
                        .toByteArray(StandardCharsets.UTF_8)
                    val packet = ByteBuffer
                        .allocate(HEADER_SIZE + idBytes.size + sampleCount)
                        .order(ByteOrder.BIG_ENDIAN)
                        .put(AUDIO_MAGIC)
                        .put(idBytes.size.toByte())
                        .putInt(sequence.incrementAndGet())
                        .put(idBytes)
                        .put(buffer, 0, sampleCount)
                    val data = packet.array()
                    val length = packet.position()
                    val targets = PeerDiscovery.snapshotAddresses()
                        .ifEmpty { PeerDiscovery.broadcastAddresses() }
                    targets.forEach { address ->
                        socket.send(
                            DatagramPacket(data, length, address, PeerDiscovery.AUDIO_PORT)
                        )
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Audio capture/send failed", error)
            } finally {
                runCatching { recorder.stop() }
                effects.forEach { it.release() }
                recorder.release()
                socket.close()
            }
        }
    }

    private fun startReceiving() {
        if (receiveJob?.isActive == true) return
        receiveJob = scope.launch {
            val socket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(PeerDiscovery.AUDIO_PORT))
                    soTimeout = 1_000
                }
            } catch (_: SocketException) {
                return@launch
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(16_000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(16_000)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize")
                track.release()
                socket.close()
                return@launch
            }
            try {
                applyAudioRoute()
                track.play()
                track.setVolume(1.0f)
                while (isActive) {
                    val data = ByteArray(MAX_PACKET_SIZE)
                    try {
                        val packet = DatagramPacket(data, data.size)
                        socket.receive(packet)
                        val pcmOffset = parseAudioOffset(data, packet.length)
                        if (pcmOffset > 0) {
                            track.write(data, pcmOffset, packet.length - pcmOffset)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Audio receive/playback failed", error)
            } finally {
                runCatching { track.stop() }
                track.release()
                socket.close()
            }
        }
    }

    private fun parseAudioOffset(data: ByteArray, length: Int): Int {
        if (length < HEADER_SIZE || !data.copyOfRange(0, AUDIO_MAGIC.size).contentEquals(AUDIO_MAGIC)) {
            return -1
        }
        val idLength = data[AUDIO_MAGIC.size].toInt() and 0xff
        val offset = HEADER_SIZE + idLength
        if (offset >= length) return -1
        val sender = String(data, HEADER_SIZE, idLength, StandardCharsets.UTF_8)
        return if (sender == PeerDiscovery.localDeviceId()) -1 else offset
    }

    private fun stopRecording() {
        recordJob?.cancel()
        recordJob = null
    }

    private fun applyAudioRoute() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = if (routeMode == AudioRouteMode.BLUETOOTH) {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            } else {
                null
            }
            if (device != null) {
                audioManager.setCommunicationDevice(device)
                return
            }
            audioManager.clearCommunicationDevice()
        }
        if (routeMode == AudioRouteMode.BLUETOOTH) {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        } else {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun audioLevel(data: ByteArray, length: Int): Int {
        var sum = 0L
        var samples = 0
        var index = 0
        while (index + 1 < length) {
            val sample = (data[index].toInt() and 0xff) or
                (data[index + 1].toInt() shl 8)
            sum += kotlin.math.abs(sample.toShort().toInt())
            samples++
            index += 2
        }
        return if (samples == 0) 0 else (sum / samples).toInt()
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                "${routeMode.label} · " +
                    if (micEnabled) "microphone on" else "microphone off"
            )
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        stopRecording()
        receiveJob?.cancel()
        PeerDiscovery.stop()
        scope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "IntercomService"
        private const val CHANNEL_ID = "intercom_audio"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_SET_MIC = "com.example.intercommvp.SET_MIC"
        private const val ACTION_SET_ROUTE = "com.example.intercommvp.SET_ROUTE"
        private const val EXTRA_MIC_ENABLED = "mic_enabled"
        private const val EXTRA_ROUTE = "route"
        private const val HEADER_SIZE = 9
        private const val MAX_PACKET_SIZE = 8192
        private const val VAD_THRESHOLD = 700
        private const val VAD_HANGOVER_MS = 350L
        private val AUDIO_MAGIC = byteArrayOf('I'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), '2'.code.toByte())
        private val sequence = AtomicInteger()
        private val _micEnabled = MutableStateFlow(false)
        val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()
        private val _routeMode = MutableStateFlow(AudioRouteMode.SPEAKER)
        val routeMode: StateFlow<AudioRouteMode> = _routeMode.asStateFlow()

        fun roomIntent(context: Context): Intent =
            Intent(context, IntercomService::class.java)

        fun setMicIntent(context: Context, enabled: Boolean): Intent =
            Intent(context, IntercomService::class.java)
                .setAction(ACTION_SET_MIC)
                .putExtra(EXTRA_MIC_ENABLED, enabled)

        fun setRouteIntent(context: Context, route: AudioRouteMode): Intent =
            Intent(context, IntercomService::class.java)
                .setAction(ACTION_SET_ROUTE)
                .putExtra(EXTRA_ROUTE, route.name)
    }
}
