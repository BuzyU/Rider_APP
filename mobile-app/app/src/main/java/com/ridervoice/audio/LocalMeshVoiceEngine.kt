package com.ridervoice.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import livekit.org.webrtc.*
import livekit.org.webrtc.audio.AudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule

class LocalMeshVoiceEngine(
    private val context: Context,
    private val voxEngine: VoxEngine
) {
    private val TAG = "LocalMeshVoiceEngine"

    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var localAudioTrack: AudioTrack? = null

    private val peerConnections = mutableMapOf<String, PeerConnection>()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        val audioDeviceModule = createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        audioDeviceModule.release()

        Log.d(TAG, "LocalMeshVoiceEngine initialized for offline mode")
    }

    private fun createAudioDeviceModule(): AudioDeviceModule {
        return JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
    }

    fun startLocalAudio() {
        val audioSource = factory?.createAudioSource(MediaConstraints())
        localAudioTrack = factory?.createAudioTrack("local_audio_track_offline", audioSource)
        localAudioTrack?.setEnabled(true)
        Log.d(TAG, "Local audio track created for offline mesh")
    }

    fun stopLocalAudio() {
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null
    }

    fun createPeerConnection(peerId: String, observer: PeerConnection.Observer): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())

        val pc = factory?.createPeerConnection(rtcConfig, observer)

        localAudioTrack?.let { track ->
            pc?.addTrack(track, listOf("offline_mesh_audio_stream"))
        }

        pc?.let { peerConnections[peerId] = it }

        _isConnected.value = peerConnections.isNotEmpty()
        return pc
    }

    fun removePeer(peerId: String) {
        peerConnections[peerId]?.close()
        peerConnections.remove(peerId)
        _isConnected.value = peerConnections.isNotEmpty()
    }

    fun cleanup() {
        stopLocalAudio()
        peerConnections.forEach { (_, pc) -> pc.close() }
        peerConnections.clear()
        factory?.dispose()
        factory = null
        eglBase?.release()
        _isConnected.value = false
    }

    fun createOffer(peerId: String, callback: (SessionDescription?) -> Unit) {
        val pc = peerConnections[peerId] ?: return
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                pc.setLocalDescription(this, desc)
                callback(desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { callback(null) }
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    fun createAnswer(peerId: String, callback: (SessionDescription?) -> Unit) {
        val pc = peerConnections[peerId] ?: return
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                pc.setLocalDescription(this, desc)
                callback(desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { callback(null) }
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    fun setRemoteDescription(peerId: String, sdp: SessionDescription) {
        val pc = peerConnections[peerId] ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully for $peerId")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "Failed to set remote description: $err")
            }
        }, sdp)
    }

    fun addIceCandidate(peerId: String, candidate: IceCandidate) {
        val pc = peerConnections[peerId] ?: return
        pc.addIceCandidate(candidate)
    }
}
