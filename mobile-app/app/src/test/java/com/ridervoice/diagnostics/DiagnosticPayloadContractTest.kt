package com.ridervoice.diagnostics

import com.google.gson.Gson
import com.ridervoice.diagnostics.models.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticPayloadContractTest {

    private val gson = Gson()

    @Test
    fun `selfMetricSample serializes expected json fields matching contract`() {
        val self = SelfMetricSample(
            timestamp = 1700000000L,
            deviceEpochMs = 1700000000000L,
            elapsedRealtimeMs = 123456L,
            rttMs = 42.5,
            packetsSent = 100,
            packetsReceived = 95,
            packetsLost = 5,
            bytesSent = 20480,
            bytesReceived = 19500,
            jitterMs = 3.2,
            networkType = "WIFI",
            batteryLevel = 88.0f,
            thermalStatus = "NONE",
            isForeground = true
        )

        val jsonStr = gson.toJson(self)
        val json = JSONObject(jsonStr)

        assertEquals(1700000000L, json.getLong("timestamp"))
        assertEquals(1700000000000L, json.getLong("deviceEpochMs"))
        assertEquals(123456L, json.getLong("elapsedRealtimeMs"))
        assertEquals(42.5, json.getDouble("rtt_ms"), 0.001)
        assertEquals(100L, json.getLong("packets_sent"))
        assertEquals(95L, json.getLong("packets_received"))
        assertEquals(5L, json.getLong("packets_lost"))
        assertEquals(20480L, json.getLong("bytes_sent"))
        assertEquals(19500L, json.getLong("bytes_received"))
        assertEquals(3.2, json.getDouble("jitter_ms"), 0.001)
        assertEquals("WIFI", json.getString("network_type"))
        assertEquals(88.0, json.getDouble("battery_level"), 0.001)
        assertEquals("NONE", json.getString("thermal_status"))
        assertTrue(json.getBoolean("is_foreground"))
    }

    @Test
    fun `inboundMetricSample serializes expected json fields matching contract`() {
        val inbound = InboundMetricSample(
            timestamp = 1700000000L,
            deviceEpochMs = 1700000000000L,
            elapsedRealtimeMs = 123456L,
            remoteRider = "rider_alpha_123",
            trackSid = "TR_audio_abc",
            packetsReceived = 150,
            packetsLost = 2,
            intervalLossPct = 1.315,
            jitterMs = 4.1,
            bytesReceived = 30000
        )

        val jsonStr = gson.toJson(inbound)
        val json = JSONObject(jsonStr)

        assertEquals(1700000000L, json.getLong("timestamp"))
        assertEquals(1700000000000L, json.getLong("deviceEpochMs"))
        assertEquals(123456L, json.getLong("elapsedRealtimeMs"))
        assertEquals("rider_alpha_123", json.getString("remote_rider"))
        assertEquals("TR_audio_abc", json.getString("track_sid"))
        assertEquals(150L, json.getLong("packets_received"))
        assertEquals(2L, json.getLong("packets_lost"))
        assertEquals(1.315, json.getDouble("interval_loss_pct"), 0.001)
        assertEquals(4.1, json.getDouble("jitter_ms"), 0.001)
        assertEquals(30000L, json.getLong("bytes_received"))
    }

    @Test
    fun `eventSample serializes expected json fields matching contract`() {
        val event = EventSample(
            timestamp = 1700000000L,
            deviceEpochMs = 1700000000000L,
            elapsedRealtimeMs = 123456L,
            eventType = "NETWORK_HEALTH_CHANGED",
            details = "DEGRADED"
        )

        val jsonStr = gson.toJson(event)
        val json = JSONObject(jsonStr)

        assertEquals(1700000000L, json.getLong("timestamp"))
        assertEquals(1700000000000L, json.getLong("deviceEpochMs"))
        assertEquals(123456L, json.getLong("elapsedRealtimeMs"))
        assertEquals("NETWORK_HEALTH_CHANGED", json.getString("event_type"))
        assertEquals("DEGRADED", json.getString("details"))
    }

    @Test
    fun `batchRequest wrapper serializes runId sequenceNumber and payload`() {
        val batchReq = DiagnosticBatchRequest(
            runId = "run_uuid_456",
            sequenceNumber = 2,
            payload = DiagnosticBatchPayload(
                selfMetrics = listOf(
                    SelfMetricSample(
                        timestamp = 1700000000L,
                        deviceEpochMs = 1700000000000L,
                        elapsedRealtimeMs = 1000L
                    )
                ),
                inboundMetrics = emptyList(),
                events = listOf(
                    EventSample(
                        timestamp = 1700000000L,
                        deviceEpochMs = 1700000000000L,
                        elapsedRealtimeMs = 1000L,
                        eventType = "SESSION_STARTED"
                    )
                )
            )
        )

        val jsonStr = gson.toJson(batchReq)
        val json = JSONObject(jsonStr)

        assertEquals("run_uuid_456", json.getString("runId"))
        assertEquals(2, json.getInt("sequenceNumber"))
        assertTrue(json.has("payload"))

        val payloadObj = json.getJSONObject("payload")
        assertTrue(payloadObj.has("selfMetrics"))
        assertTrue(payloadObj.has("inboundMetrics"))
        assertTrue(payloadObj.has("events"))
        assertEquals(1, payloadObj.getJSONArray("selfMetrics").length())
        assertEquals(0, payloadObj.getJSONArray("inboundMetrics").length())
        assertEquals(1, payloadObj.getJSONArray("events").length())
    }
}
