package com.ridervoice.participants

import com.ridervoice.models.Participant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParticipantManager @Inject constructor() {

    private val map = ConcurrentHashMap<String, Participant>()
    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    fun updateParticipants(newParticipants: List<Participant>) {
        map.clear()
        newParticipants.forEach { map[it.identity] = it }
        _participants.value = map.values.toList()
    }

    fun addParticipant(p: Participant): Boolean {
        map[p.identity] = p
        _participants.value = map.values.toList()
        return true
    }

    fun removeParticipant(identity: String): Boolean {
        val removed = map.remove(identity) != null
        if (removed) {
            _participants.value = map.values.toList()
        }
        return removed
    }

    fun getParticipant(identity: String): Participant? = map[identity]

    fun getParticipants(): List<Participant> = _participants.value

    fun clear() {
        map.clear()
        _participants.value = emptyList()
    }
}
