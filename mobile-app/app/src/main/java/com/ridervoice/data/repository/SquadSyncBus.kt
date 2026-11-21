package com.ridervoice.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class SquadSyncEvent {
    object IncomingRequestReceived : SquadSyncEvent()
    object RequestAccepted : SquadSyncEvent()
    data class RequestRejected(val handleOrId: String? = null) : SquadSyncEvent()
    object SquadChanged : SquadSyncEvent()
}

/**
 * Application-wide reactive event bus for Squad membership and request updates
 * triggered by FCM push notifications or cross-screen mutations.
 */
@Singleton
class SquadSyncBus @Inject constructor() {
    private val _events = MutableSharedFlow<SquadSyncEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SquadSyncEvent> = _events.asSharedFlow()

    fun emit(event: SquadSyncEvent) {
        _events.tryEmit(event)
    }
}
