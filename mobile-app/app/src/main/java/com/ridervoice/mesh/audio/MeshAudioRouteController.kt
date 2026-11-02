package com.ridervoice.mesh.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.ridervoice.mesh.transport.AudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshAudioRouteController @Inject constructor(
    private val context: Context?
) {
    private val _activeRoute = MutableStateFlow(AudioRoute.SPEAKER)
    val activeRoute: StateFlow<AudioRoute> = _activeRoute.asStateFlow()

    private var audioFocusRequest: AudioFocusRequest? = null
    private var isCommunicationModeAcquired = false

    private val audioManager: AudioManager?
        get() = context?.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @SuppressLint("NewApi")
    fun acquireCommunicationMode() {
        val am = audioManager ?: return
        if (isCommunicationModeAcquired) return
        isCommunicationModeAcquired = true

        // 1. Request audio focus for voice communication
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { /* Focus change listener */ }
                    .build()
                audioFocusRequest = focusReq
                am.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        }

        // 2. Set mode to MODE_IN_COMMUNICATION
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        // 3. Select default route: Bluetooth if available, else Wired, else Speakerphone
        val defaultRoute = determineInitialRoute(am)
        setRoute(defaultRoute)
    }

    private fun determineInitialRoute(am: AudioManager): AudioRoute {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
            val hasBt = devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID
            }
            val hasWired = devices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            when {
                hasBt -> AudioRoute.BLUETOOTH
                hasWired -> AudioRoute.WIRED
                else -> AudioRoute.SPEAKER
            }
        } else {
            val hasBt = runCatching {
                @Suppress("DEPRECATION")
                am.isBluetoothScoAvailableOffCall
            }.getOrDefault(false)
            val hasWired = runCatching {
                @Suppress("DEPRECATION")
                am.isWiredHeadsetOn
            }.getOrDefault(false)
            when {
                hasBt -> AudioRoute.BLUETOOTH
                hasWired -> AudioRoute.WIRED
                else -> AudioRoute.SPEAKER
            }
        }
    }

    fun setRoute(route: AudioRoute) {
        _activeRoute.value = route
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyModernRoute(am, route)
        } else {
            applyLegacyRoute(am, route)
        }
    }

    @SuppressLint("NewApi")
    private fun applyModernRoute(am: AudioManager, route: AudioRoute) {
        val devices = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
        when (route) {
            AudioRoute.BLUETOOTH -> {
                val btDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
                }
                if (btDevice != null) {
                    runCatching { am.setCommunicationDevice(btDevice) }
                }
                runCatching {
                    am.isSpeakerphoneOn = false
                    @Suppress("DEPRECATION")
                    am.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = true
                }
            }
            AudioRoute.SPEAKER -> {
                runCatching {
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = false
                    am.clearCommunicationDevice()
                }
                val speakerDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    runCatching { am.setCommunicationDevice(speakerDevice) }
                }
                runCatching { am.isSpeakerphoneOn = true }
            }
            AudioRoute.WIRED -> {
                runCatching {
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = false
                    am.clearCommunicationDevice()
                }
                val wiredDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (wiredDevice != null) {
                    runCatching { am.setCommunicationDevice(wiredDevice) }
                }
                runCatching { am.isSpeakerphoneOn = false }
            }
            AudioRoute.EARPIECE -> {
                runCatching {
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = false
                    am.clearCommunicationDevice()
                }
                val earpieceDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (earpieceDevice != null) {
                    runCatching { am.setCommunicationDevice(earpieceDevice) }
                }
                runCatching { am.isSpeakerphoneOn = false }
            }
        }
    }

    private fun applyLegacyRoute(am: AudioManager, route: AudioRoute) {
        when (route) {
            AudioRoute.BLUETOOTH -> {
                runCatching {
                    am.isSpeakerphoneOn = false
                    @Suppress("DEPRECATION")
                    am.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = true
                }
            }
            AudioRoute.SPEAKER -> {
                runCatching {
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = true
                }
            }
            AudioRoute.WIRED, AudioRoute.EARPIECE -> {
                runCatching {
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = false
                }
            }
        }
    }

    fun releaseCommunicationMode() {
        val am = audioManager ?: return
        if (!isCommunicationModeAcquired) return
        isCommunicationModeAcquired = false

        runCatching {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        }
        runCatching {
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { req ->
                runCatching { am.abandonAudioFocusRequest(req) }
            }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(null) }
        }
    }
}

