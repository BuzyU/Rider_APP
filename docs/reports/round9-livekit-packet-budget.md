# RiderVoice Round 9: LiveKit (Online Voice) Packet Budget & Infrastructure Report

> **Document Status:** Complete (WP-99 Audit Report)  
> **Audited Target:** `com.ridervoice.network.LiveKitManager`, `com.ridervoice.audio.AudioProfile`, `livekit-server/livekit.yaml`  
> **Classification:** OWNER-GATED (Default: Measure and report only; no modifications applied to frozen paths)

---

## 1. Executive Summary & Audit Context

While the offline mesh transport (v4) operates peer-to-peer over local Wi-Fi without server mediation, RiderVoice's online voice intercom relies on a centralized LiveKit Selective Forwarding Unit (SFU) utilizing WebRTC.

Under Round 9, an in-depth audit of the online voice stack was conducted to evaluate packet sizes, airtime, cellular data utilization, and latency under weak cellular coverage (e.g., 2G/3G/fringe 4G common along Indian highways).

### Key Findings Summary:
1. **VO-01 (HIGH/MED):** `dtx = false` is hardcoded across all audio routes in `AudioProfile.kt`. In `LiveKitManager.kt`, mic mute is implemented solely via `localAudioTrack.enabled = false`. In standard WebRTC, disabling a track without DTX causes the Opus encoder to continuously emit silence packets at **50 packets per second (≈ 54 kbit/s uplink)** even when the rider is completely silent and the VOX gate is closed.
2. **VO-02 (MED):** Transport overheads (RTP, SRTP, UDP, IPv4) dominate small voice packets, consuming **54 bytes** of fixed overhead per frame. Reducing Opus bitrate from 32 kbit/s to 24 kbit/s yields useful payload savings (~25%), but enabling DTX yields transformative savings (~95% reduction in packet count during idle periods).
3. **VO-03 (MED):** PING/PONG heartbeats in `LiveKitManager.kt` invoke `publishData(payload)` using default arguments (`Reliability.RELIABLE`, `identities = null`). This broadcasts heartbeats to all room participants over a reliable SCTP data channel, resulting in $O(N^2)$ delivery overhead and risking head-of-line blocking for critical messages (such as SOS alerts).
4. **VO-04 / VO-05 (HIGH):** The server configuration (`livekit-server/livekit.yaml`) lacks TURN servers, specifies a wide UDP port range (50000–60000), and has no regional routing. Indian mobile carriers (notably Reliance Jio and Bharti Airtel) employ strict symmetric Carrier-Grade NAT (CGNAT) that frequently blocks direct UDP/STUN binding, forcing a disastrous fallback to TCP (port 7881) or TLS (port 443). TCP fallback introduces jitter buffer inflation and catastrophic latency spikes.
5. **VO-06 / VO-07 (LOW/INFO):** Hardcoded development credentials exist in default server YAML; `BT_SCO` runs at an unnecessarily high 28 kbit/s for narrowband/mSBC Bluetooth SCO; `VoxEngine` dual-recorder architecture was inspected to ensure no microphone contention.

---

## 2. On-the-Wire Packet Budget Breakdown

WebRTC encapsulates voice audio into RTP over SRTP over UDP over IPv4. The exact protocol layering per 20 ms frame is:

| Layer | Header / Field | Size (Bytes) | Notes |
|---|---|---|---|
| **IPv4** | IP Header | 20 | Standard IPv4 header without options |
| **UDP** | UDP Header | 8 | Source port, dest port, length, checksum |
| **SRTP** | RTP Header | 12 | V, P, X, CC, M, PT, Seq (2B), Timestamp (4B), SSRC (4B) |
| **SRTP** | Header Extensions | ~4 | RFC 6464 Audio Level / abs-send-time (one-byte header format) |
| **SRTP** | Authentication Tag | 10 | HMAC-SHA1 80-bit auth tag (standard WebRTC crypto suite) |
| **Total Transport Overhead** | — | **54 B** | Fixed overhead per packet |

### Payload Size vs. Bitrate Comparison:

| Audio Route | Configured Bitrate | Opus Payload (20 ms) | On-Wire Packet Size | IP Bandwidth (Uplink) | Bandwidth per Remote Rider (Downlink) |
|---|---|---|---|---|---|
| **PHONE (Current)** | 32 kbit/s | 80 B | **134 B** | 53.6 kbit/s | 53.6 kbit/s |
| **WIRED (Current)** | 32 kbit/s | 80 B | **134 B** | 53.6 kbit/s | 53.6 kbit/s |
| **BT_SCO (Current)** | 28 kbit/s | 70 B | **124 B** | 49.6 kbit/s | 49.6 kbit/s |
| **BT_LE (Current)** | 32 kbit/s | 80 B | **134 B** | 53.6 kbit/s | 53.6 kbit/s |
| **PHONE (Proposed)** | 24 kbit/s | 60 B | **114 B** | 45.6 kbit/s | 45.6 kbit/s |
| **WIRED (Proposed)** | 24 kbit/s | 60 B | **114 B** | 45.6 kbit/s | 45.6 kbit/s |
| **BT_SCO (Proposed)** | 20 kbit/s | 50 B | **104 B** | 41.6 kbit/s | 41.6 kbit/s |
| **DTX Idle / Silence** | ~400 ms tick | ~12 B (CN) | **66 B** | **~1.3 kbit/s** | **~1.3 kbit/s** |

### Analysis of DTX Savings:
In a motorcycle intercom session, riders typically speak during less than 15% of the total riding duration. 
- **Without DTX (`dtx = false`):** An idle session with 6 riders transmits 50 packets/s up and 250 packets/s down continuously, consuming **~320 kbit/s of continuous cellular data** (approx 144 MB per hour per device).
- **With DTX (`dtx = true`):** When silence is detected, WebRTC transmits an initial 2-3 silence descriptor frames, then drops transmission to 1 Comfort Noise frame every 400 ms (2.5 packets/s). Total cellular data during silence drops from 144 MB/hour to **~3.5 MB/hour**, eliminating battery drain, thermal throttling, and cellular airtime congestion.

---

## 3. Detailed Audit Findings & Architectural Assessments

### VO-01: DTX Inactive and Track Disabling Behavior
- **Location:** `mobile-app/app/src/main/java/com/ridervoice/audio/AudioProfile.kt` (lines 31, 41, 51, 61), `com.ridervoice.network.LiveKitManager.kt` (lines 500–511).
- **Issue:** `AudioProfile` specifies `dtx = false` for all routes. In `LiveKitManager.applyMic()`, muting or VOX gate closure executes `localAudioTrack?.enabled = want`.
- **Impact:** WebRTC's `AudioTrack.setEnabled(false)` instructs the track to mute audio input; however, because the track remains active in the peer connection and DTX is disabled, the WebRTC native audio engine encodes silence PCM into full-rate Opus frames and transmits them across the network.
- **Verification Requirement:** Must be verified on physical hardware using WebRTC `getStats()` (PHYS-22). When the VOX gate is closed, `outbound-rtp.bytesSent` must stop increasing at 50 packets/s.

### VO-02: Transport Header Overhead Dominance
- **Location:** Computed wire analysis.
- **Issue:** Fixed transport headers consume 54 bytes (40.3% of a 134-byte packet).
- **Impact:** Adjusting Opus bitrates between 24 kbit/s and 32 kbit/s only alters the payload by 20 bytes (134 B vs 114 B, a 15% saving). Therefore, lowering bitrate alone cannot resolve bandwidth constraints on congested cell towers. DTX is the necessary mechanism to alleviate channel contention.

### VO-03: PING/PONG SCTP Head-of-Line Blocking and Broadcast Amplification
- **Location:** `mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt` (lines 1150, 1183).
- **Issue:** 
  ```kotlin
  room?.localParticipant?.publishData(pingPayload)
  // ...
  room?.localParticipant?.publishData(pongPayload)
  ```
- **Impact:** In the LiveKit Kotlin SDK, calling `publishData` without arguments defaults to:
  1. `reliability = Reliability.RELIABLE`: Transmitted over SCTP with acknowledgment and retransmission. If a packet drops on weak cell coverage, all subsequent data channel messages are blocked in queue.
  2. `identities = null`: Transmitted to **all** participants. In an $N$-person convoy, each 10s ping interval generates $N \times (N - 1)$ broadcast deliveries, plus $N \times (N - 1)$ broadcast PONG responses.
- **Remediation:** PING and PONG should use `reliability = Reliability.LOSSY` (unordered, non-retransmitted UDP-like datagram) and explicit unicast addressing: `identities = listOf(Participant.Identity(peerId))`.

### VO-04 / VO-05: Server Infrastructure, CGNAT, and TURN Fallback
- **Location:** `livekit-server/livekit.yaml`
- **Issue:**
  ```yaml
  rtc:
    tcp_port: 7881
    port_range_start: 50000
    port_range_end: 60000
    stun_servers:
      - stun:stun.l.google.com:19302
  ```
- **Impact:**
  - Indian mobile networks (Jio, Airtel, Vi) utilize strict symmetric NAT for cellular subscribers. Under symmetric NAT, the public mapping changes for every destination endpoint, preventing direct STUN peer-to-peer / SFU binding.
  - Without a TURN server configured on standard UDP port 3478 or TLS port 443, WebRTC fails ICE UDP checks and falls back to TCP via port 7881 or TLS via port 443.
  - **TCP Fallback Penalty:** When packets drop on cellular handovers, TCP's congestion control stalls playback, forcing jitter buffers to swell to 500–1,500 ms and causing intolerable voice lag.
- **Remediation:**
  1. Enable LiveKit's embedded TURN server on UDP port 3478 and TLS port 443.
  2. Configure a single UDP multiplexing port (`udp_port: 7882`) rather than opening a 10,000-port firewall range.
  3. Deploy the SFU in Mumbai (`ap-south-1` on AWS or `asia-south1` on GCP) to minimize edge RTT for Indian riders (< 30 ms).

### VO-06: Production Credential Hygiene & Bluetooth SCO Bitrate
- **Location:** `livekit-server/livekit.yaml` (lines 12–13), `AudioProfile.kt` (line 30).
- **Issue:** `livekit.yaml` contains `devkey: secret`. `BT_SCO` bitrate is configured for 28 kbit/s.
- **Impact:** Standard Bluetooth Hands-Free Profile (HFP) SCO uses either narrowband CVSD (8 kHz audio, ~64 kbit/s uncompressed) or wideband mSBC (16 kHz audio, ~64 kbit/s uncompressed). Allocating 28 kbit/s for Opus over an 8/16 kHz SCO link is excessive and does not improve voice fidelity over the physical Bluetooth link. 20 kbit/s provides identical speech quality on SCO while saving bandwidth.

### VO-07: Dual AudioRecord Verification in VoxEngine
- **Location:** `mobile-app/app/src/main/java/com/ridervoice/audio/VoxEngine.kt` (lines 267–290).
- **Issue:** `VoxEngine` includes fallback logic that starts its own `AudioRecord` if ADM (Audio Device Module) frames cease arriving.
- **Inspection Result:** Line 284 confirms:
  ```kotlin
  if (newPath == "adm") {
      stopOwnAudioRecord()
  } else if (isRunning) {
      startOwnAudioRecord()
  }
  ```
  `stopOwnAudioRecord()` is actively invoked when ADM frames are streaming. Two audio recording instances are not active simultaneously during steady-state operation. Verification on physical hardware (PHYS-22) is required to guarantee no hardware HAL recording conflicts occur across differing OEM chipsets (e.g., MediaTek vs Snapdragon).

---

## 4. Proposed Source Modifications (Pending Owner Unfreeze)

> [!IMPORTANT]
> The files below are **FROZEN** under the Round 9 contract. The diffs below are proposed for implementation when the repository owner provides explicit unfreeze authorization.

### Patch A: `mobile-app/app/src/main/java/com/ridervoice/audio/AudioProfile.kt`
```diff
--- a/mobile-app/app/src/main/java/com/ridervoice/audio/AudioProfile.kt
+++ b/mobile-app/app/src/main/java/com/ridervoice/audio/AudioProfile.kt
@@ -28,8 +28,8 @@ data class AudioProfile(
                     echoCancellation = false,
                     autoGain = true,
                     highPassHz = 130f,
-                    bitrate = 28000,
-                    dtx = false,
+                    bitrate = 20000,
+                    dtx = false,
                     voxOpenRatio = (3.2f + ratioDelta).coerceIn(2.0f, 5.0f)
                 )
                 RouteType.BT_LE -> AudioProfile(
@@ -48,8 +48,8 @@ data class AudioProfile(
                     echoCancellation = true,
                     autoGain = false,
                     highPassHz = 100f,
-                    bitrate = 32000,
-                    dtx = false,
+                    bitrate = 24000,
+                    dtx = true,
                     voxOpenRatio = (2.8f + ratioDelta).coerceIn(2.0f, 5.0f)
                 )
                 RouteType.PHONE -> AudioProfile(
@@ -58,8 +58,8 @@ data class AudioProfile(
                     echoCancellation = true,
                     autoGain = true,
                     highPassHz = 100f,
-                    bitrate = 32000,
-                    dtx = false,
+                    bitrate = 24000,
+                    dtx = true,
                     voxOpenRatio = (3.0f + ratioDelta).coerceIn(2.0f, 5.0f)
                 )
```

### Patch B: `mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt`
```diff
--- a/mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt
+++ b/mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt
@@ -1147,7 +1147,11 @@ class LiveKitManager {
                         put("to", peerId)
                     }.toString().toByteArray(Charsets.UTF_8)
                     try {
-                        room?.localParticipant?.publishData(pingPayload)
+                        room?.localParticipant?.publishData(
+                            data = pingPayload,
+                            reliability = Reliability.LOSSY,
+                            identities = listOf(Participant.Identity(peerId))
+                        )
                     } catch (e: Exception) {
                         Log.w(TAG, "Ping publish error: ${e.message}")
                     }
@@ -1180,7 +1184,11 @@ class LiveKitManager {
                 }.toString().toByteArray(Charsets.UTF_8)
                 scope.launch {
                     try {
-                        room?.localParticipant?.publishData(pongPayload)
+                        room?.localParticipant?.publishData(
+                            data = pongPayload,
+                            reliability = Reliability.LOSSY,
+                            identities = listOf(Participant.Identity(from))
+                        )
                     } catch (e: Exception) {
                         Log.w(TAG, "Pong publish error: ${e.message}")
                     }
```

### Patch C: `livekit-server/livekit.yaml`
```diff
--- a/livekit-server/livekit.yaml
+++ b/livekit-server/livekit.yaml
@@ -1,9 +1,14 @@
 port: 7880
 rtc:
   tcp_port: 7881
-  port_range_start: 50000
-  port_range_end: 60000
+  udp_port: 7882
   use_external_ip: true
+  enable_turn: true
+  turn_port: 3478
   stun_servers:
     - stun:stun.l.google.com:19302
 room:
@@ -11,4 +16,5 @@ room:
   empty_timeout: 600
 keys:
-  devkey: secret
+  # Production keys injected via LIVEKIT_API_KEY and LIVEKIT_API_SECRET env variables
```

---

## 5. Field Measurement & Telemetry Protocol (PHYS-22)

To validate online voice performance and confirm that DTX and UDP transport are operational, test engineers must execute the following procedures:

### A. WebRTC Statistics Inspection via Chrome Internals
When running in Android debug mode with WebRTC inspection enabled:
1. Connect device via USB debugging.
2. Open `chrome://inspect/#devices` on workstation Chrome.
3. Open `chrome://webrtc-internals` and locate the active `RTCPeerConnection`.
4. Inspect `outbound-rtp (audio)`:
   - **Target Metric 1:** `bytesSent` and `packetsSent` rate when the VOX gate is OPEN should match configured bitrate (~6.0 kB/s at 24 kbit/s).
   - **Target Metric 2:** When VOX gate CLOSES, `bytesSent` increment rate must drop to < 150 B/s (1 comfort-noise packet every 400 ms).
5. Inspect `candidate-pair (selected)`:
   - **Target Metric 3:** `localCandidateType` and `remoteCandidateType` must both be `udp` (or `relay` with UDP transport). If `protocol` is `tcp`, CGNAT fallback has occurred.

### B. Adb Shell Diagnostic Commands
```bash
# 1. Inspect active UDP and TCP sockets used by RiderVoice
adb shell "netstat -uanp | grep ridervoice"
adb shell "ss -uanp | grep ridervoice"

# 2. Monitor logcat for WebRTC ICE candidate selection and DTX transitions
adb logcat -v time -s LiveKit:D WebRTC:D AudioDeviceRouter:D VoxEngine:D
```

---

## 6. Latency and Honesty Assessment (Section A6 Notice)

> **Honesty Notice:**  
> Smaller packets reduce cellular airtime, frame loss, and data consumption. However, **long-distance online voice latency is dominated by the Round-Trip Time (RTT) to the SFU server, not by packet size** (an 80-byte payload vs. a 134-byte packet changes transmission delay by fractions of a millisecond on 4G/5G).  
>
> Therefore, no claim of online latency reduction is made without empirical verification on physical hardware via PHYS-22. Real-world latency improvements online are achieved primarily through:
> 1. Geographical co-location of the SFU in the region of the riders (e.g., Mumbai SFU for Maharashtra riders reducing base RTT from ~200 ms to < 25 ms).
> 2. Eliminating TCP fallback through proper TURN UDP configuration.
> 3. Eliminating SCTP head-of-line blocking by switching heartbeat telemetry to lossy data channels.
