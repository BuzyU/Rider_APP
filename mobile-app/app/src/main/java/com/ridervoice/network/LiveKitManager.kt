package com.ridervoice.network

import android.content.Context
import android.util.Log
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.AudioDeviceRouter
import com.ridervoice.audio.VoxEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import com.google.gson.Gson
import com.ridervoice.models.RiderLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveKitManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioDeviceRouter: AudioDeviceRouter,
    private val voxEngine: VoxEngine,
) {
    private val TAG = "LiveKitManager"

    private var room: Room? = null
    private var localAudioTrack: LocalAudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _participants = MutableStateFlow<List<String>>(emptyList())
    val participants: StateFlow<List<String>> = _participants

    private val _remoteLocations = MutableStateFlow<Map<String, RiderLocation>>(emptyMap())
    val remoteLocations: StateFlow<Map<String, RiderLocation>> = _remoteLocations

    private val _activeSpeaker = MutableStateFlow<String?>(null)
    val activeSpeaker: StateFlow<String?> = _activeSpeaker

    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled

    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 10
    private var lastUrl = ""
    private var lastToken = ""

    private val gson = Gson()

    fun connect(url: String, token: String) {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) return

        lastUrl = url
        lastToken = token
        reconnectAttempts = 0

        scope.launch { connectInternal(url, token) }
    }

    private suspend fun connectInternal(url: String, token: String) {
        _connectionState.value = ConnectionState.CONNECTING

        audioDeviceRouter.start()

        voxEngine.onMicStateChange = { open ->
            scope.launch { setMicrophoneEnabled(open) }
        }

        audioDeviceRouter.activeDevice
            .onEach { device -> onAudioDeviceChanged(device) }
            .launchIn(scope)

        voxEngine.start()

        try {
            val newRoom = LiveKit.create(appContext = context)
            room = newRoom

            newRoom.connect(url = url, token = token)

            publishAudioTrack(audioDeviceRouter.activeDevice.value)

            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempts = 0
            Log.d(TAG, "Room connected ✓")
            updateParticipantList()

            newRoom.events.collect { event -> handleEvent(event) }

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _connectionState.value = ConnectionState.FAILED
            scheduleReconnect()
        }
    }

    private suspend fun publishAudioTrack(device: AudioDevice) {

        localAudioTrack?.let { track ->
            try {
                room?.localParticipant?.unpublishTrack(track)
                track.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unpublish old track: ${e.message}")
            }
        }
        localAudioTrack = null

        val options = buildAudioTrackOptions(device)
        Log.d(TAG, "Publishing audio track for device: ${device.displayName()}, options: $options")

        try {
            val track = room?.localParticipant?.createAudioTrack(name = "microphone", options = options)
            if (track != null) {
                localAudioTrack = track
                room?.localParticipant?.publishAudioTrack(track)
            }

            setMicrophoneEnabled(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish audio track: ${e.message}")
        }
    }

    private fun buildAudioTrackOptions(device: AudioDevice): LocalAudioTrackOptions {
        return when (device) {
            is AudioDevice.BluetoothSco -> LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = true,
                highPassFilter = true,
                typingNoiseDetection = false
            )
            is AudioDevice.WiredHeadset -> LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = false,
                autoGainControl = true,
                highPassFilter = true,
                typingNoiseDetection = false
            )
            is AudioDevice.UsbAudio -> LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = false,
                autoGainControl = true,
                highPassFilter = false,
                typingNoiseDetection = false
            )
            is AudioDevice.Earpiece -> LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = true,
                highPassFilter = true,
                typingNoiseDetection = false
            )
        }
    }

    private fun onAudioDeviceChanged(device: AudioDevice) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        Log.d(TAG, "Device changed to ${device.displayName()} — rebuilding audio track")
        scope.launch {

            setMicrophoneEnabled(false)
            delay(150)
            publishAudioTrack(device)
        }
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        if (_isMicEnabled.value == enabled) return
        _isMicEnabled.value = enabled

        try {
            room?.localParticipant?.setMicrophoneEnabled(enabled)
            Log.d(TAG, "Mic ${if (enabled) "OPEN" else "CLOSED"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set mic state: ${e.message}")
        }
    }

    fun onPttPressed(pressed: Boolean) {
        voxEngine.setPttOverride(pressed)
    }

    private fun handleEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected    -> updateParticipantList()
            is RoomEvent.ParticipantDisconnected -> {
                updateParticipantList()
                _remoteLocations.update { it - (event.participant.identity?.value ?: return) }
            }
            is RoomEvent.Disconnected -> {
                Log.w(TAG, "Room disconnected (reason: ${event.error?.message})")
                _connectionState.value = ConnectionState.DISCONNECTED
                _remoteLocations.value = emptyMap()
                _activeSpeaker.value = null
                scheduleReconnect()
            }
            is RoomEvent.DataReceived -> {
                try {
                    val json = String(event.data, Charsets.UTF_8)
                    val loc = gson.fromJson(json, RiderLocation::class.java)
                    val peerId = event.participant?.identity?.value ?: loc.riderId
                    _remoteLocations.update { it + (peerId to loc.copy(riderId = peerId)) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse telemetry: ${e.message}")
                }
            }
            is RoomEvent.TrackSubscribed -> {
                Log.d(TAG, "Remote track subscribed: ${event.track.kind}")
            }
            is RoomEvent.ActiveSpeakersChanged -> {
                val speaker = event.speakers.firstOrNull()?.identity?.value
                _activeSpeaker.value = if (!speaker.isNullOrBlank()) speaker else null
            }
            else -> {}
        }
    }

    private fun updateParticipantList() {
        val remotes = room?.remoteParticipants?.values
            ?.mapNotNull { it.identity?.value ?: it.sid.value }
            ?: emptyList()
        val local = room?.localParticipant?.identity?.value
        _participants.value = if (!local.isNullOrBlank() && !remotes.contains(local)) {
            listOf(local) + remotes
        } else {
            remotes
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached — giving up")
            _connectionState.value = ConnectionState.FAILED
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts++

            val delayMs = minOf(2000L * reconnectAttempts, 30_000L)
            Log.d(TAG, "Reconnect attempt $reconnectAttempts in ${delayMs}ms")
            _connectionState.value = ConnectionState.RECONNECTING
            delay(delayMs)

            if (_connectionState.value == ConnectionState.RECONNECTING) {
                connectInternal(lastUrl, lastToken)
            }
        }
    }

    fun publishLocation(location: RiderLocation) {
        scope.launch {
            try {
                val bytes = gson.toJson(location).toByteArray(Charsets.UTF_8)
                room?.localParticipant?.publishData(
                    bytes
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish location: ${e.message}")
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS

        voxEngine.stop()
        audioDeviceRouter.stop()

        localAudioTrack?.stop()
        localAudioTrack = null

        room?.disconnect()
        room = null

        _connectionState.value = ConnectionState.DISCONNECTED
        _participants.value = emptyList()
        _activeSpeaker.value = null
        _isMicEnabled.value = false
        Log.d(TAG, "Disconnected cleanly")
    }
}
