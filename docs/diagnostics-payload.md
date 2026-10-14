# RiderVoice Network Diagnostics Payload Specification

## 1. Overview
The Network Diagnostics subsystem records client-side WebRTC metrics, device network telemetry, and convoy voice quality events during active rides. Data is buffered locally in a dedicated SQLite database on the Android client and uploaded in discrete batches to `/api/diagnostics/batches`.

## 2. Batch Request Schema (`POST /api/diagnostics/batches`)

```json
{
  "runId": "uuid-string",
  "sequenceNumber": 1,
  "payload": {
    "selfMetrics": [ ... ],
    "inboundMetrics": [ ... ],
    "events": [ ... ]
  }
}
```

### Constraints:
- **Maximum Batch Size:** 5 MB HTTP payload limit (returns HTTP 413 if exceeded).
- **Maximum Total Rows:** 200 total samples across `selfMetrics`, `inboundMetrics`, and `events` per batch (returns HTTP 400 if exceeded).
- **Idempotency:** Unique index on `(runId, riderId, sequenceNumber)`. Repeated uploads of the same sequence number are acknowledged as `200 OK` without creating duplicate records.

---

## 3. Sample Schemas & Field Definitions

### A. `selfMetrics` (Outgoing & Local Device Telemetry)
Sampled periodically (default 2s interval) while diagnostics toggle is ON and room is connected.

| Field | Type | Nullable | Units / Format | Description |
|---|---|---|---|---|
| `timestamp` | Integer | No | Epoch ms | Client wall-clock timestamp (`System.currentTimeMillis()`) |
| `deviceEpochMs` | Integer | No | Epoch ms | Wall-clock timestamp when sample was gathered |
| `elapsedRealtimeMs` | Integer | No | ms | Monotonic time since boot (`SystemClock.elapsedRealtime()`) |
| `rtt_ms` | Float | **Yes** | milliseconds | Selected candidate-pair round-trip time (`currentRoundTripTime * 1000.0`). **Null if unavailable; NEVER 0.0**. Range: [0, 30000] |
| `packets_sent` | Integer | No | count | Cumulative WebRTC audio packets sent (`outbound-rtp.packetsSent`). Range: >= 0 |
| `packets_received` | Integer | No | count | Cumulative WebRTC audio packets received (`inbound-rtp.packetsReceived`). Range: >= 0 |
| `packets_lost` | Integer | No | count | Cumulative packets lost reported via RTCP receiver reports. Range: >= 0 |
| `retransmits` | Integer | Yes | count | Cumulative retransmitted packets (`outbound-rtp.retransmittedPacketsSent`). Range: >= 0 |
| `bytes_sent` | Integer | No | bytes | Cumulative bytes sent (`outbound-rtp.bytesSent`). Range: >= 0 |
| `bytes_received` | Integer | No | bytes | Cumulative bytes received (`inbound-rtp.bytesReceived`). Range: >= 0 |
| `bitrate_kbps` | Float | Yes | kbps | Outbound transmission bitrate calculated over the sampling interval. Range: >= 0 |
| `jitter_ms` | Float | Yes | milliseconds | WebRTC jitter buffer metric (`inbound-rtp.jitter * 1000.0`). Range: >= 0 |
| `network_type` | String | No | Enum | Active network transport: `WIFI`, `CELLULAR`, `ETHERNET`, `VPN`, `UNKNOWN`. Max 32 chars. |
| `cell_operator` | String | Yes | string | Carrier / Telephony operator name (e.g. `Verizon`, `Jio`). Max 64 chars. |
| `cell_tech` | String | Yes | string | Radio access technology: `LTE`, `5G_NR`, `HSPA`, `EDGE`, `UNKNOWN`. Max 32 chars. |
| `signal_strength_dbm`| Integer | Yes | dBm | Signal strength in dBm. Range: [-150, 0] |
| `battery_level` | Float | Yes | fraction / % | Battery charge fraction [0.0, 1.0] or percentage [0, 100]. |
| `thermal_status` | String | Yes | Enum | Device thermal state: `NONE`, `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`, `EMERGENCY`, `SHUTDOWN`. Max 32 chars. |
| `is_foreground` | Boolean | Yes | boolean | `true` if RiderVoice app UI is in the foreground; `false` if background service. |

---

### B. `inboundMetrics` (Per Remote Participant Audio Quality)
One sample per active remote track collected every sampling interval.

| Field | Type | Nullable | Units / Format | Description |
|---|---|---|---|---|
| `timestamp` | Integer | No | Epoch ms | Wall-clock timestamp |
| `deviceEpochMs` | Integer | No | Epoch ms | Wall-clock timestamp |
| `elapsedRealtimeMs` | Integer | No | ms | Monotonic time since boot |
| `remote_rider` | String | No | string | Remote participant LiveKit identity (`user.uid`). Max 128 chars. |
| `track_sid` | String | Yes | string | LiveKit track SID (e.g. `TR_...`). Max 64 chars. |
| `packets_received` | Integer | No | count | Cumulative inbound packets received for this remote track. Range: >= 0 |
| `packets_lost` | Integer | No | count | Raw cumulative packet loss reported by WebRTC. |
| `interval_loss_pct`| Float | Yes | percentage | Loss percentage calculated over previous sampling interval. Clamped to `>= 0.0%`. |
| `jitter_ms` | Float | Yes | milliseconds | Inbound jitter in milliseconds. Range: >= 0 |
| `jitter_buffer_delay`| Float | Yes | seconds / ms | WebRTC `jitterBufferDelay`. Range: >= 0 |
| `concealed_samples`| Integer | Yes | count | Cumulative audio concealment samples synthesized due to packet loss. Range: >= 0 |
| `concealment_events`| Integer | Yes | count | Audio concealment events. Range: >= 0 |
| `audio_level` | Float | Yes | fraction | Inbound audio volume energy level [0.0, 1.0]. |
| `bytes_received` | Integer | No | bytes | Cumulative bytes received for this track. Range: >= 0 |

---

### C. `events` (Network & Voice System Transitions)
Discrete operational events emitted upon state changes.

| Field | Type | Nullable | Units / Format | Description |
|---|---|---|---|---|
| `timestamp` | Integer | No | Epoch ms | Event timestamp |
| `deviceEpochMs` | Integer | No | Epoch ms | Wall-clock timestamp |
| `elapsedRealtimeMs` | Integer | No | ms | Monotonic time since boot |
| `event_type` | String | No | string | Event classification: `NETWORK_HEALTH`, `CONNECTION_QUALITY`, `PTT_PRESS`, `PTT_RELEASE`, `VOX_TRIGGER`, `RECONNECTING`, `RECONNECTED`, `DISCONNECTED`, `APP_STATE`. Max 64 chars. |
| `details` | String | Yes | string | Human-readable event metadata or state details. Max 256 chars. |

---

## 4. Cumulative Counters vs. Interval Deltas
- Client collector preserves cumulative counters (`packets_sent`, `packets_received`, `packets_lost`, `bytes_sent`, `bytes_received`, `concealed_samples`) as reported directly by WebRTC stats reports.
- Client collector computes interval loss percentage:
  $$\Delta \text{loss} = \frac{\Delta \text{packets\_lost}}{\Delta \text{packets\_received} + \Delta \text{packets\_lost}} \times 100\%$$
- If WebRTC reports negative delta loss (due to late-arriving packets or sequence rollover), `interval_loss_pct` is clamped to `0.0%`, while the raw cumulative counters are preserved unmodified.
- When an ICE restart or track reconnection occurs, counter resets are detected by the client and interval delta calculation resets cleanly.
