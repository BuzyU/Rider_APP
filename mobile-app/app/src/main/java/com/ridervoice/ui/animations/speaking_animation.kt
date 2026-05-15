package com.ridervoice.ui.animations

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable

@Composable
fun speakingScale(isSpeaking: Boolean): Float {

    val scale = animateFloatAsState(
        targetValue = if (isSpeaking) 1.2f else 1f,
        label = "voice_scale"
    )

    return scale.value
}
