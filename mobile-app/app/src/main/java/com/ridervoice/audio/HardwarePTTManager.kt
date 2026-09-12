package com.ridervoice.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HardwarePTTManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaSession: MediaSessionCompat? = null
    private var isMicOpen = false
    private var focusRequest: android.media.AudioFocusRequest? = null
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs

    private var lastEventTime = 0L
    private val DEBOUNCE_MS = 300L

    var onMicToggleRequest: ((Boolean) -> Unit)? = null

    fun activateSession() {
        if (mediaSession?.isActive == true) {
            logDebug("MediaSession: Already active — ignoring duplicate activateSession()")
            return
        }

        if (mediaSession == null) {
            mediaSession = MediaSessionCompat(context, "HardwarePTTManager")
            mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    }
                    if (keyEvent != null && (keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK)) {

                        val now = System.currentTimeMillis()
                        if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                            if (now - lastEventTime > DEBOUNCE_MS) {
                                lastEventTime = now
                                handlePttToggle()
                                logDebug("AVRCP: KEYCODE_MEDIA_PLAY_PAUSE (ACTION_DOWN) - Accepted")
                            } else {
                                logDebug("AVRCP: KEYCODE_MEDIA_PLAY_PAUSE (ACTION_DOWN) - DEBOUNCED GHOST")
                            }
                        }
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
        }

        val state = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        mediaSession?.setPlaybackState(state)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }

        mediaSession?.isActive = true
        logDebug("MediaSession: ACTIVATED (Taking focus)")
    }

    fun deactivateSession() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }

        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        logDebug("MediaSession: DEACTIVATED (Releasing to Spotify/Apple Music)")
    }

    fun isSessionActive(): Boolean {
        return mediaSession?.isActive == true
    }

    private fun handlePttToggle() {
        isMicOpen = !isMicOpen
        onMicToggleRequest?.invoke(isMicOpen)
        logDebug("Mic State Toggled: $isMicOpen")
    }

    private fun logDebug(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date())
        val newLog = "[$timestamp] $message"
        _debugLogs.value = (_debugLogs.value + newLog).takeLast(20)
    }
}
