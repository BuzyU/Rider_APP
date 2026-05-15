package com.ridervoice.audio

import android.content.Context
import android.media.AudioManager

class AudioDeviceManager(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE)
                as AudioManager

    fun enableBluetoothAudio() {
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    fun disableBluetoothAudio() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
    }
}
