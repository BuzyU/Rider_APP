package com.ridervoice.participants

class SpeakingDetector {

    fun isSpeaking(audioLevel: Float): Boolean {
        return audioLevel > 0.2f
    }
}
