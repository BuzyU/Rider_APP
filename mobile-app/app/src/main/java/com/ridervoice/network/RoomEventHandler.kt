package com.ridervoice.network

import io.livekit.android.events.RoomEvent

class RoomEventHandler {

    fun handle(event: RoomEvent) {

        when (event) {

            is RoomEvent.ParticipantConnected -> {
                println("Participant connected")
            }

            is RoomEvent.ParticipantDisconnected -> {
                println("Participant disconnected")
            }

            else -> {
                println("Other event")
            }
        }
    }
}
