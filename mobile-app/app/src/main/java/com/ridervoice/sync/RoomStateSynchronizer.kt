package com.ridervoice.sync

import com.ridervoice.models.Participant

class RoomStateSynchronizer {

    private val roomParticipants = mutableMapOf<String, Participant>()

    fun syncParticipant(participant: Participant) {
        roomParticipants[participant.identity] = participant
    }

    fun remove(identity: String) {
        roomParticipants.remove(identity)
    }

    fun getAll(): List<Participant> {
        return roomParticipants.values.toList()
    }
}
