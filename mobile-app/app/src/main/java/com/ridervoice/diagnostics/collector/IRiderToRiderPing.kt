package com.ridervoice.diagnostics.collector

/**
 * Interface for future rider-to-rider data channel RTT ping measurements.
 * TODO: Implement when LiveKit peer-to-peer data channel protocol is finalized.
 */
interface IRiderToRiderPing {
    fun sendPing(targetParticipantUid: String, timestamp: Long)
    fun onPongReceived(targetParticipantUid: String, rttMs: Double)
}
