# RiderVoice Offline Mesh v2.0: Audit Facts & Baseline Verification

Generated: 2026-11-01
Repository: BuzyU/Rider_APP
Branch: fix/mesh-audit-r3 (branched from main at 4a25125)

---

## 1. Frozen Path Isolation Verification (Fact A)

Frozen paths checked:
- mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt
- mobile-app/app/src/main/java/com/ridervoice/ui/viewmodels/RoomViewModel.kt
- mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt
- mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt
- mobile-app/app/src/main/java/com/ridervoice/audio/
- mobile-app/app/src/main/java/com/ridervoice/room/
- backend/

### git diff --stat against Base Commit a37ef09 (HEAD before mesh fix series):
```text
(empty - 0 files changed, 0 insertions, 0 deletions)
```
Verdict: 0 lines of diff against a37ef09.

### git diff --stat against Baseline Commit de53299 (Pre-Mesh commit):
```text
backend/.eslintrc.json | 13 +++++++++++++
 backend/.gitignore     |  1 +
 2 files changed, 14 insertions(+)
```
Verdict: Only the two backend CI lint configuration files added in commit a37ef09 differ.

---

## 2. JUnit XML Test Census from testDebugUnitTest (Fact B)

Execution command: `./gradlew testDebugUnitTest`
Result: BUILD SUCCESSFUL
XML Files Analyzed: 48
Total Unit Tests: 281
Total Failures: 0
Total Errors: 0
Total Skipped: 0

### Per-Class Test Breakdown:

| Test Class | Tests | Failures | Errors | Skipped | Time (s) |
|---|---|---|---|---|---|
| `com.ridervoice.audio.AudioDeviceRouterTest` | 4 | 0 | 0 | 0 | 0.132 |
| `com.ridervoice.audio.AudioRouteDecisionEngineTest` | 4 | 0 | 0 | 0 | 0.003 |
| `com.ridervoice.audio.AudioSettingsApplierTest` | 1 | 0 | 0 | 0 | 1.815 |
| `com.ridervoice.audio.VoxCaptureEvaluatorTest` | 7 | 0 | 0 | 0 | 0.002 |
| `com.ridervoice.audio.VoxDetectorTest` | 5 | 0 | 0 | 0 | 0.147 |
| `com.ridervoice.audio.VoxTuningHarnessTest` | 5 | 0 | 0 | 0 | 0.027 |
| `com.ridervoice.diagnostics.BatchBuilderTest` | 3 | 0 | 0 | 0 | 0.088 |
| `com.ridervoice.diagnostics.DeltaLossCalculatorTest` | 6 | 0 | 0 | 0 | 0.002 |
| `com.ridervoice.diagnostics.DiagnosticPayloadContractTest` | 4 | 0 | 0 | 0 | 0.02 |
| `com.ridervoice.diagnostics.DiagnosticsGatingTest` | 5 | 0 | 0 | 0 | 0.004 |
| `com.ridervoice.localization.LocalizationParityTest` | 3 | 0 | 0 | 0 | 0.144 |
| `com.ridervoice.localization.StringResourceParityTest` | 1 | 0 | 0 | 0 | 0.045 |
| `com.ridervoice.mesh.ImportIsolationTest` | 3 | 0 | 0 | 0 | 0.086 |
| `com.ridervoice.mesh.MeshStage1SimulationTest` | 8 | 0 | 0 | 0 | 0.904 |
| `com.ridervoice.mesh.OfflineMeshGateTest` | 8 | 0 | 0 | 0 | 0.002 |
| `com.ridervoice.mesh.Stage2IntegrationTest` | 2 | 0 | 0 | 0 | 0.176 |
| `com.ridervoice.mesh.Stage3AutomaticSwitchingTest` | 6 | 0 | 0 | 0 | 0.018 |
| `com.ridervoice.mesh.Stage5AcceptanceTest` | 6 | 0 | 0 | 0 | 0.194 |
| `com.ridervoice.mesh.audit.AdversarialTest` | 7 | 0 | 0 | 0 | 0.817 |
| `com.ridervoice.mesh.audit.AndroidNetworkingTest` | 4 | 0 | 0 | 0 | 2.058 |
| `com.ridervoice.mesh.audit.AudioOwnershipArbiterTest` | 3 | 0 | 0 | 0 | 0.051 |
| `com.ridervoice.mesh.audit.HonestStateTest` | 5 | 0 | 0 | 0 | 0.818 |
| `com.ridervoice.mesh.audit.PayloadCryptoTest` | 7 | 0 | 0 | 0 | 0.054 |
| `com.ridervoice.mesh.audit.RelayAmplificationTest` | 3 | 0 | 0 | 0 | 0.31 |
| `com.ridervoice.mesh.audit.ScheduledDeliveryScenarioTest` | 8 | 0 | 0 | 0 | 3.044 |
| `com.ridervoice.mesh.audit.SessionKdfTest` | 6 | 0 | 0 | 0 | 0.004 |
| `com.ridervoice.mesh.audit.StateMachineLivenessTest` | 4 | 0 | 0 | 0 | 0.012 |
| `com.ridervoice.mesh.audit.TransportPauseResumeTest` | 2 | 0 | 0 | 0 | 0.107 |
| `com.ridervoice.mesh.audit.TransportRobustnessTest` | 2 | 0 | 0 | 0 | 0.206 |
| `com.ridervoice.mesh.audit.VoiceSessionCoordinatorTest` | 5 | 0 | 0 | 0 | 0.016 |
| `com.ridervoice.mesh.audit.WiringTest` | 5 | 0 | 0 | 0 | 0.128 |
| `com.ridervoice.models.LobbyStatusSerializationTest` | 2 | 0 | 0 | 0 | 0.004 |
| `com.ridervoice.navigation.DeepLinkHolderTest` | 6 | 0 | 0 | 0 | 0.0 |
| `com.ridervoice.network.RoomIdentityVerificationTest` | 18 | 0 | 0 | 0 | 1.366 |
| `com.ridervoice.services.FcmParsingTest` | 3 | 0 | 0 | 0 | 0.0 |
| `com.ridervoice.services.LocationPolicyTest` | 5 | 0 | 0 | 0 | 0.002 |
| `com.ridervoice.state.ActiveRideRepositoryTest` | 9 | 0 | 0 | 0 | 0.021 |
| `com.ridervoice.ui.RoomLobbyLayoutTest` | 5 | 0 | 0 | 0 | 0.011 |
| `com.ridervoice.ui.components.ParticipantNameResolverTest` | 5 | 0 | 0 | 0 | 0.003 |
| `com.ridervoice.ui.components.UiMessageMapperTest` | 10 | 0 | 0 | 0 | 0.002 |
| `com.ridervoice.ui.viewmodels.HostSetupViewModelTest` | 5 | 0 | 0 | 0 | 0.02 |
| `com.ridervoice.ui.viewmodels.LobbyViewModelTest` | 9 | 0 | 0 | 0 | 0.06 |
| `com.ridervoice.ui.viewmodels.SosViewModelTest` | 3 | 0 | 0 | 0 | 0.015 |
| `com.ridervoice.update.AppVersionTest` | 22 | 0 | 0 | 0 | 0.002 |
| `com.ridervoice.update.UpdateManagerIntegrityTest` | 3 | 0 | 0 | 0 | 1.01 |
| `com.ridervoice.utils.EmergencyNumbersTest` | 7 | 0 | 0 | 0 | 0.019 |
| `com.ridervoice.utils.JoinGuardsTest` | 11 | 0 | 0 | 0 | 0.007 |
| `com.ridervoice.utils.JwtRoomClaimDecoderTest` | 16 | 0 | 0 | 0 | 0.003 |

---

## 3. Git Grep Usage Proofs (Fact C)

### A. `git grep -n OFFLINE_MESH_ENABLED`
```text
README.md:14:- **Offline Mesh Intercom Engine v2.0 (v0.0.3.20)** â€“ Zero-infrastructure peer-to-peer ad-hoc Wi-Fi/UDP multi-hop voice mesh for motorcycle convoys outside cellular coverage. Includes a 9-state deterministic `AutomaticSwitchStateMachine` with recovery deadlock prevention (D25) for seamless transitions between LiveKit WebRTC and local mesh, daisy-hop packet forwarding with duplicate suppression (cache + bloom filter) and TTL decay, route-aware relay pruning to suppress broadcast storms (D26), Audio HAL mutual-exclusion via `AudioOwnershipArbiter` preventing hardware lock contention, Slow-KDF PBKDF2/AES-256-GCM authenticated payload encryption with sliding-window replay rejection (D8, D19), battery-efficient adaptive heartbeats, and dedicated diagnostics HUD. *(Note: `FeatureFlags.OFFLINE_MESH_ENABLED = false` by default in release builds).*
RIDERVOICE_SYSTEM_EXPLANATION.md:368:*(Note: Feature flag `FeatureFlags.OFFLINE_MESH_ENABLED = false` by default in release builds).*
mobile-app/app/src/main/java/com/ridervoice/mesh/FeatureFlags.kt:11:    const val OFFLINE_MESH_ENABLED: Boolean = false
mobile-app/app/src/main/java/com/ridervoice/mesh/OfflineMeshGate.kt:14:        buildFlag: Boolean = FeatureFlags.OFFLINE_MESH_ENABLED,
mobile-app/app/src/main/java/com/ridervoice/mesh/OfflineMeshGate.kt:20:        buildFlag: Boolean = FeatureFlags.OFFLINE_MESH_ENABLED,
mobile-app/app/src/main/java/com/ridervoice/mesh/OfflineMeshGate.kt:27:        buildFlag: Boolean = FeatureFlags.OFFLINE_MESH_ENABLED,
mobile-app/app/src/test/java/com/ridervoice/mesh/OfflineMeshGateTest.kt:34:        assertFalse(FeatureFlags.OFFLINE_MESH_ENABLED)
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage5AcceptanceTest.kt:335:        assertFalse("Build flag must be false by default", FeatureFlags.OFFLINE_MESH_ENABLED)
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/WiringTest.kt:49:        assertFalse("Build flag must be off in test", FeatureFlags.OFFLINE_MESH_ENABLED)
```

### B. `git grep -n AutomaticSwitchStateMachine`
```text
ARCHITECTURE.md:86:        Handoff["AutomaticSwitchStateMachine\n(cellular â†” offline fallback)"]
ARCHITECTURE.md:240:        Handoff["AutomaticSwitchStateMachine\n(cellular â†” offline fallback)"]
ARCHITECTURE.md:381:| **Offline mesh voice intercom** | `OfflineMeshVoiceTransport` and `AutomaticSwitchStateMachine` provide zero-infrastructure ad-hoc Wi-Fi/UDP multi-hop voice mesh with mutual-exclusion Audio HAL management (`AudioOwnershipArbiter`) when internet connectivity drops |
CODE_DEPTH_STRUCTURE.md:59:â”‚           â”‚   â”œâ”€â”€ switching/            AutomaticSwitchStateMachine (9 states) & health signals
CODE_DEPTH_STRUCTURE.md:236:    Fallback[AutomaticSwitchStateMachine / OfflineMeshVoiceTransport]
CODE_DEPTH_STRUCTURE.md:249:- `mesh/switching/AutomaticSwitchStateMachine.kt`: 9-state deterministic finite state machine governing automatic transitions between LiveKit WebRTC and local offline mesh.
CODE_DEPTH_STRUCTURE.md:307:  - `AutomaticSwitchStateMachine.kt`: 9-state deterministic finite state machine (`ONLINE_LIVEKIT_ACTIVE`, `STARTING_OFFLINE_MESH`, `OFFLINE_ACTIVE`, `HANDING_BACK_TO_LIVEKIT`, etc.) managing failover and recovery. Includes recovery deadlock prevention (D25) by tracking Internet health even when LiveKit was disconnected by handoff.
README.md:14:- **Offline Mesh Intercom Engine v2.0 (v0.0.3.20)** â€“ Zero-infrastructure peer-to-peer ad-hoc Wi-Fi/UDP multi-hop voice mesh for motorcycle convoys outside cellular coverage. Includes a 9-state deterministic `AutomaticSwitchStateMachine` with recovery deadlock prevention (D25) for seamless transitions between LiveKit WebRTC and local mesh, daisy-hop packet forwarding with duplicate suppression (cache + bloom filter) and TTL decay, route-aware relay pruning to suppress broadcast storms (D26), Audio HAL mutual-exclusion via `AudioOwnershipArbiter` preventing hardware lock contention, Slow-KDF PBKDF2/AES-256-GCM authenticated payload encryption with sliding-window replay rejection (D8, D19), battery-efficient adaptive heartbeats, and dedicated diagnostics HUD. *(Note: `FeatureFlags.OFFLINE_MESH_ENABLED = false` by default in release builds).*
RELEASE_NOTES.md:22:- **9-State Deterministic State Machine (`AutomaticSwitchStateMachine`)**: Implemented complete state lifecycle with debounced handoff/handback triggers.
RIDERVOICE_SYSTEM_EXPLANATION.md:365:4. **Deterministic 9-State Switching & Deadlock Prevention (D25):** The `AutomaticSwitchStateMachine` evaluates network health (`LocalHealthSignals`) across debounced windows. Crucially, when cellular coverage drops and LiveKit is disconnected, the state machine continues evaluating independent network reachability signals so the convoy seamlessly hands back to LiveKit when connectivity returns.
RIDERVOICE_SYSTEM_EXPLANATION.md:428:- [AutomaticSwitchStateMachine.kt](./mobile-app/app/src/main/java/com/ridervoice/mesh/switching/AutomaticSwitchStateMachine.kt): Deterministic 9-state finite state machine orchestrating automatic handoff between LiveKit WebRTC and offline mesh.
RIDERVOICE_SYSTEM_EXPLANATION.md:502:  - `AutomaticSwitchStateMachine.kt`: 9-state automatic handoff machine with recovery deadlock fix (D25).
docs/SETTINGS_CONTRACT.md:24:| **Auto Fallback to Mesh** | `offline_mesh_auto_fallback` | `true` | `true`, `false` | `AutomaticSwitchStateMachine`, `SettingsViewModel` | **Live** | `offline_mesh_enabled == true` | **No** (Preserved across logout) | `AutomaticSwitchStateMachineTest` |
docs/implementation/mesh/README.md:42:â”‚   â”œâ”€â”€ AutomaticSwitchStateMachine.kt # 9-state finite state machine governing automatic failover between LiveKit and Mesh
docs/implementation/mesh/README.md:108:4. **State Machine:** `AutomaticSwitchStateMachine` runs evaluation loops within structured coroutine scopes, emitting state updates via immutable `StateFlow`.
docs/implementation/mesh/state-machine.md:3:**Target:** `com.ridervoice.mesh.switching.AutomaticSwitchStateMachine`  
mobile-app/app/src/main/java/com/ridervoice/mesh/switching/AutomaticSwitchStateMachine.kt:20:class AutomaticSwitchStateMachine(
mobile-app/app/src/main/java/com/ridervoice/mesh/switching/LocalHealthSignalsProvider.kt:31: * Drives AutomaticSwitchStateMachine once per second while active.
mobile-app/app/src/main/java/com/ridervoice/mesh/switching/LocalHealthSignalsProvider.kt:88:        machine: AutomaticSwitchStateMachine,
mobile-app/app/src/main/java/com/ridervoice/mesh/switching/LocalHealthSignalsProvider.kt:111:        ): AutomaticSwitchStateMachine = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage3AutomaticSwitchingTest.kt:5:import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage3AutomaticSwitchingTest.kt:57:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage3AutomaticSwitchingTest.kt:82:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage3AutomaticSwitchingTest.kt:105:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage3AutomaticSwitchingTest.kt:127:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage3AutomaticSwitchingTest.kt:169:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage3AutomaticSwitchingTest.kt:202:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage5AcceptanceTest.kt:24:import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage5AcceptanceTest.kt:219:        val stateMachine = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage5AcceptanceTest.kt:272:        val stateMachine = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/StateMachineLivenessTest.kt:5:import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/StateMachineLivenessTest.kt:21:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/StateMachineLivenessTest.kt:45:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/StateMachineLivenessTest.kt:66:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/StateMachineLivenessTest.kt:96:        val sm = AutomaticSwitchStateMachine(
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/WiringTest.kt:13:import com.ridervoice.mesh.switching.AutomaticSwitchStateMachine
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/WiringTest.kt:151:        val machine = AutomaticSwitchStateMachine(
```

### C. `git grep -n LocalHealthSignalsProvider`
```text
docs/implementation/mesh/README.md:44:â”‚   â””â”€â”€ LocalHealthSignalsProvider.kt  # Polling and reactive sensor sampling health signals for state machine
mobile-app/app/src/main/java/com/ridervoice/mesh/switching/LocalHealthSignalsProvider.kt:33:class LocalHealthSignalsProvider(
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/WiringTest.kt:15:import com.ridervoice.mesh.switching.LocalHealthSignalsProvider
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/WiringTest.kt:90:     * Verify LocalHealthSignalsProvider accurately samples local health conditions.
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/WiringTest.kt:99:        val provider = LocalHealthSignalsProvider(
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/WiringTest.kt:136:     * Verify LocalHealthSignalsProvider driving loop evaluates machine while active and ends session when ride ends.
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/WiringTest.kt:145:        val provider = LocalHealthSignalsProvider(
```

### D. `git grep -n VoiceSessionCoordinator`
```text
docs/implementation/mesh/README.md:49:â”‚   â”œâ”€â”€ VoiceSessionCoordinator.kt     # Option B coordinator handling switching between LiveKit and Offline Mesh
docs/testing/mesh-test-plan.md:29:| `VoiceSessionCoordinatorTest` | Architecture | 4 | Option B coordination, foreground service delegation |
mobile-app/app/src/main/java/com/ridervoice/di/MeshModule.kt:14:import com.ridervoice.mesh.transport.VoiceSessionCoordinator
mobile-app/app/src/main/java/com/ridervoice/di/MeshModule.kt:74:    fun provideVoiceSessionCoordinator(
mobile-app/app/src/main/java/com/ridervoice/di/MeshModule.kt:78:    ): VoiceSessionCoordinator = VoiceSessionCoordinator(
mobile-app/app/src/main/java/com/ridervoice/mesh/switching/LocalHealthSignalsProvider.kt:4:import com.ridervoice.mesh.transport.VoiceSessionCoordinator
mobile-app/app/src/main/java/com/ridervoice/mesh/switching/LocalHealthSignalsProvider.kt:108:            coordinator: VoiceSessionCoordinator,
mobile-app/app/src/main/java/com/ridervoice/mesh/transport/VoiceSessionCoordinator.kt:27:class VoiceSessionCoordinator(
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage2IntegrationTest.kt:13:import com.ridervoice.mesh.transport.VoiceSessionCoordinator
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage2IntegrationTest.kt:32: * - VoiceSessionCoordinator atomic transport handoff
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage2IntegrationTest.kt:85:        val coordinator = VoiceSessionCoordinator(mockLiveKit, mockMesh, arbiter)
mobile-app/app/src/test/java/com/ridervoice/mesh/Stage5AcceptanceTest.kt:30:import com.ridervoice.mesh.transport.VoiceSessionCoordinator
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/VoiceSessionCoordinatorTest.kt:12:import com.ridervoice.mesh.transport.VoiceSessionCoordinator
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/VoiceSessionCoordinatorTest.kt:27:class VoiceSessionCoordinatorTest {
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/VoiceSessionCoordinatorTest.kt:119:        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/VoiceSessionCoordinatorTest.kt:139:        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/VoiceSessionCoordinatorTest.kt:167:        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/VoiceSessionCoordinatorTest.kt:187:        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)
mobile-app/app/src/test/java/com/ridervoice/mesh/audit/VoiceSessionCoordinatorTest.kt:210:        val coordinator = VoiceSessionCoordinator(liveKit, mesh, arbiter)
```

### Key Fact Analysis from Grep:
1. `FeatureFlags.OFFLINE_MESH_ENABLED`: Defined as `const val OFFLINE_MESH_ENABLED: Boolean = false`.
2. `AutomaticSwitchStateMachine` and `LocalHealthSignalsProvider`: Implemented in `mesh/switching/`, exercised in unit tests, but zero callers exist in production application wiring outside the switching package itself.
3. `VoiceSessionCoordinator`: Provided in `MeshModule.kt`, but never injected into application activities, viewmodels, or services.
4. `NavGraph.kt`: Currently constructs its own standalone `OfflineMeshVoiceTransport` and `AudioOwnershipArbiter` directly, bypassing the Hilt singletons.

---

## 4. Ground Truth Protocol Constants in Code (Fact D)

Extracted directly from source code in `MeshPacket.kt`, `MeshConfig.kt`, `SessionKdf.kt`, and `OfflineSession.kt`:

| Constant / Parameter | Source File | Exact Value in Code | Notes |
|---|---|---|---|
| Magic Bytes | MeshPacketCodec.MAGIC | `0x52564D31` (`"RVM1"`) | 4-byte integer (0x52, 0x56, 0x4D, 0x31) |
| Protocol Version | MeshPacketCodec.VERSION | `2` | 1-byte value 0x02 |
| Packet Header Length | MeshPacketCodec.HEADER_LEN | `75` bytes | 4+1+1+16+16+4+8+1+1+16+1+4+2 bytes |
| Hop Tag Length | MeshPacketCodec.TAG_LEN | `16` bytes | HMAC-SHA256 truncated to 16 bytes |
| Encrypted Voice Payload | MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES | `656` bytes | 640 bytes PCM (20ms @ 16kHz mono) + 16 bytes GCM tag |
| Total Voice Datagram Size | Wire layout | `747` bytes | 75 (header) + 656 (voice payload) + 16 (hop tag) |
| Max Payload Size | MeshConfig.MAX_PAYLOAD_BYTES | `1,200` bytes | Sanity upper bound |
| Audio UDP Port | MeshConfig.AUDIO_PORT | `45454` | Audio datagram socket port |
| Discovery UDP Port | MeshConfig.DISCOVERY_PORT | `45455` | UNUSED in production runtime (OfflineMeshVoiceTransport uses AUDIO_PORT 45454 for both audio and discovery/HELLO) |
| Default Voice TTL | MeshConfig.DEFAULT_TTL | `5` | Up to 6 hops (covers max 6 devices chain) |
| HELLO Discovery TTL | MeshConfig.HELLO_TTL | `4` | Discovery announcements radius |
| Max Devices Cap | MeshConfig.MAX_DEVICES | `6` | Group capacity |
| Redundant Relay Pruning | MeshConfig.SUPPRESS_REDUNDANT_RELAYS | `true` | Suppresses redundant broadcast relays |
| KDF Algorithm | SessionKdf | `PBKDF2WithHmacSHA256` | Salt: `"rvmesh/v2/session"` |
| KDF Iterations | SessionKdf.DEFAULT_ITERATIONS | `120,000` | PBKDF2 (CPU-hard, not memory-hard) key derivation |
| Derived Key Output | SessionKdf.derive | `80` bytes | 16B Session UUID + 32B HMAC Key + 32B AES Key |
| Pairing Code Alphabet | OfflineSession.ALPHABET | `0123456789ABCDEFGHJKMNPQRSTVWXYZ` | 32 Crockford Base32 symbols (no I, L, O, U) |
| Pairing Code Length | OfflineSession.CODE_LENGTH | `16` characters | 80 bits of entropy (formatted `XXXX-XXXX-XXXX-XXXX`) |
| Duplicate Cache TTL | MeshConfig.DUP_CACHE_TTL_MS | `125,000` ms (125 s) | 2 * MAX_CLOCK_SKEW_MS (60s) + 5s buffer |
| Duplicate Cache Max | MeshConfig.DUP_CACHE_MAX_ENTRIES | `32,000` entries | Size bound |
| State Machine States | AutomaticSwitchState | `8` states | `ONLINE`, `ONLINE_SUSPECT`, `STARTING_OFFLINE_MESH`, `OFFLINE_ACTIVE`, `ONLINE_RECOVERING`, `HANDING_BACK_TO_LIVEKIT`, `FALLBACK_UNAVAILABLE`, `SESSION_ENDED` |
