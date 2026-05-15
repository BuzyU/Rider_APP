package com.ridervoice.network

import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.connectOptions
import io.livekit.android.room.roomOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LiveKitManager {

    private var room: Room? = null

    fun connect(
        url: String,
        token: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {

            room = LiveKit.create(
                appContext = null
            )

            room?.connect(
                url = url,
                token = token,
                options = roomOptions {
                    adaptiveStream = true
                    dynacast = true
                }
            )

            room?.localParticipant?.setMicrophoneEnabled(true)
        }
    }

    fun disconnect() {
        room?.disconnect()
    }

    fun mute(muted: Boolean) {
        room?.localParticipant?.setMicrophoneEnabled(!muted)
    }
}
