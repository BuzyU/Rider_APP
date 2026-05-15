package com.ridervoice.sync

import com.ridervoice.models.Participant

class ParticipantStateSync {

    fun updateSpeaking(
        participant: Participant,
        speaking: Boolean
    ): Participant {

        return participant.copy(
            isSpeaking = speaking
        )
    }
}
