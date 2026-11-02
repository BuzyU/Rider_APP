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

    @SuppressLint("NewApi")
    override fun requestCommunicationFocus(): Boolean {
        val am = audioManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(false)
    }

    @SuppressLint("NewApi")
    override fun abandonCommunicationFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
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

class FakeAudioManagerPort(
    override var mode: Int = AudioManager.MODE_NORMAL,
    override var isSpeakerphoneOn: Boolean = false,
    override var isBluetoothScoOn: Boolean = false,
    override var isBluetoothScoAvailableOffCall: Boolean = false,
    override var isWiredHeadsetOn: Boolean = false,
    override var availableCommunicationDevices: List<AudioDeviceInfo> = emptyList()
) : AudioManagerPort {
    var communicationFocusHeld: Boolean = false
    var selectedCommunicationDevice: AudioDeviceInfo? = null
    var startBluetoothScoCalls: Int = 0
    var stopBluetoothScoCalls: Int = 0

    override fun requestCommunicationFocus(): Boolean {
        communicationFocusHeld = true
        return true
    }

    override fun abandonCommunicationFocus() {
        communicationFocusHeld = false
    }

    override fun startBluetoothSco() {
        startBluetoothScoCalls++
        isBluetoothScoOn = true
    }

    override fun stopBluetoothSco() {
        stopBluetoothScoCalls++
        isBluetoothScoOn = false
    }

    override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
        selectedCommunicationDevice = device
        return true
    }

    override fun clearCommunicationDevice() {
        selectedCommunicationDevice = null
    }
}

