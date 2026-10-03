package com.ridervoice.network

import android.content.Context
import android.util.Log
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.AudioDeviceRouter
import com.ridervoice.audio.RouterState
import com.ridervoice.audio.VoxEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.AudioOptions
import com.google.gson.Gson
import com.ridervoice.models.RiderLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Actual transmit state of the published microphone track (set only after the SDK confirms). */
    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled

    private val _deafened = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> = _deafened

    private val _isUserMuted = MutableStateFlow(false)
    val isUserMuted: StateFlow<Boolean> = _isUserMuted

    /** Set from Settings (noise cancellation toggle). Only affects noiseSuppression. */
    @Volatile var noiseSuppressionEnabled: Boolean = true

    private val micMutex = Mutex()
    private val publishMutex = Mutex()

    /** Result of VOX/PTT. */
    @Volatile private var voiceGateOpen = false
    /** Notification/UI mute. */
    @Volatile private var userMuted = false

    private var deviceJob: Job? = null
    private var eventsJob: Job? = null
    private var lastOptions: LocalAudioTrackOptions? = null

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

    @OptIn(FlowPreview::class)
    private suspend fun connectInternal(url: String, token: String) {
        _connectionState.value = ConnectionState.CONNECTING

        audioDeviceRouter.start()

        voxEngine.onMicStateChange = { open -> onVoiceGate(open) }
        audioDeviceRouter.onFocusRegained = { restartVox() }

        // Release any previous Room (reconnect path) so we never leak a WebRTC stack.
        eventsJob?.cancel()
        runCatching { room?.disconnect() }
        runCatching { room?.release() }
        room = null
        localAudioTrack = null
        lastOptions = null

        deviceJob?.cancel()
        deviceJob = audioDeviceRouter.activeDevice
            .drop(1)
            .distinctUntilChanged()
            .debounce(400)
            .onEach { device ->
                scope.launch {
                    publishAudioTrack(device)
                    restartVox()
                }
            }
            .launchIn(scope)

        try {
            // AudioDeviceRouter is the single owner of audio mode / routing / focus.
            val newRoom = LiveKit.create(
                appContext = context,
                overrides = LiveKitOverrides(
                    audioOptions = AudioOptions(audioHandler = NoAudioHandler())
                )
            )
            room = newRoom

            newRoom.connect(url = url, token = token)

            publishAudioTrack(audioDeviceRouter.activeDevice.value, force = true)

            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempts = 0
            Log.d(TAG, "Room connected ✓")
            updateParticipantList()

            startVoxSafely()

            eventsJob = scope.launch { newRoom.events.collect { event -> handleEvent(event) } }

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _connectionState.value = ConnectionState.FAILED
            scheduleReconnect()
        }
    }

    private suspend fun startVoxSafely() {
        // Wait for the router to settle so calibration measures the real input device.
        withTimeoutOrNull(5000) {
            while (audioDeviceRouter.routerState.value != RouterState.ACTIVE) delay(100)
        }
        delay(1500)
        try {
            voxEngine.start()
        } catch (e: Exception) {
            // AudioRecord failure must never crash the process; stay connected in PTT-only mode.
            Log.e(TAG, "VOX engine failed to start — continuing in PTT-only mode: ${e.message}")
        }
    }

    private fun restartVox() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        scope.launch {
            try { voxEngine.stop() } catch (e: Exception) { Log.w(TAG, "VOX stop failed: ${e.message}") }
            startVoxSafely()
        }
    }

    fun onVoiceGate(open: Boolean) {
        voiceGateOpen = open
        scope.launch { applyMic() }
    }

    fun setUserMuted(m: Boolean) {
        userMuted = m
        _isUserMuted.value = m
        scope.launch { applyMic() }
    }

    private suspend fun applyMic() {
        val want = voiceGateOpen && !userMuted
        val lp = room?.localParticipant ?: return
        // The SDK DROPS muted tracks on a full reconnect. If our publication is gone,
        // republish OUR track with OUR options instead of letting setMicrophoneEnabled(true)
        // auto-create a default track.
        if (lp.getTrackPublication(Track.Source.MICROPHONE) == null) {
            if (_connectionState.value == ConnectionState.CONNECTED || localAudioTrack != null) {
                publishAudioTrack(audioDeviceRouter.activeDevice.value, force = true)
            }
            return
        }
        micMutex.withLock {
            val ok = try { lp.setMicrophoneEnabled(want) } catch (e: Exception) { false }
            if (ok) {
                _isMicEnabled.value = want
                Log.d(TAG, "Mic ${if (want) "OPEN" else "CLOSED"}")
            } else {
                Log.w(TAG, "setMicrophoneEnabled($want) failed")
            }
        }
    }

    private suspend fun publishAudioTrack(device: AudioDevice, force: Boolean = false) {
        val published = publishMutex.withLock {
            val lp = room?.localParticipant ?: return@withLock false
            val opts = buildAudioTrackOptions(device)
            if (!force && localAudioTrack != null && opts == lastOptions) return@withLock false

            lp.audioTrackCaptureDefaults = opts

            localAudioTrack?.let { old ->
                runCatching { lp.unpublishTrack(old) }
                runCatching { old.stop() }
            }
            localAudioTrack = null

            Log.d(TAG, "Publishing audio track for device: ${device.displayName()}, options: $opts")
            val track = lp.createAudioTrack(name = "microphone", options = opts)
            val ok = try {
                lp.publishAudioTrack(track)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish audio track: ${e.message}")
                false
            }
            if (ok) {
                localAudioTrack = track
                lastOptions = opts
                // Publication exists now, so this really mutes (fixes hot-mic-at-join).
                runCatching { lp.setMicrophoneEnabled(false) }
                _isMicEnabled.value = false
                true
            } else {
                runCatching { track.stop() }
                false
            }
        }
        if (published) applyMic()
    }

    private fun buildAudioTrackOptions(device: AudioDevice): LocalAudioTrackOptions {
        val ns = noiseSuppressionEnabled
        return when (device) {
            is AudioDevice.BluetoothSco -> LocalAudioTrackOptions(
                noiseSuppression = ns,
                echoCancellation = true,
                autoGainControl = false,
                highPassFilter = true,
                typingNoiseDetection = false
            )
            is AudioDevice.WiredHeadset -> LocalAudioTrackOptions(
                noiseSuppression = ns,
                echoCancellation = true,
                autoGainControl = false,
                highPassFilter = true,
                typingNoiseDetection = false
            )
            is AudioDevice.UsbAudio -> LocalAudioTrackOptions(
                noiseSuppression = ns,
                echoCancellation = true,
                autoGainControl = false,
                highPassFilter = true,
                typingNoiseDetection = false
            )
            is AudioDevice.Earpiece -> LocalAudioTrackOptions(
                noiseSuppression = ns,
                echoCancellation = true,
                autoGainControl = true,
                highPassFilter = true,
                typingNoiseDetection = false
            )
        }
    }

    fun onPttPressed(pressed: Boolean) {
        voxEngine.setPttOverride(pressed)
    }

    fun setDeafened(d: Boolean) {
        _deafened.value = d
        applyDeafen()
    }

    private fun applyDeafen() {
        val v = if (_deafened.value) 0.0 else 1.0
        room?.remoteParticipants?.values?.forEach { p ->
            p.audioTrackPublications.forEach { (_, t) -> (t as? RemoteAudioTrack)?.setVolume(v) }
        }
    }

    private fun handleEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected    -> updateParticipantList()
            is RoomEvent.ParticipantDisconnected -> {
                updateParticipantList()
                _remoteLocations.update { it - (event.participant.identity?.value ?: return) }
            }
            is RoomEvent.Reconnecting -> Log.w(TAG, "Room reconnecting…")
            is RoomEvent.Reconnected -> {
                Log.d(TAG, "Room reconnected — re-asserting mic gate")
                scope.launch { applyMic() }
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
                (event.track as? RemoteAudioTrack)?.setVolume(if (_deafened.value) 0.0 else 1.0)
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
        deviceJob?.cancel()
        deviceJob = null
        eventsJob?.cancel()
        eventsJob = null
        voiceGateOpen = false
        voxEngine.onMicStateChange = null
        audioDeviceRouter.onFocusRegained = null
        _deafened.value = false

        voxEngine.stop()
        audioDeviceRouter.stop()

        localAudioTrack?.stop()
        localAudioTrack = null
        lastOptions = null

        // disconnect() alone does not free the PeerConnectionFactory / ADM; release() does.
        runCatching { room?.disconnect() }
        runCatching { room?.release() }
        room = null

        _connectionState.value = ConnectionState.DISCONNECTED
        _participants.value = emptyList()
        _activeSpeaker.value = null
        _isMicEnabled.value = false
        Log.d(TAG, "Disconnected cleanly")
    }
}
