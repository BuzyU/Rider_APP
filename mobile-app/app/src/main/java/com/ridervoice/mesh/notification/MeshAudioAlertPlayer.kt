package com.ridervoice.mesh.notification

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

interface MeshAudioCuePlayer {
    fun playCue(event: MeshStatusEvent, spokenText: String, isDeafened: Boolean)
    fun release() {}
}

object NoOpMeshAudioCuePlayer : MeshAudioCuePlayer {
    override fun playCue(event: MeshStatusEvent, spokenText: String, isDeafened: Boolean) {}
}

class AndroidMeshAudioCuePlayer(
    private val context: Context
) : MeshAudioCuePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var offlineTtsReady: Boolean = false

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext ?: context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    val langRes = engine.setLanguage(Locale.getDefault())
                    val voice = engine.voice
                    val supportsOffline = langRes >= TextToSpeech.LANG_AVAILABLE &&
                        voice != null && !voice.isNetworkConnectionRequired
                    offlineTtsReady = supportsOffline
                }
            }
        }
    }

    override fun playCue(event: MeshStatusEvent, spokenText: String, isDeafened: Boolean) {
        if (isDeafened) return
        val toneType = when (event) {
            MeshStatusEvent.WIFI_OFF, MeshStatusEvent.LINK_FAILED -> ToneGenerator.TONE_PROP_NACK
            MeshStatusEvent.OFFLINE_ACTIVE -> ToneGenerator.TONE_PROP_BEEP2
            MeshStatusEvent.ONLINE_RECOVERED -> ToneGenerator.TONE_PROP_ACK
            MeshStatusEvent.STARTING_OFFLINE, MeshStatusEvent.STARTING_ONLINE -> ToneGenerator.TONE_PROP_BEEP
        }
        val toneMs = if (event == MeshStatusEvent.WIFI_OFF) 400 else 220
        val engine = tts
        if (offlineTtsReady && engine != null) {
            val spoke = runCatching {
                val bundle = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                }
                engine.speak(spokenText, TextToSpeech.QUEUE_FLUSH, bundle, "rv_cue_${event.name}") == TextToSpeech.SUCCESS
            }.getOrDefault(false)
            if (spoke) return
        }
        playVoiceStreamTone(toneType, toneMs)
    }

    private fun playVoiceStreamTone(toneType: Int, durationMs: Int) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            tg.startTone(toneType, durationMs)
            scope.launch {
                delay(durationMs.toLong() + 80L)
                runCatching { tg.release() }
            }
        }
    }

    override fun release() {
        runCatching { scope.cancel() }
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        offlineTtsReady = false
    }
}
