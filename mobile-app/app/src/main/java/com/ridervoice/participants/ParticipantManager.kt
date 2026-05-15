package com.ridervoice.participants

import com.ridervoice.models.Participant

class ParticipantManager {

    private val participants = mutableListOf<Participant>()

    fun addParticipant(participant: Participant) {
        participants.add(participant)
    }

    fun removeParticipant(identity: String) {
        participants.removeAll {
            it.identity == identity
        }
    }

    fun getParticipants(): List<Participant> {
        return participants
    }
}
