package com.ridervoice.audio

import android.content.Context
import android.media.AudioManager

class AudioConfigurator(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE)
                as AudioManager

    fun configureVoiceMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
    }
}
