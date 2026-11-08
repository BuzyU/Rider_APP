package com.ridervoice.mesh.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

interface AudioManagerPort {
    var mode: Int
    var isSpeakerphoneOn: Boolean
    var isBluetoothScoOn: Boolean
    val isBluetoothScoAvailableOffCall: Boolean
    val isWiredHeadsetOn: Boolean
    val availableCommunicationDevices: List<AudioDeviceInfo>

    fun requestCommunicationFocus(): Boolean
    fun abandonCommunicationFocus()
    fun startBluetoothSco()
    fun stopBluetoothSco()
    fun setCommunicationDevice(device: AudioDeviceInfo): Boolean
    fun clearCommunicationDevice()
}

class AndroidAudioManagerPort(
    private val context: Context
) : AudioManagerPort {
    private val audioManager: AudioManager?
        get() = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var audioFocusRequest: AudioFocusRequest? = null

    override var mode: Int
        get() = audioManager?.mode ?: AudioManager.MODE_NORMAL
        set(value) { audioManager?.mode = value }

    override var isSpeakerphoneOn: Boolean
        get() = audioManager?.isSpeakerphoneOn ?: false
        set(value) { audioManager?.isSpeakerphoneOn = value }

    override var isBluetoothScoOn: Boolean
        get() = audioManager?.isBluetoothScoOn ?: false
        set(value) {
            @Suppress("DEPRECATION")
            audioManager?.isBluetoothScoOn = value
        }

    override val isBluetoothScoAvailableOffCall: Boolean
        get() = runCatching {
            @Suppress("DEPRECATION")
            audioManager?.isBluetoothScoAvailableOffCall ?: false
        }.getOrDefault(false)

    override val isWiredHeadsetOn: Boolean
        get() = runCatching {
            @Suppress("DEPRECATION")
            audioManager?.isWiredHeadsetOn ?: false
        }.getOrDefault(false)

    override val availableCommunicationDevices: List<AudioDeviceInfo>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager?.availableCommunicationDevices ?: emptyList() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

    override fun requestCommunicationFocus(): Boolean {
        val am = audioManager ?: return false
        return runCatching {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
    }

    override fun abandonCommunicationFocus() {
        val am = audioManager ?: return
        runCatching {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        }
    }

    override fun startBluetoothSco() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager?.startBluetoothSco()
        }
    }

    override fun stopBluetoothSco() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager?.stopBluetoothSco()
        }
    }

    @SuppressLint("NewApi")
    override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager?.setCommunicationDevice(device) ?: false }.getOrDefault(false)
        } else false
    }

    @SuppressLint("NewApi")
    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager?.clearCommunicationDevice() }
        }
    }
}

