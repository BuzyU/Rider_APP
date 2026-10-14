package com.ridervoice.diagnostics.models

import com.google.gson.annotations.SerializedName

data class SelfMetricSample(
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("deviceEpochMs") val deviceEpochMs: Long,
    @SerializedName("elapsedRealtimeMs") val elapsedRealtimeMs: Long,
    @SerializedName("rtt_ms") val rttMs: Double? = null,
    @SerializedName("packets_sent") val packetsSent: Long = 0,
    @SerializedName("packets_received") val packetsReceived: Long = 0,
    @SerializedName("packets_lost") val packetsLost: Long = 0,
    @SerializedName("retransmits") val retransmits: Long? = null,
    @SerializedName("bytes_sent") val bytesSent: Long = 0,
    @SerializedName("bytes_received") val bytesReceived: Long = 0,
    @SerializedName("bitrate_kbps") val bitrateKbps: Double? = null,
    @SerializedName("jitter_ms") val jitterMs: Double? = null,
    @SerializedName("network_type") val networkType: String = "UNKNOWN",
    @SerializedName("cell_operator") val cellOperator: String? = null,
    @SerializedName("cell_tech") val cellTech: String? = null,
    @SerializedName("signal_strength_dbm") val signalStrengthDbm: Int? = null,
    @SerializedName("battery_level") val batteryLevel: Float? = null,
    @SerializedName("thermal_status") val thermalStatus: String? = null,
    @SerializedName("is_foreground") val isForeground: Boolean? = null
)

data class InboundMetricSample(
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("deviceEpochMs") val deviceEpochMs: Long,
    @SerializedName("elapsedRealtimeMs") val elapsedRealtimeMs: Long,
    @SerializedName("remote_rider") val remoteRider: String,
    @SerializedName("track_sid") val trackSid: String? = null,
    @SerializedName("packets_received") val packetsReceived: Long = 0,
    @SerializedName("packets_lost") val packetsLost: Long = 0,
    @SerializedName("interval_loss_pct") val intervalLossPct: Double? = null,
    @SerializedName("jitter_ms") val jitterMs: Double? = null,
    @SerializedName("jitter_buffer_delay") val jitterBufferDelay: Double? = null,
    @SerializedName("concealed_samples") val concealedSamples: Long? = null,
    @SerializedName("concealment_events") val concealmentEvents: Long? = null,
    @SerializedName("audio_level") val audioLevel: Float? = null,
    @SerializedName("bytes_received") val bytesReceived: Long = 0
)

data class EventSample(
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("deviceEpochMs") val deviceEpochMs: Long,
    @SerializedName("elapsedRealtimeMs") val elapsedRealtimeMs: Long,
    @SerializedName("event_type") val eventType: String,
    @SerializedName("details") val details: String? = null
)

data class DiagnosticBatchPayload(
    @SerializedName("selfMetrics") val selfMetrics: List<SelfMetricSample> = emptyList(),
    @SerializedName("inboundMetrics") val inboundMetrics: List<InboundMetricSample> = emptyList(),
    @SerializedName("events") val events: List<EventSample> = emptyList()
)

data class DiagnosticBatchRequest(
    @SerializedName("runId") val runId: String,
    @SerializedName("sequenceNumber") val sequenceNumber: Int,
    @SerializedName("payload") val payload: DiagnosticBatchPayload
)

data class DiagnosticBatchResponse(
    @SerializedName("status") val status: String,
    @SerializedName("batchId") val batchId: String? = null,
    @SerializedName("sequenceNumber") val sequenceNumber: Int? = null
)

data class DiagnosticJoinRequest(
    @SerializedName("convoyId") val convoyId: String
)

data class DiagnosticJoinResponse(
    @SerializedName("runId") val runId: String,
    @SerializedName("convoyId") val convoyId: String,
    @SerializedName("convoyName") val convoyName: String,
    @SerializedName("testNumber") val testNumber: Int,
    @SerializedName("participantId") val participantId: String,
    @SerializedName("handle") val handle: String
)

data class DiagnosticStopRequest(
    @SerializedName("runId") val runId: String,
    @SerializedName("lastSeq") val lastSeq: Int
)

data class DiagnosticStopResponse(
    @SerializedName("status") val status: String,
    @SerializedName("runClosed") val runClosed: Boolean
)
