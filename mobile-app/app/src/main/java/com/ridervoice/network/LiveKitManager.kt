package com.ridervoice.network

import android.content.Context
import android.util.Log
import com.ridervoice.audio.AudioConfigurator
import com.ridervoice.audio.AudioDeviceManager
import com.ridervoice.audio.AudioFocusManager
import com.ridervoice.audio.BluetoothState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrackOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.google.gson.Gson
import com.ridervoice.models.RiderLocation
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveKitManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioDeviceManager: AudioDeviceManager,
    private val audioFocusManager: AudioFocusManager,
    private val audioConfigurator: AudioConfigurator
) {

    private var room: Room? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _participants = MutableStateFlow<List<String>>(emptyList())
    val participants: StateFlow<List<String>> = _participants

    private val _remoteLocations = MutableStateFlow<Map<String, RiderLocation>>(emptyMap())
    val remoteLocations: StateFlow<Map<String, RiderLocation>> = _remoteLocations

    private val gson = Gson()

    init {
        // Monitor Bluetooth State to enforce Safe Fallback Mute
        CoroutineScope(Dispatchers.Main).launch {
            audioDeviceManager.bluetoothState.collect { state ->
                if (state == BluetoothState.DISCONNECTED && room != null) {
                    Log.w("LiveKitManager", "Bluetooth dropped! Auto-muting local microphone.")
                    setMicrophoneEnabled(false)
                }
            }
        }
    }

    fun connect(url: String, token: String) {
        _connectionState.value = ConnectionState.CONNECTING

        // Configure hardware for voice communications
        audioConfigurator.configureVoiceMode()
        audioFocusManager.requestFocus()
        audioDeviceManager.enableBluetoothAudio()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val audioOptions = LocalAudioTrackOptions(
                    echoCancellation = true,
                    noiseSuppression = true,
                    autoGainControl = true
                )

                room = LiveKit.create(
                    appContext = context
                )

                room?.connect(
                    url = url,
                    token = token
                )

                // Default to OPEN MIC (Smart VOX) as requested
                room?.localParticipant?.setMicrophoneEnabled(true)

                _connectionState.value = ConnectionState.CONNECTED

                room?.events?.collect { event ->
                    handleEvent(event)
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.FAILED
                e.printStackTrace()
                cleanupAudio()
            }
        }
    }

    private fun cleanupAudio() {
        audioDeviceManager.disableBluetoothAudio()
        audioFocusManager.abandonFocus()
    }

    private fun handleEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected -> updateParticipantList()
            is RoomEvent.ParticipantDisconnected -> {
                updateParticipantList()
                removeParticipantLocation(event.participant.identity?.value)
            }
            is RoomEvent.Disconnected -> {
                _connectionState.value = ConnectionState.DISCONNECTED
                _remoteLocations.value = emptyMap()
            }
            is RoomEvent.DataReceived -> {
                try {
                    val jsonString = String(event.data, Charsets.UTF_8)
                    val loc = gson.fromJson(jsonString, RiderLocation::class.java)
                    _remoteLocations.update { it + (loc.riderId to loc) }
                } catch (e: Exception) {
                    Log.e("LiveKitManager", "Failed to parse telemetry: ${e.message}")
                }
            }
            else -> {}
        }
    }

    private fun removeParticipantLocation(identity: String?) {
        if (identity == null) return
        _remoteLocations.update { it - identity }
    }

    private fun updateParticipantList() {
        val ids = room?.remoteParticipants?.keys?.map { it.value } ?: emptyList()
        _participants.value = ids
    }

    fun disconnect() {
        room?.disconnect()
        room = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _participants.value = emptyList()
        cleanupAudio()
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            room?.localParticipant?.setMicrophoneEnabled(enabled)
        }
    }

    fun publishLocation(location: RiderLocation) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonString = gson.toJson(location)
                room?.localParticipant?.publishData(jsonString.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e("LiveKitManager", "Failed to publish telemetry", e)
            }
        }
    }
}
