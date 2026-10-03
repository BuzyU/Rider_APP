package com.ridervoice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null

    private val _hasFocus = MutableStateFlow(true)
    /** False after AUDIOFOCUS_LOSS / LOSS_TRANSIENT; true again on regain. */
    val hasFocus: StateFlow<Boolean> = _hasFocus

    /** Called on the main thread when focus is regained after a loss. */
    var onFocusRegained: (() -> Unit)? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Focus gained")
                val wasLost = !_hasFocus.value
                _hasFocus.value = true
                if (wasLost) onFocusRegained?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Focus lost (permanent) — LiveKit stays unmuted")
                _hasFocus.value = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Focus lost (transient)")
                _hasFocus.value = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Log.d(TAG, "Focus ducked")
        }
    }

    fun requestFocus() {
        if (focusRequest != null) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(req)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
            result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            focusRequest = req
            _hasFocus.value = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "Audio focus granted (result=$result)")
        } else {
            Log.e(TAG, "Audio focus DENIED (result=$result)")
        }
    }

    fun abandonFocus() {
        val req = focusRequest ?: return
        audioManager.abandonAudioFocusRequest(req)
        focusRequest = null
        _hasFocus.value = true
        Log.d(TAG, "Audio focus abandoned")
    }
}
