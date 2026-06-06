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

/**
 * LiveKit voice room manager — rewritten to wire up:
 *
 *   1. Audio track options tuned per device type (BT SCO vs wired vs earpiece)
 *   2. Device-change handling: rebuilds the audio track when the router
 *      switches from Bluetooth to wired or vice versa
 *   3. VOX engine integration: mic enable/disable driven by VoxEngine
 *   4. PTT integration: hardware button directly calls setMicrophoneEnabled
 *   5. Reconnection: exponential backoff on disconnect, max 10 attempts
 */
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

    // ── Public state ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _participants = MutableStateFlow<List<String>>(emptyList())
    val participants: StateFlow<List<String>> = _participants

    private val _remoteLocations = MutableStateFlow<Map<String, RiderLocation>>(emptyMap())
    val remoteLocations: StateFlow<Map<String, RiderLocation>> = _remoteLocations

    private val _activeSpeaker = MutableStateFlow<String?>("Nobody")
    val activeSpeaker: StateFlow<String?> = _activeSpeaker

    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled

    // ── Reconnect state ───────────────────────────────────────────────────────

    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 10
    private var lastUrl = ""
    private var lastToken = ""

    private val gson = Gson()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Connect to a LiveKit room.
     *
     * Before connecting:
     *   - Starts AudioDeviceRouter to detect and route to the best device
     *   - Wires VOX engine callbacks to mic enable/disable
     *   - Builds audio track options appropriate for the current device
     *
     * The room is created with Smart VOX active by default.
     * Hardware PTT overrides VOX when pressed.
     */
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

        // 1. Start audio routing — must happen before LiveKit touches AudioManager
        audioDeviceRouter.start()

        // 2. Wire VOX → mic enable/disable
        voxEngine.onMicStateChange = { open ->
            scope.launch { setMicrophoneEnabled(open) }
        }

        // 3. Observe device changes — rebuild audio track on switch
        audioDeviceRouter.activeDevice
            .onEach { device -> onAudioDeviceChanged(device) }
            .launchIn(scope)

        // 4. Start VOX engine (calibrates noise floor, then starts gating)
        voxEngine.start()

        try {
            val newRoom = LiveKit.create(appContext = context)
            room = newRoom

            // Connect with audio track options tuned for current device
            newRoom.connect(url = url, token = token)

            // Publish audio track with processing profile for current device
            publishAudioTrack(audioDeviceRouter.activeDevice.value)

            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempts = 0
            Log.d(TAG, "Room connected ✓")

            // Collect room events
            newRoom.events.collect { event -> handleEvent(event) }

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _connectionState.value = ConnectionState.FAILED
            scheduleReconnect()
        }
    }

    /**
     * Publishes (or re-publishes) a local audio track with processing options
     * tuned for the given audio device.
     *
     * This is called both on initial connect and when the device router
     * switches to a new device mid-session.
     */
    private suspend fun publishAudioTrack(device: AudioDevice) {
        // Unpublish existing track if any
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

            // Start with mic closed — VOX or PTT will open it
            setMicrophoneEnabled(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish audio track: ${e.message}")
        }
    }

    /**
     * Build LiveKit LocalAudioTrackOptions tuned for the physical device.
     *
     * Bluetooth SCO:
     *   - Noise suppression ON (wind, engine, road)
     *   - Echo cancellation ON (audio bleeds back through SCO)
     *   - AGC ON (Cardo/Sena mics vary wildly in gain)
     *   - High-pass filter ON (removes low-frequency motor rumble)
     *
     * Wired headset:
     *   - Noise suppression ON but lighter (less wind noise)
     *   - Echo cancellation OFF (wired has no feedback loop)
     *   - AGC ON
     *
     * Earpiece (fallback):
     *   - All processing ON — worst-case scenario, max filtering
     */
    private fun buildAudioTrackOptions(device: AudioDevice): LocalAudioTrackOptions {
        return when (device) {
            is AudioDevice.BluetoothSco -> LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = true,
                highPassFilter = true,
                typingNoiseDetection = false  // irrelevant on a motorcycle
            )
            is AudioDevice.WiredHeadset -> LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = false,  // wired has no feedback path
                autoGainControl = true,
                highPassFilter = true,
                typingNoiseDetection = false
            )
            is AudioDevice.UsbAudio -> LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = false,
                autoGainControl = true,
                highPassFilter = false,   // USB audio often has hardware filtering
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

    /**
     * Called by AudioDeviceRouter when a plug/unplug or BT state change
     * causes a device switch. We rebuild the audio track for the new device's
     * processing profile.
     */
    private fun onAudioDeviceChanged(device: AudioDevice) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        Log.d(TAG, "Device changed to ${device.displayName()} — rebuilding audio track")
        scope.launch {
            // Brief mute to avoid audio glitch during track rebuild
            setMicrophoneEnabled(false)
            delay(150)
            publishAudioTrack(device)
        }
    }

    // ── Mic control ───────────────────────────────────────────────────────────

    /**
     * Enable or disable the local microphone on the published track.
     * This is the single point of truth for mic state — both VOX and PTT
     * flow through here.
     */
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

    /**
     * Hardware PTT button pressed/released.
     * Delegates to VoxEngine which manages the PTT-overrides-VOX logic.
     */
    fun onPttPressed(pressed: Boolean) {
        voxEngine.setPttOverride(pressed)
    }

    // ── Event handling ────────────────────────────────────────────────────────

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
                scheduleReconnect()
            }
            is RoomEvent.DataReceived -> {
                try {
                    val json = String(event.data, Charsets.UTF_8)
                    val loc = gson.fromJson(json, RiderLocation::class.java)
                    _remoteLocations.update { it + (loc.riderId to loc) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse telemetry: ${e.message}")
                }
            }
            is RoomEvent.TrackSubscribed -> {
                Log.d(TAG, "Remote track subscribed: ${event.track.kind}")
            }
            is RoomEvent.ActiveSpeakersChanged -> {
                val speaker = event.speakers.firstOrNull()
                _activeSpeaker.value = speaker?.identity?.value ?: "Nobody"
            }
            else -> {}
        }
    }

    private fun updateParticipantList() {
        _participants.value = room?.remoteParticipants?.values
            ?.map { it.identity?.value ?: it.sid.value }
            ?: emptyList()
    }

    // ── Reconnection ──────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached — giving up")
            _connectionState.value = ConnectionState.FAILED
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts++
            // Exponential backoff: 2s, 4s, 8s … capped at 30s
            val delayMs = minOf(2000L * reconnectAttempts, 30_000L)
            Log.d(TAG, "Reconnect attempt $reconnectAttempts in ${delayMs}ms")
            _connectionState.value = ConnectionState.RECONNECTING
            delay(delayMs)

            if (_connectionState.value == ConnectionState.RECONNECTING) {
                connectInternal(lastUrl, lastToken)
            }
        }
    }

    // ── Telemetry ─────────────────────────────────────────────────────────────

    fun publishLocation(location: RiderLocation) {
        scope.launch {
            try {
                val bytes = gson.toJson(location).toByteArray(Charsets.UTF_8)
                room?.localParticipant?.publishData(
                    bytes,
                    options = io.livekit.android.room.track.DataPublishOptions(reliability = io.livekit.android.room.track.DataPublishReliability.LOSSY)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish location: ${e.message}")
            }
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS  // prevent auto-reconnect

        voxEngine.stop()
        audioDeviceRouter.stop()

        localAudioTrack?.stop()
        localAudioTrack = null

        room?.disconnect()
        room = null

        _connectionState.value = ConnectionState.DISCONNECTED
        _participants.value = emptyList()
        _isMicEnabled.value = false
        Log.d(TAG, "Disconnected cleanly")
    }
}
