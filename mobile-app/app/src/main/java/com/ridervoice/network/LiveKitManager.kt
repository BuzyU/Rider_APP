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
import io.livekit.android.room.participant.AudioTrackPublishOptions
import io.livekit.android.AudioOptions
import com.google.gson.Gson
import com.ridervoice.models.RiderLocation
import com.ridervoice.diagnostics.models.StatsSnapshot
import com.ridervoice.diagnostics.models.OutboundAudioStats
import com.ridervoice.diagnostics.models.InboundRiderStats
import livekit.org.webrtc.RTCStatsReport
import livekit.org.webrtc.RTCStats
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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

    private val _participants = MutableStateFlow<List<com.ridervoice.models.Participant>>(emptyList())
    val participants: StateFlow<List<com.ridervoice.models.Participant>> = _participants

    private val _remoteLocations = MutableStateFlow<Map<String, RiderLocation>>(emptyMap())
    val remoteLocations: StateFlow<Map<String, RiderLocation>> = _remoteLocations

    private val _activeSpeaker = MutableStateFlow<String?>(null)
    val activeSpeaker: StateFlow<String?> = _activeSpeaker

    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled

    private val _deafened = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> = _deafened

    private val _isUserMuted = MutableStateFlow(false)
    val isUserMuted: StateFlow<Boolean> = _isUserMuted

    @Volatile var noiseSuppressionEnabled: Boolean = true

    private val micMutex = Mutex()
    private val publishMutex = Mutex()

    @Volatile private var voiceGateOpen = false
    @Volatile private var userMuted = false

    private var deviceJob: Job? = null
    private var eventsJob: Job? = null
    private var lastOptions: LocalAudioTrackOptions? = null

    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 10
    private var lastUrl = ""
    private var lastToken = ""

    private var lastBroadcastLoc: RiderLocation? = null
    private var lastBroadcastTime: Long = 0L

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
                    publishAudioTrack(device, force = true)
                    voxEngine.updatePreferredDevice()
                    restartVox()
                }
            }
            .launchIn(scope)

        scope.launch {
            audioDeviceRouter.republishRequests.collect {
                publishAudioTrack(audioDeviceRouter.activeDevice.value, force = true)
                voxEngine.updatePreferredDevice()
                restartVox()
            }
        }

        try {
            val newRoom = LiveKit.create(
                appContext = context,
                overrides = LiveKitOverrides(
                    audioOptions = AudioOptions(
                        audioHandler = NoAudioHandler(),
                        javaAudioDeviceModuleCustomizer = { builder ->
                            builder.setSamplesReadyCallback { audioSamples ->
                                val data = audioSamples.data
                                if (data != null && data.isNotEmpty()) {
                                    voxEngine.onTxFrame(data, audioSamples.sampleRate)
                                }
                            }
                            builder.setUseHardwareAcousticEchoCanceler(true)
                            builder.setUseHardwareNoiseSuppressor(true)
                        }
                    )
                )
            )
            room = newRoom

            newRoom.connect(url = url, token = token)

            withTimeoutOrNull(5000) {
                while (audioDeviceRouter.routerState.value != RouterState.ACTIVE &&
                       audioDeviceRouter.routerState.value != RouterState.FAILED) {
                    delay(100)
                }
            }

            publishAudioTrack(audioDeviceRouter.activeDevice.value, force = true)

            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempts = 0
            updateParticipantList()

            startVoxSafely()

            eventsJob = scope.launch { newRoom.events.collect { event -> handleEvent(event) } }

        } catch (e: Exception) {
            _connectionState.value = ConnectionState.FAILED
            scheduleReconnect()
        }
    }

    private suspend fun startVoxSafely() {
        withTimeoutOrNull(5000) {
            while (audioDeviceRouter.routerState.value != RouterState.ACTIVE) delay(100)
        }
        delay(150)
        try {
            voxEngine.start()
        } catch (e: Exception) {
            Log.e(TAG, "VOX start fallback: ${e.message}")
        }
    }

    private fun restartVox() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        scope.launch {
            try { voxEngine.stop() } catch (e: Exception) { Log.w(TAG, "VOX stop: ${e.message}") }
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
        if (lp.getTrackPublication(Track.Source.MICROPHONE) == null) {
            if (_connectionState.value == ConnectionState.CONNECTED && localAudioTrack == null) {
                publishAudioTrack(audioDeviceRouter.activeDevice.value, force = true)
            }
            return
        }
        micMutex.withLock {
            localAudioTrack?.enabled = want
            val ok = try { lp.setMicrophoneEnabled(want) } catch (e: Exception) { false }
            if (ok) {
                _isMicEnabled.value = want
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

            val track = try {
                lp.createAudioTrack(name = "microphone", options = opts).also {
                    runCatching { it.start() }
                }
            } catch (e: Exception) {
                null
            } ?: return@withLock false

            val ok = try {
                lp.publishAudioTrack(
                    track,
                    AudioTrackPublishOptions(
                        dtx = false,
                        red = false,
                        audioBitrate = 32000
                    )
                )
            } catch (e: Exception) {
                false
            }
            if (ok) {
                localAudioTrack = track
                lastOptions = opts
                runCatching { track.prewarm() }
                runCatching { lp.setMicrophoneEnabled(false) }
                _isMicEnabled.value = false
                true
            } else {
                runCatching { track.stop() }
                false
            }
        }
        if (published) scope.launch { applyMic() }
    }

    private fun buildAudioTrackOptions(device: AudioDevice): LocalAudioTrackOptions {
        val ns = noiseSuppressionEnabled
        return when (device) {
            is AudioDevice.BluetoothSco -> LocalAudioTrackOptions(
                noiseSuppression = ns,
                echoCancellation = false,
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
            is AudioDevice.Earpiece, is AudioDevice.Speakerphone -> LocalAudioTrackOptions(
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
            is RoomEvent.Reconnecting -> {}
            is RoomEvent.Reconnected -> {
                scope.launch {
                    applyMic()
                    restartVox()
                }
            }
            is RoomEvent.Disconnected -> {
                _connectionState.value = ConnectionState.DISCONNECTED
                _remoteLocations.value = emptyMap()
                _activeSpeaker.value = null
                scheduleReconnect()
            }
            is RoomEvent.DataReceived -> {
                try {
                    val json = String(event.data, Charsets.UTF_8)
                    val loc = gson.fromJson(json, RiderLocation::class.java)
                    val peerId = event.participant?.identity?.value?.takeIf { it.isNotBlank() }
                        ?: loc.riderId?.takeIf { it.isNotBlank() }
                    if (peerId != null) {
                        _remoteLocations.update { it + (peerId to loc.copy(riderId = peerId)) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Telemetry parse error: ${e.message}")
                }
            }
            is RoomEvent.TrackSubscribed -> {
                (event.track as? RemoteAudioTrack)?.let { remoteTrack ->
                    remoteTrack.enabled = true
                    remoteTrack.setVolume(if (_deafened.value) 0.0 else 1.0)
                }
            }
            is RoomEvent.ActiveSpeakersChanged -> {
                val speaker = event.speakers.firstOrNull()?.identity?.value
                _activeSpeaker.value = if (!speaker.isNullOrBlank()) speaker else null
            }
            else -> {}
        }
    }

    private fun updateParticipantList() {
        val localParticipant = room?.localParticipant
        val localIdentity = localParticipant?.identity?.value
        val localName = localParticipant?.name?.takeIf { it.isNotBlank() }

        val remotes = room?.remoteParticipants?.values
            ?.filter { !it.identity?.value.isNullOrBlank() }
            ?.map { p ->
                com.ridervoice.models.Participant(
                    identity = p.identity!!.value,
                    displayName = p.name?.takeIf { it.isNotBlank() }
                )
            } ?: emptyList()

        _participants.value = if (!localIdentity.isNullOrBlank()) {
            listOf(com.ridervoice.models.Participant(identity = localIdentity, displayName = localName)) + remotes
        } else {
            remotes
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.FAILED
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts++
            val delayMs = minOf(1000L * reconnectAttempts, 30_000L)
            _connectionState.value = ConnectionState.RECONNECTING
            delay(delayMs)

            if (_connectionState.value == ConnectionState.RECONNECTING) {
                connectInternal(lastUrl, lastToken)
            }
        }
    }

    fun onNetworkRestored() {
        if ((_connectionState.value == ConnectionState.FAILED ||
             _connectionState.value == ConnectionState.DISCONNECTED ||
             _connectionState.value == ConnectionState.RECONNECTING) &&
            lastUrl.isNotBlank() && lastToken.isNotBlank()) {
            reconnectAttempts = 0
            reconnectJob?.cancel()
            scope.launch { connectInternal(lastUrl, lastToken) }
        }
    }

    fun publishLocation(location: RiderLocation) {
        val now = System.currentTimeMillis()
        val prev = lastBroadcastLoc
        val dist = if (prev != null) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(prev.lat, prev.lng, location.lat, location.lng, results)
            results[0]
        } else Float.MAX_VALUE
        val speedKmh = (location.speed ?: 0f) * 3.6f
        val isStationary = speedKmh < 1.5f

        if (prev != null && isStationary && dist < 3.0f && (now - lastBroadcastTime) < 30_000L) {
            return
        }

        lastBroadcastLoc = location
        lastBroadcastTime = now

        scope.launch {
            try {
                val bytes = gson.toJson(location).toByteArray(Charsets.UTF_8)
                room?.localParticipant?.publishData(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Location publish: ${e.message}")
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS
        lastBroadcastLoc = null
        lastBroadcastTime = 0L
        deviceJob?.cancel()
        deviceJob = null
        eventsJob?.cancel()
        eventsJob = null
        voiceGateOpen = false
        voxEngine.onMicStateChange = null
        audioDeviceRouter.onFocusRegained = null
        _deafened.value = false

        runCatching { room?.disconnect() }

        voxEngine.stop()
        audioDeviceRouter.stop()

        localAudioTrack?.stop()
        localAudioTrack = null
        lastOptions = null

        runCatching { room?.release() }
        room = null

        _connectionState.value = ConnectionState.DISCONNECTED
        _participants.value = emptyList()
        _activeSpeaker.value = null
        _isMicEnabled.value = false
    }

    suspend fun collectStatsSnapshot(): StatsSnapshot? {
        val currentRoom = room ?: return null
        if (_connectionState.value != ConnectionState.CONNECTED) return null

        val now = System.currentTimeMillis()

        val pubReport: RTCStatsReport? = suspendCancellableCoroutine { cont ->
            try {
                currentRoom.getPublisherRTCStats { report ->
                    if (cont.isActive) cont.resume(report)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

        val subReport: RTCStatsReport? = suspendCancellableCoroutine { cont ->
            try {
                currentRoom.getSubscriberRTCStats { report ->
                    if (cont.isActive) cont.resume(report)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

        var pubRttMs: Double? = null
        var pubBytesSent = 0L
        var pubPacketsSent = 0L

        pubReport?.statsMap?.values?.forEach { stat ->
            if (stat.type == "candidate-pair" && (stat.members["nominated"] == true || stat.members["state"] == "succeeded")) {
                val rtt = (stat.members["currentRoundTripTime"] as? Number)?.toDouble()
                if (rtt != null) {
                    pubRttMs = rtt * 1000.0
                }
                pubBytesSent = (stat.members["bytesSent"] as? Number)?.toLong() ?: pubBytesSent
                pubPacketsSent = (stat.members["packetsSent"] as? Number)?.toLong() ?: pubPacketsSent
            }
        }

        var outboundAudio: OutboundAudioStats? = null
        pubReport?.statsMap?.values?.forEach { stat ->
            if (stat.type == "outbound-rtp" && stat.members["kind"] == "audio") {
                val bSent = (stat.members["bytesSent"] as? Number)?.toLong() ?: 0L
                val pSent = (stat.members["packetsSent"] as? Number)?.toLong() ?: 0L
                val targetBitrate = (stat.members["targetBitrate"] as? Number)?.toDouble()
                outboundAudio = OutboundAudioStats(
                    bytesSent = bSent,
                    packetsSent = pSent,
                    targetBitrate = targetBitrate
                )
            }
        }

        var subRttMs: Double? = null
        var subBytesRecv = 0L
        var subPacketsRecv = 0L

        subReport?.statsMap?.values?.forEach { stat ->
            if (stat.type == "candidate-pair" && (stat.members["nominated"] == true || stat.members["state"] == "succeeded")) {
                val rtt = (stat.members["currentRoundTripTime"] as? Number)?.toDouble()
                if (rtt != null) {
                    subRttMs = rtt * 1000.0
                }
                subBytesRecv = (stat.members["bytesReceived"] as? Number)?.toLong() ?: subBytesRecv
                subPacketsRecv = (stat.members["packetsReceived"] as? Number)?.toLong() ?: subPacketsRecv
            }
        }

        val inboundRiders = mutableListOf<InboundRiderStats>()
        val remoteParticipants = currentRoom.remoteParticipants

        for ((identity, participant) in remoteParticipants) {
            val riderHandle = identity.value.ifBlank { participant.sid.value }

            val audioPubs = participant.trackPublications.values.filter { it.kind == Track.Kind.AUDIO }
            for (pub in audioPubs) {
                val trackSid = pub.sid
                val track = pub.track

                val trackReport = try { track?.getRTCStats() } catch (e: Exception) { null }

                val inboundStat: RTCStats? = trackReport?.statsMap?.values?.firstOrNull { it.type == "inbound-rtp" }
                    ?: subReport?.statsMap?.values?.firstOrNull { stat ->
                        stat.type == "inbound-rtp" && (
                            stat.members["trackIdentifier"] == track?.rtcTrack?.id() ||
                            stat.members["trackId"] == track?.rtcTrack?.id() ||
                            stat.members["trackId"] == trackSid
                        )
                    }

                if (inboundStat != null) {
                    val pktsRecv = (inboundStat.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                    val pktsLost = (inboundStat.members["packetsLost"] as? Number)?.toLong() ?: 0L
                    val jitterRaw = (inboundStat.members["jitter"] as? Number)?.toDouble()
                    val jitterMs = jitterRaw?.times(1000.0)
                    val bytesRecv = (inboundStat.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                    val jbDelay = (inboundStat.members["jitterBufferDelay"] as? Number)?.toDouble()
                    val concealed = (inboundStat.members["concealedSamples"] as? Number)?.toLong()

                    inboundRiders.add(
                        InboundRiderStats(
                            riderIdentity = riderHandle,
                            trackSid = trackSid,
                            packetsReceived = pktsRecv,
                            packetsLost = pktsLost,
                            jitterMs = jitterMs,
                            bytesReceived = bytesRecv,
                            jitterBufferDelay = jbDelay,
                            concealedSamples = concealed
                        )
                    )
                }
            }
        }

        val effectiveRtt = pubRttMs ?: subRttMs
        val totalPacketsSent = outboundAudio?.packetsSent ?: pubPacketsSent
        val totalPacketsRecv = if (inboundRiders.isNotEmpty()) inboundRiders.sumOf { it.packetsReceived } else subPacketsRecv
        val totalPacketsLost = inboundRiders.sumOf { it.packetsLost }
        val avgJitterMs = if (inboundRiders.isNotEmpty()) {
            val jitters = inboundRiders.mapNotNull { it.jitterMs }
            if (jitters.isNotEmpty()) jitters.average() else null
        } else null
        val totalBytesSent = outboundAudio?.bytesSent ?: pubBytesSent
        val totalBytesRecv = if (inboundRiders.isNotEmpty()) inboundRiders.sumOf { it.bytesReceived } else subBytesRecv

        return StatsSnapshot(
            timestamp = now,
            rttMs = effectiveRtt,
            packetsSent = totalPacketsSent,
            packetsReceived = totalPacketsRecv,
            packetsLost = totalPacketsLost,
            jitterMs = avgJitterMs,
            bytesSent = totalBytesSent,
            bytesReceived = totalBytesRecv,
            outboundAudio = outboundAudio,
            inboundRiders = inboundRiders
        )
    }
}
