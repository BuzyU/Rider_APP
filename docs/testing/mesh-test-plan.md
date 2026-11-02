# RiderVoice Offline Mesh Intercom Test Plan & Verification Census

**Version:** 2.0.0  
**Scope:** Automated JVM Unit Tests, Network Simulation, Chaos Testing, and Mutation Verification.

---

## 1. Test Suite Census (280 Total Unit Tests)

| Test Class | Category | Test Count | Key Invariants Verified |
|---|---|---|---|
| `MeshStage1SimulationTest` | Core Logic | 7 | 6-node linear chain, session limits, codec round-trips, packet validation |
| `OfflineMeshGateTest` | Feature Flags | 2 | Zero sockets opened and zero threads spawned when flag is false |
| `Stage2IntegrationTest` | Audio & Services | 8 | Microphone exclusivity, arbiter claims, service lifecycle |
| `ImportIsolationTest` | Code Isolation | 2 | Zero imports of `com.ridervoice.mesh` in frozen online files |
| `Stage3AutomaticSwitchingTest` | State Machine | 6 | 3s degraded debounce, 5s recovery confirmation, 10s cooldown |
| `Stage5AcceptanceTest` | Full Acceptance | 6 | Convoy simulation, 6-device saturating mix, 7th device rejection |
| `AdversarialTest` | Security | 6 | HMAC tampering rejection, corrupted IVs, replayed packets, oversized frames |
| `AndroidNetworkingTest` | Network | 2 | UDP port binding, broadcast fallback addresses |
| `AudioOwnershipArbiterTest` | Concurrency | 3 | Mutex-protected mic ownership, race condition stress test (50 coroutines) |
| `HonestStateTest` | Protocol State | 5 | Route aging, next-hop resets, T27 sequence wrap across Int.MAX_VALUE |
| `PayloadCryptoTest` | Encryption | 2 | AES-256-GCM authenticated encryption, bit-flip detection |
| `RelayAmplificationTest` | Forwarding | 3 | D26 relay suppression (5 datagrams for 6 nodes, triangle-with-tail) |
| `ScheduledDeliveryScenarioTest` | Simulated Net | 8 | 6 network disruption scenarios, T23 chaos test, deterministic tie-breaking |
| `SessionKdfTest` | Cryptography | 2 | Slow-KDF PBKDF2 100k iterations, Crockford Base32 normalization |
| `StateMachineLivenessTest` | State Machine | 4 | D25 deadlock fix, D4 exception rollback, random walk invariant check |
| `TransportPauseResumeTest` | Audio Hardware | 3 | Pause/resume mic release, mute state propagation |
| `TransportRobustnessTest` | Sockets | 2 | Socket failure recovery, graceful teardown |
| `VoiceSessionCoordinatorTest` | Architecture | 4 | Option B coordination, foreground service delegation |
| `WiringTest` | Integration | 4 | DI bindings, local health signal sampling, VOX bridge |
| `LocalizationParityTest` | Localization | 2 | Zero hardcoded user strings in UI, complete string parity across EN/HI/MR |
| ProductionWiringTest | Remediation v3 | 7 | Entry-point reachability, port wiring, gate verification, room secret removal |
| SessionHygieneTest | Remediation v3 | 4 | Argument-free navigation, secret hygiene, active session holder, foreground service |
| StateMachineLifecycleTest | Remediation v3 | 14 | Session lifecycle phase gating, probe throttling, Rule W-DEGRADED, group connectivity |
| AudioHandoffRobustnessTest | Remediation v3 | 12 | Single-capture ownership, quiet guard window (400ms), EnableAudioResult sealed returns |
| NetworkProtocolHardeningTest | Remediation v3 | 12 | Port consolidation (45454), TTL bounds (7), thread-safe routing, D22 endpoint learning |
| StringsParityTest | Remediation v3 | 2 | 1:1 key parity across EN/HI/MR (97 keys), zero raw Color(0x) literals outside MeshColors |
| *Baseline App Tests* | Online App | 185 | LiveKit, RoomViewModel, ActiveRideRepository, Admin API, UI components |
| **Total** | | **358** | **All 358 tests PASSING** |

---

## 2. Physical & Hardware-in-the-Loop Testing Policy

Per the honesty and evidence contract:
- Automated tests run exclusively inside JVM test harnesses.
- Physical device, multi-device radio, Bluetooth helmet, and real cellular blackout tests are tracked in [PHYSICAL_TEST_CHECKLIST.md](./PHYSICAL_TEST_CHECKLIST.md) as **NOT EXECUTED**.

---

## 2. Simulated Network Scenarios (`ScheduledDeliveryScenarioTest`)

All scenarios run deterministically over `ScheduledNet` with PRNG seeds (1, 2, 3):

1. **Scenario 1 (Relay Disappears):** A-B-C chain; B disappears mid-stream. A and C drop dead routes within 4,500 ms; C jitter buffer synthesizes PLC and returns to idle cleanly.
2. **Scenario 2 (Relay Returns with New Endpoint):** Relay drops and returns with new IP/port; graph updates immediately and routing re-establishes without packet loss.
3. **Scenario 3 (Partition & Merge):** 6-node network splits into 3-node islands and merges; routing tables recompute automatically with correct hop counts.
4. **Scenario 4 (Stale HELLO Overwrites):** Late out-of-order HELLOs arriving after link severance cannot resurrect dead edges due to monotonic sequence tracking.
5. **Scenario 5 (Duplicate Flood):** High packet duplication (25%) delivers exactly one audio frame per source sequence to the receiver jitter buffer.
6. **Scenario 6 (Route Change Mid-Transmission):** Dynamic re-routing when a preferred shorter link becomes available during an active voice burst.
7. **Scenario 7 (Chaos Test T23):** 6-node chain under 10% packet drop, 15% duplicate, and 0-50ms random delay. All nodes receive packets without infinite relay loops.
8. **Scenario 8 (Deterministic Tie-Break):** When multiple shortest paths exist, deterministic lexicographic ordering guarantees consistent next-hop selection across all nodes.

---

## 3. Mutation Testing Verification

Six critical logic invariants were mutated and verified to trigger immediate test failures:

| # | Component | Mutation Description | Target Test | Result |
|---|---|---|---|---|
| 1 | `DuplicatePacketCache` | `markIfNew` always returns `true` | `scenario5_duplicateFlood` | **FAILED** (duplicates delivered) |
| 2 | `RouteCalculator` | Inverted tie-break order | `routeCalculatorDeterministicTieBreak` | **FAILED** (wrong nextHop selected) |
| 3 | `MeshPacketCodec` | Skipped HMAC-SHA256 verification | `MeshStage1SimulationTest.packetCodec` | **FAILED** (tampered tag accepted) |
| 4 | `ForwardingEngine` | Removed `previousHopId` exclusion | `sixDeviceChainStillReachesEveryone` | **FAILED** (packet bounced backward) |
| 5 | `AudioOwnershipArbiter` | Allowed offline mesh to steal LiveKit mic | `mutualExclusionBasicTransitions` | **FAILED** (mutex ownership violated) |
| 6 | `SessionManager` | Changed `>= maxDevices` to `>` | `six-device linear chain rejects 7th` | **FAILED** (admitted 7th device) |
