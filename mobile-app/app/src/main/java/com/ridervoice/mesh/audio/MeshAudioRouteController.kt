package com.ridervoice.mesh.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.AudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshAudioRouteController @Inject constructor(
    private val port: AudioManagerPort,
    private val arbiter: AudioOwnershipArbiter
) {
    private val _activeRoute = MutableStateFlow(AudioRoute.SPEAKER)
    val activeRoute: StateFlow<AudioRoute> = _activeRoute.asStateFlow()

    private var isCommunicationModeAcquired = false
    private var priorMode: Int? = null
    private var priorSpeakerphoneOn: Boolean? = null

    fun acquireCommunicationMode() {
        if (arbiter.owner.value != AudioOwner.OFFLINE_MESH) return
        if (isCommunicationModeAcquired) return
        isCommunicationModeAcquired = true

        priorMode = port.mode
        priorSpeakerphoneOn = port.isSpeakerphoneOn

        port.requestCommunicationFocus()
        port.mode = AudioManager.MODE_IN_COMMUNICATION

        val defaultRoute = determineInitialRoute()
        setRoute(defaultRoute)
    }

    private fun determineInitialRoute(): AudioRoute {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = port.availableCommunicationDevices
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
            when {
                port.isBluetoothScoAvailableOffCall -> AudioRoute.BLUETOOTH
                port.isWiredHeadsetOn -> AudioRoute.WIRED
                else -> AudioRoute.SPEAKER
            }
        }
    }

    fun setRoute(route: AudioRoute) {
        _activeRoute.value = route
        if (arbiter.owner.value != AudioOwner.OFFLINE_MESH || !isCommunicationModeAcquired) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyModernRoute(route)
        } else {
            applyLegacyRoute(route)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun applyModernRoute(route: AudioRoute) {
        val devices = port.availableCommunicationDevices
        when (route) {
            AudioRoute.BLUETOOTH -> {
                val btDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
                }
                if (btDevice != null) {
                    port.setCommunicationDevice(btDevice)
                }
                port.isSpeakerphoneOn = false
                port.startBluetoothSco()
                port.isBluetoothScoOn = true
            }
            AudioRoute.SPEAKER -> {
                port.stopBluetoothSco()
                port.isBluetoothScoOn = false
                port.clearCommunicationDevice()
                val speakerDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    port.setCommunicationDevice(speakerDevice)
                }
                port.isSpeakerphoneOn = true
            }
            AudioRoute.WIRED -> {
                port.stopBluetoothSco()
                port.isBluetoothScoOn = false
                port.clearCommunicationDevice()
                val wiredDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (wiredDevice != null) {
                    port.setCommunicationDevice(wiredDevice)
                }
                port.isSpeakerphoneOn = false
            }
            AudioRoute.EARPIECE -> {
                port.stopBluetoothSco()
                port.isBluetoothScoOn = false
                port.clearCommunicationDevice()
                val earpieceDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (earpieceDevice != null) {
                    port.setCommunicationDevice(earpieceDevice)
                }
                port.isSpeakerphoneOn = false
            }
        }
    }

    private fun applyLegacyRoute(route: AudioRoute) {
        when (route) {
            AudioRoute.BLUETOOTH -> {
                port.isSpeakerphoneOn = false
                port.startBluetoothSco()
                port.isBluetoothScoOn = true
            }
            AudioRoute.SPEAKER -> {
                port.stopBluetoothSco()
                port.isBluetoothScoOn = false
                port.isSpeakerphoneOn = true
            }
            AudioRoute.WIRED, AudioRoute.EARPIECE -> {
                port.stopBluetoothSco()
                port.isBluetoothScoOn = false
                port.isSpeakerphoneOn = false
            }
        }
    }

    fun releaseCommunicationMode() {
        if (!isCommunicationModeAcquired) return
        isCommunicationModeAcquired = false

        if (arbiter.owner.value == AudioOwner.LIVEKIT) {
            // LiveKit already claimed audio ownership; do NOT tear down Bluetooth SCO,
            // communication device, or audio focus on top of the active online call.
            priorMode = null
            priorSpeakerphoneOn = null
            return
        }

        port.stopBluetoothSco()
        port.isBluetoothScoOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            port.clearCommunicationDevice()
        }

        priorSpeakerphoneOn?.let { port.isSpeakerphoneOn = it }
        priorMode?.let { port.mode = it }

        port.abandonCommunicationFocus()

        priorMode = null
        priorSpeakerphoneOn = null
    }
}
