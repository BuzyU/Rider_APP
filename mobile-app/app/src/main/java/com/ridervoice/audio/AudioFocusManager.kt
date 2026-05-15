package com.ridervoice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

class AudioFocusManager(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE)
                as AudioManager

    fun requestFocus() {

        val focusRequest = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN
        )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
            )
            .build()

        audioManager.requestAudioFocus(focusRequest)
    }
}
