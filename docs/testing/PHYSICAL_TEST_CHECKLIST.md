# RiderVoice Offline Mesh — Physical & Multi-Device Test Checklist

This checklist documents the required hardware-in-the-loop and multi-device field testing protocol for the RiderVoice Offline Mesh Intercom (`com.ridervoice.mesh`). 

Per the verification contract and rule R4, tests that have not been run on real Android hardware across physical RF conditions are explicitly tracked as **NOT EXECUTED**. Automated verification was executed solely via deterministic JVM unit tests in a simulated network and mocked audio environment.

---

## Physical Hardware Test Cases

| Test ID | Test Scenario | Hardware Setup | Verification Criteria | Status |
|---|---|---|---|---|
| **PHYS-01** | Multi-Device P2P Wi-Fi Direct Group Formation | 3x Physical Android devices (Android 11, 13, 14) | Automatic Wi-Fi Direct GO/Client negotiation, IP assignment, socket bind on port 45454 | **NOT EXECUTED** |
| **PHYS-02** | RF Range & Daisy-Hop Multi-Hop Relay | 3x Motorcycles in convoy spaced 150m apart (A <-> B <-> C) | Node A voice relayed via Node B to Node C; end-to-end latency < 150ms | **NOT EXECUTED** |
| **PHYS-03** | Helmet Bluetooth Intercom & PTT Keypress | Helmet headset (Cardo PackTalk / Sena 50S) paired via BT | SCO audio route switch, hardware PTT media button press transitions mic state | **NOT EXECUTED** |
| **PHYS-04** | Cellular Blackout Tunnel Entry (LiveKit -> Mesh) | Physical vehicle entering an RF shielded chamber / tunnel | LiveKit packet loss triggers automatic fallback to offline mesh within debounce window | **NOT EXECUTED** |
| **PHYS-05** | Cellular Recovery Tunnel Exit (Mesh -> LiveKit) | Physical vehicle exiting RF shielded chamber to 5G coverage | Independent HTTP probe detects cellular; LiveKit reconnects and claims audio | **NOT EXECUTED** |
| **PHYS-06** | Battery & Thermal Runaway on 2-Hour Ride | 2 physical phones running continuous mesh voice loop | Battery drain < 12%/hour, no thermal throttling or background process kill | **NOT EXECUTED** |
| **PHYS-07** | Wi-Fi / Bluetooth Radio Coexistence | Wi-Fi Direct active on 2.4GHz + BT SCO headset | No packet drops or SCO audio stutter under mutual RF interference | **NOT EXECUTED** |
| **PHYS-08** | Dynamic Convoy Re-ordering & Topology Mutation | 4 physical nodes changing relative order on road | Graph repository discovers topology changes, Dijkstra/BFS updates next-hop | **NOT EXECUTED** |

---

## Protocol for Future Physical Verification
When executing physical test runs:
1. Capture Wireshark pcap traces on port 45454 to verify wire framing and HMAC tags.
2. Record `MeshDiagnosticsSnapshot` logs via adb bugreport.
3. Measure round-trip audio latency using synchronized hardware clocks or acoustic chirp analysis.
4. Record actual physical device model numbers, Android OS versions, and RF environment metrics.
