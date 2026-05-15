package com.ridervoice.models

data class Participant(
    val identity: String,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false
)
