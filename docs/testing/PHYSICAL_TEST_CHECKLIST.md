# RiderVoice Offline Mesh — Physical & Multi-Device Test Checklist

> [!WARNING]
> Plan Stage 1 gate (≥ 2 physical devices) has not been met; Stage 2/3 code exists without it. No physical scenario has been executed.

This checklist documents the required hardware-in-the-loop and multi-device field testing protocol for the RiderVoice Offline Mesh Intercom (`com.ridervoice.mesh`). 

Per the verification contract and rule R4, tests that have not been run on real Android hardware across physical RF conditions are explicitly tracked as **NOT EXECUTED**. Automated verification was executed solely via deterministic JVM unit tests in a simulated network and mocked audio environment.

---

## Physical Hardware Test Cases

| Test ID | Test Scenario | Hardware Setup | Verification Criteria | Status |
|---|---|---|---|---|
| **PHYS-01** | Multi-Device P2P Group Formation (Wi-Fi Direct not supported in v1: v1 transport = UDP over a shared Wi-Fi/hotspot network) | 3x Physical Android devices (Android 11, 13, 14) | Subnet IP assignment, socket bind on port 45454 across shared hotspot | **NOT EXECUTED** |
| **PHYS-02** | RF Range & Daisy-Hop Multi-Hop Relay | 3x Motorcycles in convoy spaced 150m apart (A <-> B <-> C) | Node A voice relayed via Node B to Node C; end-to-end latency < 150ms | **NOT EXECUTED** |
| **PHYS-03** | Helmet Bluetooth Intercom & PTT Keypress | Helmet headset (Cardo PackTalk / Sena 50S) paired via BT | SCO audio route switch, hardware PTT media button press transitions mic state | **NOT EXECUTED** |
| **PHYS-04** | Cellular Blackout Tunnel Entry (LiveKit -> Mesh) | Physical vehicle entering an RF shielded chamber / tunnel | LiveKit packet loss triggers automatic fallback to offline mesh within debounce window | **NOT EXECUTED** |
| **PHYS-05** | Cellular Recovery Tunnel Exit (Mesh -> LiveKit) | Physical vehicle exiting RF shielded chamber to 5G coverage | Independent HTTP probe detects cellular; LiveKit reconnects and claims audio | **NOT EXECUTED** |
| **PHYS-06** | Battery & Thermal Runaway on 2-Hour Ride | 2 physical phones running continuous mesh voice loop | Battery drain < 12%/hour, no thermal throttling or background process kill | **NOT EXECUTED** |
| **PHYS-07** | Wi-Fi / Bluetooth Radio Coexistence (Wi-Fi Direct not supported in v1; tested via shared Wi-Fi + BT SCO) | Shared Wi-Fi hotspot active on 2.4GHz + BT SCO headset | No packet drops or SCO audio stutter under mutual RF interference | **NOT EXECUTED** |
| **PHYS-08** | Dynamic Convoy Re-ordering & Topology Mutation | 4 physical nodes changing relative order on road | Graph repository discovers topology changes, Dijkstra/BFS updates next-hop | **NOT EXECUTED** |
| **PHYS-09** | Dead Hotspot (Wi-Fi Up, No Internet) | Mobile Wi-Fi hotspot active without internet uplink | Total loss confirmed via missing NET_CAPABILITY_VALIDATED; falls back after 10 s | **NOT EXECUTED** |
| **PHYS-10** | Cellular Bars But No Data | Connected to cell tower with zero data throughput | Probe failure confirms total loss; triggers mesh fallback after 10 s debounce | **NOT EXECUTED** |
| **PHYS-11** | Internet Back While LiveKit Reconnecting | Internet restored while LiveKit WebRTC is reconnecting | Standing rule enforced: suspect timer resets; does NOT fall back to mesh | **NOT EXECUTED** |
| **PHYS-12** | Non-Disruptive Recovery Dead Air Check | Convoy exiting dead zone back into 5G coverage | Mesh audio playout continues while LiveKit reconnects; zero dead air gap | **NOT EXECUTED** |
| **PHYS-13** | Helmet PTT After Offline Session | Helmet headset PTT used online after offline session | PTT toggles mic state online without residual offline ownership lock | **NOT EXECUTED** |
| **PHYS-14** | VOX Hands-Free Over Mesh | Helmet microphone in high-noise cockpit environment | VOX auto-triggers mic capture; release hold timer prevents syllable clipping | **NOT EXECUTED** |
| **PHYS-15** | Screen Activity Rotation During Active Session | Orientation rotated during active offline HUD | Session and audio stream remain continuous without re-deriving session keys | **NOT EXECUTED** |
| **PHYS-16** | Android 14 Foreground Service Start Without Mic | App launched with RECORD_AUDIO permission revoked | Companion start() refuses startup; avoids foreground service security exception | **NOT EXECUTED** |
| **PHYS-17** | Peer Reboot Rejoin Within Report Timeout | One convoy phone rebooted and re-opened | Device rejoins mesh with new random epoch; graph accepts report within 8 s | **NOT EXECUTED** |
| **PHYS-18** | Hotspot-Host Phone Socket Binding | Phone acting as Wi-Fi hotspot AP host | bindSocketToWifi logs fallback gracefully; socket communicates unbound | **NOT EXECUTED** |
| **PHYS-19** | Redundant Relay Suppression Loss vs Airtime Benchmark | 6 physical phones in dense convoy comparing SUPPRESS_REDUNDANT_RELAYS true vs false | Frame packet loss <= 2.0% across all 6 nodes; measure Wi-Fi channel airtime and datagram reduction (>40% drop in datagram count when enabled on dense topologies) | **NOT EXECUTED** |

---

## Adb Diagnostic Commands for Field Testing

The debug broadcast receiver is guarded by `android.permission.DUMP` in debug builds:

```bash
# 1. Dump runtime mesh diagnostics (truncated session ID, packet counts, drop telemetry)
adb shell am broadcast -a com.ridervoice.mesh.DEBUG_DUMP_STATE

# 2. Inspect active UDP socket binding on port 45454
adb shell "netstat -uan | grep 45454"
# Or on modern kernels:
adb shell "ss -uan | grep 45454"

# 3. Verify Wi-Fi network interface address and broadcast configuration
adb shell ip addr show wlan0

# 4. Stream real-time mesh transport, audio engine, and link logs
adb logcat -v time -s MeshTransport:D UdpMeshLink:D ForwardingEngine:D MeshAudio:D

# 5. Set peer filter for partitioned mesh simulation
adb shell am broadcast -a com.ridervoice.debug.MESH_SET_PEER_FILTER --es allow "node-1,node-2"

# 6. Clear peer filter
adb shell am broadcast -a com.ridervoice.debug.MESH_SET_PEER_FILTER --es allow ""

# 7. Clear all in-memory offline sessions
adb shell am broadcast -a com.ridervoice.mesh.DEBUG_CLEAR_SESSION
```

---

## Field Log Template

When executing physical test runs in the field, record each observation using the template below (status of all tests remains **NOT EXECUTED** pending physical device availability):

```markdown
### Field Test Run Report
- **Date & Time:** YYYY-MM-DD HH:MM UTC
- **Location & Environment:** [e.g., Highway convoy / Mountain twisties / RF shielded basement]
- **Rider ID / Call-sign:** [e.g., Rider-Alpha]
- **Device Model:** [e.g., Google Pixel 7 (GQML3)]
- **Android Version & API Level:** [e.g., Android 14, API 34]
- **Hotspot Host:** [Self / Peer phone / Dedicated mobile Wi-Fi router]
- **AP Isolation:** [YES / NO / UNKNOWN] (Warning triggered if devices cannot see each other)
- **Local IPv4 Address:** [e.g., 192.168.43.45]
- **Subnet Broadcast Address:** [e.g., 192.168.43.255]
- **Authenticated Neighbours Heard (Last 10s):** [e.g., 3]
- **Observed Packet Loss (%):** [e.g., 2.4%]
- **Latency Measurement Method:** [Acoustic chirp / Hardware oscilloscope / Log timestamp difference]
- **Measured End-to-End Latency (ms):** [e.g., 85ms]
- **Audio Intelligibility & Quality:** [Clear / Jitter / Concealed / Stutter]
- **Anomalies / Failure Notes:** [e.g., None]
```

---

## Protocol for Future Physical Verification
When executing physical test runs:
1. Capture Wireshark pcap traces on port 45454 to verify wire framing and HMAC tags.
2. Record `MeshDiagnosticsSnapshot` logs via adb bugreport.
3. Measure round-trip audio latency using synchronized hardware clocks or acoustic chirp analysis.
4. Record actual physical device model numbers, Android OS versions, and RF environment metrics.
