package com.ridervoice.diagnostics.models

/**
 * Parsed, immutable snapshot of real-time WebRTC network and voice-quality metrics.
 * Decoupled from LiveKit / WebRTC internal types so no WebRTC classes leak out of LiveKitManager.
 */
data class StatsSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val rttMs: Double?,
    val packetsSent: Long,
    val packetsReceived: Long,
    val packetsLost: Long,
    val jitterMs: Double?,
    val bytesSent: Long,
    val bytesReceived: Long,
    val outboundAudio: OutboundAudioStats?,
    val inboundRiders: List<InboundRiderStats>
)

data class OutboundAudioStats(
    val bytesSent: Long,
    val packetsSent: Long,
    val targetBitrate: Double?
)

data class InboundRiderStats(
    val riderIdentity: String,
    val trackSid: String?,
    val packetsReceived: Long,
    val packetsLost: Long,
    val jitterMs: Double?,
    val bytesReceived: Long,
    val jitterBufferDelay: Double? = null,
    val concealedSamples: Long? = null
)
