package com.ridervoice.mesh.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

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

