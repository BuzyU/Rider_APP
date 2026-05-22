package com.ridervoice.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
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

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs

    private var lastEventTime = 0L
    private val DEBOUNCE_MS = 300L // 300ms debounce for ghost presses

    // Optional callback for LiveKit integration
    var onMicToggleRequest: ((Boolean) -> Unit)? = null

    fun activateSession() {
        if (mediaSession == null) {
            mediaSession = MediaSessionCompat(context, "HardwarePTTManager")
            mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent != null && keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        
                        // Debounce filtering
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
                        return true // Consume event
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
        }

        // Set state to Playing so OS sends events here
        val state = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        
        mediaSession?.setPlaybackState(state)
        mediaSession?.isActive = true
        logDebug("MediaSession: ACTIVATED (Taking focus)")
    }

    fun deactivateSession() {
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
        _debugLogs.value = (_debugLogs.value + newLog).takeLast(20) // Keep last 20 logs
    }
}
