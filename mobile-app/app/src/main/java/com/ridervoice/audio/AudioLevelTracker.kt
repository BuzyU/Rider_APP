package com.ridervoice.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AudioLevelTracker {

    private val _audioLevel = MutableStateFlow(0f)

    val audioLevel: StateFlow<Float> = _audioLevel

    fun update(level: Float) {
        _audioLevel.value = level
    }
}
