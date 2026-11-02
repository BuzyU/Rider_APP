# Rider_APP Offline Mesh — Plan Conformance Audit & Master Remediation Plan

- **Repository:** `BuzyU/Rider_APP`, branch `main`, HEAD `12b13e5`
- **Compared Against:** `rider-app-offline-mesh-three-stage-plan.md`
- **Verified Local Build & Test Baseline at `12b13e5`:**
  - `./gradlew testDebugUnitTest`: **PASS** (`290` tests across `49` test suites, `0` failures, `0` errors, `0` skipped)
  - `./gradlew assembleDebug`: **PASS**
  - `./gradlew lintDebug`: **PASS** (`0` errors, `1` informational baseline entry)
  - Frozen online files diff (`git diff de53299 HEAD` over frozen paths): **0 lines changed** in Android frozen files; only `backend/.eslintrc.json` and `backend/.gitignore` changed in commit `a37ef09` for CI lint.

All Android source paths below are relative to `mobile-app/app/src/main/java/com/ridervoice/` unless a full repository path is given. All unit test paths are relative to `mobile-app/app/src/test/java/com/ridervoice/`.

---

## 1. Executive Verdict

- **Stage 1 (Daisy-Hop Mesh Core): Implemented and verified in simulation.** Identity, session, 75-byte packet codec, HMAC-SHA256 + AES-256-GCM crypto, topology graph, BFS routing, TTL, duplicate cache, 18-step forwarding pipeline, multi-source jitter buffer with PLC, and a 6-node scheduled simulation all exist and pass unit tests. However, `MeshConfig.debugPeerFilter` is never read in production code, so multi-hop cannot be forced on a shared subnet during physical testing.
- **Stage 2 (Online/Offline Integration): Classes exist, but disconnected from the real online ride.** `AudioOwnershipArbiter`, `LiveKitVoiceTransport`, `OfflineMeshVoiceTransport`, `VoiceSessionCoordinator`, and `OfflineRoomScreen` exist, but the online ride connects via frozen `state/RoomViewModel.kt` -> `network/LiveKitManager.kt` directly without notifying `AudioOwnershipArbiter`. Consequently, `AudioOwner` remains `NONE` during an active online ride, allowing dual microphone capture.
- **Stage 3 (Automatic Switching): Written and unit-tested in isolation, but 100% unreachable in production.** Nothing in production instantiates or starts `AutomaticSwitchStateMachine`, `LocalHealthSignalsProvider`, or injects `VoiceSessionCoordinator`. Toggling "Automatic fallback" in `SettingsScreen.kt` writes a SharedPreferences boolean (`auto_fallback_enabled`) that nothing in the running app ever acts upon.
- **Stage 4 (Backend Continuity): Client stub only** (`mesh/continuity/*`, `BACKEND_CONTINUITY_ENABLED = false`). Acceptable per plan (optional, default-off).
- **Stage 5 (Acceptance & Release Readiness): Simulated only.** No `androidTest` directory exists, `debugPeerFilter` is unwired, UDP sockets are not bound to the Wi-Fi `Network`, and GitHub Actions CI does not run `lintDebug`, `assembleRelease` R8 verification, or frozen-file guards.
- **Documentation & Specs: Severe drift / hallucinations.** `docs/protocol/mesh-v2.md`, `docs/implementation/mesh/README.md`, `docs/implementation/mesh/state-machine.md`, `docs/security/mesh-threat-model.md`, `docs/SETTINGS_CONTRACT.md`, `README.md`, `ARCHITECTURE.md`, `CODE_DEPTH_STRUCTURE.md`, and `RIDERVOICE_SYSTEM_EXPLANATION.md` contain claims that contradict the code (wrong ports `40001`/`4242`, wrong magic `0x52564D32`, wrong header sizes `55`/`73`, wrong tag size `32`, non-existent HKDF, non-existent Bloom filter, non-existent 9-state machine, non-existent source files).

---

## 2. Plan Conformance Matrix (`rider-app-offline-mesh-three-stage-plan.md`)

| Plan Section | Status | Exact Code Evidence & Gap |
| :--- | :--- | :--- |
| **Flags: build flag + runtime setting, default off** | **Done** | `mobile-app/app/build.gradle:118,127,130` (`OFFLINE_MESH_BUILD_ENABLED`: `true` in `debug`, `false` in `defaultConfig` & `release`); `mesh/FeatureFlags.kt:17-39`; `mesh/OfflineMeshGate.kt:8-34`; `mesh/MeshRuntimeSettings.kt:5-25` (all default `false`). |
| **Flag-off & import-isolation tests** | **Done (needs hardening)** | `mesh/ImportIsolationTest.kt:13-78`, `mesh/OfflineMeshGateTest.kt`, `mesh/audit/WiringTest.kt:47-149`. Note: `ImportIsolationTest.kt:55` uses `if (f.exists())` instead of `assertTrue(f.exists())`. |
| **Stage 0: Baseline & frozen files** | **Done** | Frozen Android files have 0 diff against `de53299` and `a37ef09`. |
| **1.1 Stable device identity** | **Done (display name bug)** | `mesh/identity/MeshIdentity.kt:10-68`. Persists UUID in `mesh_identity_prefs`. Gap **M8**: `di/MeshModule.kt:49` passes `Build.MODEL ?: "Rider"` and never syncs the user's rider callsign/displayName. |
| **1.2 Standalone offline session** | **Done (UI thread KDF bug)** | `mesh/session/OfflineSession.kt:13-138`, `mesh/session/SessionKdf.kt:17-39`. 16-char Crockford Base32 code (80 bits), PBKDF2-HMAC-SHA256 (120,000 iterations), 6-device cap, 8h expiry. Gap **M1**: KDF runs on UI thread. |
| **1.3 `MeshLink` abstraction** | **Done** | `mesh/link/MeshLink.kt:9-23`, `mesh/link/UdpMeshLink.kt:31-152`. |
| **1.4 Discovery heartbeat** | **Partial (port mismatch)** | `mesh/discovery/HelloCodec.kt`, `ForwardingEngine.kt:144-192`. Gap **D1**: `MeshConfig.DISCOVERY_PORT = 45455` (`MeshConfig.kt:5`) is never bound; `UdpMeshLink.discoverBroadcastEndpoints` (`UdpMeshLink.kt:135`) defaults to `DISCOVERY_PORT` while `OfflineMeshVoiceTransport.kt:55,62` uses `AUDIO_PORT` (`45454`). |
| **1.5 Graph representation** | **Done** | `mesh/graph/MeshGraphRepository.kt:10-186`. Vertex = deviceId, edge = `MeshEdge`. `latencyMs` is `null`; `diagnosticsRepo` (`MeshDiagnosticsRepository.kt:44`) counts one-way claims in `neighborsOf(selfDeviceId)`. |
| **1.6 BFS route calculation** | **Done (edge direction bug)** | `mesh/routing/RouteCalculator.kt:22-86`. Deterministic lexicographic tie-breaking. Gap **M7**: line 24 filters only `it.expiresAt > now` and ignores `it.bidirectional`, allowing one-way edges into routes. |
| **1.7 Audio packet format** | **Done** | `mesh/protocol/MeshPacket.kt:20-128`, `mesh/protocol/PayloadCrypto.kt:16-75`. 75-byte header + 656-byte AES-GCM voice payload + 16-byte HMAC tag = 747 bytes. |
| **1.8 18-step forwarding** | **Done (pre-AEAD state mutation bug)** | `mesh/forwarding/ForwardingEngine.kt:235-408`. Gap **M9**: `sessionManager.admit()` (line 293) and `duplicateCache.markIfNew()` (line 317) run *before* `PayloadCrypto.open()` (line 338) and `HelloCodec.decode()` (line 346). |
| **1.9 TTL & loop prevention** | **Done** | `mesh/forwarding/TtlPolicy.kt:9-46`. `DEFAULT_TTL = 5`, `HELLO_TTL = 4`, `MAX_CLOCK_SKEW_MS = 60_000L`, `FORWARD_BUDGET_PER_SEC = 120`. |
| **1.10 Jitter buffer** | **Done (epoch reset gap)** | `mesh/audio/JitterBuffer.kt:24-243`. 3-frame (60 ms) target, 16-frame max, PLC, 16-bit saturating mixer. Gap **L1**: keyed only by `sourceId`, not `(sourceId, epoch)`. |
| **1.11 Stage 1 tests** | **Done in JVM; physical hook missing** | `MeshStage1SimulationTest.kt`, `ScheduledDeliveryScenarioTest.kt`. Gap **H5**: `MeshConfig.debugPeerFilter` (`MeshConfig.kt:46`) is never read by `ForwardingEngine` or `UdpMeshLink`. |
| **2.1 `VoiceTransport` + adapters** | **Done** | `mesh/transport/VoiceTransport.kt:44-59`, `LiveKitVoiceTransport.kt:33-158`, `OfflineMeshVoiceTransport.kt:46-405`. |
| **2.2 Separate participant models** | **Done** | `mesh/transport/VoiceParticipantModels.kt:6-107` (`OnlineParticipant`, `OfflineMeshDevice`, `UnifiedVoicePeer`). |
| **2.3 Audio ownership arbiter** | **BROKEN in production (C2)** | `mesh/transport/AudioOwnershipArbiter.kt:27-106`. `claimLiveKit()` is only called in `LiveKitVoiceTransport.start()` (`line 111`), which is never called by `RoomViewModel`. `owner` stays `NONE` during online rides. |
| **2.4 Coordinator / runtime integration** | **BROKEN in production (C1)** | `mesh/transport/VoiceSessionCoordinator.kt:27-162` is provided in `di/MeshModule.kt:73-81` but never injected anywhere in the app. |
| **2.5 Separate offline UI + `OfflineAwareRoomHost`** | **Partial** | `OfflineRoomScreen.kt:69-500` exists, but `OfflineAwareRoomHost` does not exist, and `OfflineRoomScreen` omits route age, mesh health summary, audio output route selector, and PTT/VOX controls. |
| **2.6 Stage 2 tests** | **Partial** | `Stage2IntegrationTest.kt` uses fakes; does not test real `LiveKitManager` state observation, PTT/VOX, or audio routing. |
| **3.1 Local failure detection** | **Partial & unwired** | `mesh/switching/LocalHealthSignalsProvider.kt:33-118` and `SwitchingModels.kt:18-40`. `isLiveKitReconnecting` and `liveKitReconnectAttempts` are sampled (`lines 56-57`) but never used in `LocalHealthSignals`. |
| **3.2 State machine (8 states)** | **Done in isolation, unwired (C1)** | `mesh/switching/AutomaticSwitchStateMachine.kt:20-196`. Implements the 8 plan states (`ONLINE`, `ONLINE_SUSPECT`, `STARTING_OFFLINE_MESH`, `OFFLINE_ACTIVE`, `ONLINE_RECOVERING`, `HANDING_BACK_TO_LIVEKIT`, `FALLBACK_UNAVAILABLE`, `SESSION_ENDED`). |
| **3.3 Online to offline fallback** | **BROKEN (C1, C3, C4, D2, D9)** | Unwired; disconnects LiveKit instead of suspending capture; no online-to-offline session bootstrap; no warm-standby discovery while online; no permission/capability check before switch. |
| **3.4 Offline to online recovery** | **BROKEN (C1, C3, D2, H6)** | `lastOnlineSession` is always `null` in production (`VoiceSessionCoordinator.kt:35,135`); `LiveKitVoiceTransport.resume()` (`line 139`) unmutes a user who was muted before fallback (**H6**). |
| **3.5 Group status (3 states)** | **Missing** | Plan requires `Local online recovered`, `Some peers still offline`, `Entire known group online`. None exist in code or UI. |
| **3.6 Anti-flapping** | **Done** | `SwitchingModels.kt:45-51` (`failureConfirmationMs = 10_000L`, `recoveryStabilityMs = 8_000L`, `minDwellMs = 20_000L`, `maxTransitionsPerWindow = 3`, `windowMs = 120_000L`). |

---

## 3. Architectural Decisions (Resolved — Do Not Deviate)

To eliminate ambiguity and prevent AI hallucination during implementation, the following architectural decisions are **final and mandatory**:

### Decision 1 (Fixes D1): Single UDP Port `45454` with `DISCOVERY_PORT` Unified
- All mesh packets (`HELLO`, `VOICE`, `BYE`) already share `MeshPacketCodec` and `ForwardingEngine` over UDP port `45454` (`MeshConfig.AUDIO_PORT`).
- Set `const val DISCOVERY_PORT = AUDIO_PORT` (`45454`) in `MeshConfig.kt` (keeping the constant name for backward test compatibility) and default `UdpMeshLink.discoverBroadcastEndpoints(port: Int = MeshConfig.AUDIO_PORT)` to `MeshConfig.AUDIO_PORT`.

### Decision 2 (Fixes D2, C2, C3, H6): Non-Disconnecting Pause/Resume Handoff + Automatic `LiveKitManager` State Bridge (Zero Frozen-File Diff)
- **Constraint:** `network/LiveKitManager.kt` and `state/RoomViewModel.kt` are **FROZEN** (`0` diff allowed).
- **How `AudioOwnershipArbiter` sees the online ride (Fixes C2):**
  - `LiveKitManager` is a `@Singleton` injected into `LiveKitVoiceTransport`.
  - In `LiveKitVoiceTransport.init` (`LiveKitVoiceTransport.kt:66-78`), when collecting `liveKitPort.connectionState`:
    - When `conn` is `ConnectionState.CONNECTING`, `ConnectionState.CONNECTED`, or `ConnectionState.RECONNECTING` **and** `!pausedByCoordinator` **and** `audioOwnershipArbiter.owner.value != AudioOwner.OFFLINE_MESH`: call `audioOwnershipArbiter.claimLiveKit()`.
    - When `conn` is `ConnectionState.DISCONNECTED` or (`conn == ConnectionState.FAILED` and `audioOwnershipArbiter.owner.value == AudioOwner.LIVEKIT` and `!pausedByCoordinator`): call `audioOwnershipArbiter.release(AudioOwner.LIVEKIT)`.
  - Ensure `LiveKitVoiceTransport` and `VoiceSessionCoordinator` are eagerly initialized at app startup (by injecting `VoiceSessionCoordinator` into `App.kt` or an initializer in `MeshModule.kt`) so `LiveKitVoiceTransport.init` is always active even before any mesh screen is opened.
- **How Online-to-Offline Fallback Suspends LiveKit Without Disconnecting (Fixes D2, C3, H6):**
  - Extend `LiveKitPort` (`mesh/transport/LiveKitPort.kt`) with:
    - `val isUserMuted: StateFlow<Boolean>` (delegating to `liveKitManager.isUserMuted`)
    - `val deafened: StateFlow<Boolean>` (delegating to `liveKitManager.deafened`)
    - `fun onVoiceGate(open: Boolean)` (delegating to `liveKitManager.onVoiceGate(open)`)
    - `fun onNetworkRestored()` (delegating to `liveKitManager.onNetworkRestored()`)
    - `fun stopVox()` (calling `voxEngine.stop()` on the injected `@Singleton VoxEngine`)
  - When `LiveKitVoiceTransport.pause()` is called:
    1. Record `savedUserMutedBeforePause = liveKitPort.isUserMuted.value` and `savedDeafenedBeforePause = liveKitPort.deafened.value`.
    2. Set `pausedByCoordinator = true`.
    3. Call `liveKitPort.onVoiceGate(false)`, `liveKitPort.setUserMuted(true)`, `liveKitPort.setDeafened(true)`, and `liveKitPort.stopVox()`.
    4. In `LiveKitVoiceTransport.init`, whenever `liveKitPort.connectionState` emits `ConnectionState.CONNECTED` **while `pausedByCoordinator == true`** (which happens when `RoomViewModel` / `NetworkResilienceManager` calls `liveKitManager.onNetworkRestored()` in the background!): immediately re-assert `liveKitPort.onVoiceGate(false)`, `liveKitPort.setUserMuted(true)`, `liveKitPort.setDeafened(true)`, `liveKitPort.stopVox()`, and launch a `delay(250L)` coroutine that calls `liveKitPort.stopVox()` and `liveKitPort.setUserMuted(true)` again to defeat `LiveKitManager.startVoxSafely()` (`LiveKitManager.kt:473`, which delays 150 ms before calling `voxEngine.start()`).
  - In `VoiceSessionCoordinator.fallbackToMesh()`:
    1. Verify `mesh.hasValidRoute.value` is `true`.
    2. Use `audioOwnershipArbiter.handoffLiveKitToOfflineMesh { liveKitTransport.pause() }` (instead of `liveKitTransport.stop()`).
    3. Call `mesh.enableAudio()`.
    4. If `mesh.enableAudio()` fails or `meshTransport.micState.value == MicState.HARDWARE_BUSY` (e.g., on an older device where WebRTC's native ADM holds the mic despite mute): call `liveKitTransport.stop()` as a hardware-release fallback and retry `mesh.enableAudio()` once. If still failing, roll back via `liveKitTransport.resume()` and `audioOwnershipArbiter.claimLiveKit()`.
- **How Offline-to-Online Recovery Works (Fixes C3, D2, H6):**
  - While in `AutoSwitchState.OFFLINE_ACTIVE`, if `signals.isValidatedInternet && signals.isHealthProbeSuccessful && !signals.isLiveKitConnected && !signals.isLiveKitReconnecting`, `LocalHealthSignalsProvider` / `VoiceSessionCoordinator` calls `liveKitTransport.ensureReconnectingInBackground()` (which invokes `liveKitPort.onNetworkRestored()` while keeping `pausedByCoordinator = true`).
  - Once `LiveKitManager` connects (`isLiveKitConnected == true`), `LocalHealthSignals.isOnlineRecoverable()` (`isValidatedInternet && isLiveKitConnected && isHealthProbeSuccessful`) becomes `true`, entering `ONLINE_RECOVERING` for the 8-second `recoveryStabilityMs` window (matching Stage 3.4 of the plan).
  - When the 8-second window completes, `VoiceSessionCoordinator.recoverToLiveKit()` executes:
    1. `mesh.disableAudio()` (stops `OfflineAudioCapture` first, then `OfflineAudioPlayback`, and releases `AudioOwner.OFFLINE_MESH`).
    2. If `liveKitTransport.state.value != VoiceTransportState.CONNECTED`, calls `liveKitTransport.ensureReconnectingInBackground()` (or `liveKitTransport.start(online)` if `lastOnlineSession != null`) and waits up to `15_000L` ms for `VoiceTransportState.CONNECTED`.
    3. Claims `AudioOwner.LIVEKIT` and calls `liveKitTransport.resume()`, which restores `liveKitPort.setDeafened(savedDeafenedBeforePause)` and `liveKitPort.setUserMuted(savedUserMutedBeforePause)` (never unmuting a user who was muted before fallback!).

### Decision 3 (Fixes D9, C4): Deterministic Companion `OfflineSession` from `ActiveRideRepository`
- Why fallback had no session: `ActiveRideRepository.state` holds the active online ride (`ActiveRide`), which has `roomId: String` and `lkRoomName: String?`. Every device in the same online convoy shares the same canonical room identifier (`val canonicalKey = ride.lkRoomName?.takeIf { it.isNotBlank() } ?: ride.roomId`).
- Add `OfflineSession.fromOnlineRoom(canonicalRoomKey: String, now: Long, iterations: Int = SessionKdf.DEFAULT_ITERATIONS): OfflineSession?` in `mesh/session/OfflineSession.kt`:
  - Normalize `canonicalRoomKey.trim().lowercase()`. Return `null` if blank.
  - Compute `HmacSHA256` with domain key `"rvmesh/v2/online-room-bootstrap".toByteArray(UTF_8)` over `canonicalRoomKey.toByteArray(UTF_8)`.
  - Convert the first 10 bytes (80 bits) into 16 characters of `OfflineSession.ALPHABET` (5 bits per character).
  - Return `fromCode(code16, now, iterations)`.
- When an online ride is active (`ActiveRideRepository.state.value != null`) and `OfflineMeshGate.isWarmStandbyAllowed() || OfflineMeshGate.isAutomaticFallbackAllowed()`, `VoiceSessionCoordinator` derives the companion `OfflineSession` on `Dispatchers.Default` and calls `meshTransport.startDiscoveryOnly(companionSession)` so UDP discovery runs in warm standby without opening `AudioRecord` or `AudioTrack`.

---

## 4. Complete Defect, Bug, Crash & CI Failure Register

### 4.1 Critical Defects (Break Core Plan Goals)

| ID | File & Lines | Defect Description | Exact Fix Required |
| :--- | :--- | :--- | :--- |
| **C1** | `di/MeshModule.kt:73-81`, `mesh/switching/AutomaticSwitchStateMachine.kt:20`, `mesh/switching/LocalHealthSignalsProvider.kt:33` | **Stage 3 is completely unwired.** Nothing in production injects `VoiceSessionCoordinator`, constructs `AutomaticSwitchStateMachine`, or calls `LocalHealthSignalsProvider.startDriving(...)`. | Provide `@Singleton LocalHealthSignalsProvider` and `@Singleton AutomaticSwitchStateMachine` in `MeshModule.kt`. Create `@Singleton MeshAutoSwitchManager` (or wire inside `VoiceSessionCoordinator`) initialized in `App.onCreate()` that observes `ActiveRideRepository.state` and `OfflineMeshGate`, starts warm-standby discovery when allowed, and drives `AutomaticSwitchStateMachine` every `1_000L` ms while an online ride is active. |
| **C2** | `mesh/transport/LiveKitVoiceTransport.kt:66-78,111`, `mesh/ui/OfflineMeshViewModel.kt:26` | **Arbiter is blind to online rides.** `audioOwnershipArbiter.claimLiveKit()` is only called in `LiveKitVoiceTransport.start()`, which `RoomViewModel` never calls. `arbiter.owner.value` stays `AudioOwner.NONE` during online rides, so `OfflineMeshViewModel.isMicOwnedByLiveKit()` is always `false` and opening the offline HUD starts a second `AudioRecord` concurrently with LiveKit. | In `LiveKitVoiceTransport.init`, observe `liveKitPort.connectionState`: claim `AudioOwner.LIVEKIT` when `CONNECTING`/`CONNECTED`/`RECONNECTING` (unless `pausedByCoordinator` or owned by `OFFLINE_MESH`), and release `AudioOwner.LIVEKIT` when `DISCONNECTED`/`FAILED`. Eagerly initialize `LiveKitVoiceTransport` at app startup. |
| **C3** | `mesh/transport/VoiceSessionCoordinator.kt:35,101-156` | **Recovery always fails (`lastOnlineSession == null`) & fallback fights `RoomViewModel`.** `lastOnlineSession` is only set in `switchToTransport()`, which is never called when `RoomViewModel` starts a ride. Calling `liveKitTransport.stop()` disconnects LiveKit behind `RoomViewModel`'s back, while `RoomViewModel` (`line 161`) calls `liveKitManager.onNetworkRestored()` on network change. | Implement Decision 2: use `liveKitTransport.pause()`/`resume()` via `AudioOwnershipArbiter.handoffLiveKitToOfflineMesh` / `handoffOfflineMeshToLiveKit`, keep `LiveKitManager` muted/deafened while paused even across background `onNetworkRestored()` reconnects, and support recovery via `liveKitPort.onNetworkRestored()` + `lastOnlineSession` populated from `ActiveRideRepository`. |
| **C4** | `mesh/transport/OfflineMeshVoiceTransport.kt:87,196`, `mesh/OfflineMeshGate.kt:29-33` | **Fallback can never find a route (`hasValidRoute == false`).** `hasValidRoute` is only `true` after `startDiscoveryOnly()` runs and receives neighbor `HELLO`s, but nothing starts discovery during an online ride and no offline session secret is shared. | Implement Decision 3: derive deterministic companion `OfflineSession.fromOnlineRoom(...)` when `ActiveRideRepository.state` has an active ride, and run `meshTransport.startDiscoveryOnly(session)` whenever `isWarmStandbyAllowed() || isAutomaticFallbackAllowed()` (or when `onProbeMesh` fires in `ONLINE_SUSPECT`). Add the Warm Standby toggle to `SettingsScreen.kt`. |

### 4.2 High-Severity Bugs & Crashes

| ID | File & Lines | Defect Description | Exact Fix Required |
| :--- | :--- | :--- | :--- |
| **H1** | `mesh/audio/MeshVoxBridge.kt:23-98`, `mesh/ui/OfflineRoomScreen.kt:104-122`, `audio/VoxEngine.kt:183` | **No PTT or VOX in offline mesh; wiring `VoxEngine` directly would open a second `AudioRecord`.** `OfflineRoomScreen` only has a mic on/off button. If `VoxEngine` is started in `"own"` mode, it opens a second `AudioRecord` (`VoxEngine.kt:187`). | Add `VoxDetector` (from `com.ridervoice.audio.VoxDetector`, which is pure math and opens no `AudioRecord`) or frame-callback support into `OfflineAudioCapture` / `MeshVoxBridge` so that in VOX mode `OfflineAudioCapture` reads from its single `AudioRecord` and only calls `onPcmFrame` when `VoxDetector` gate is open or PTT is pressed. Add press-and-hold PTT button, VOX toggle, and hardware PTT (`HardwarePTTManager`) wiring to `OfflineRoomScreen`. |
| **H2** | `mesh/transport/OfflineMeshVoiceTransport.kt:49,399-401`, `di/MeshModule.kt:65-69` | **Offline audio routing is a no-op.** `setAudioRoute(route)` calls `onAudioRouteChanged(route)` which defaults to `{}` and is not supplied in `MeshModule.kt`. Nothing sets `AudioManager.MODE_IN_COMMUNICATION`, Bluetooth SCO, or speakerphone in standalone offline mode. | Create `mesh/audio/MeshAudioRouteController.kt` (using Android `AudioManager` / `setCommunicationDevice` on API 31+ and `startBluetoothSco`/`isSpeakerphoneOn` on API 26–30, plus `AudioFocusRequest`) and wire it into `OfflineMeshVoiceTransport` via `MeshModule.kt`. Expose current `AudioRoute` state and route selector in `OfflineRoomScreen`. |
| **H3** | `mesh/transport/OfflineMeshVoiceTransport.kt:64`, `di/MeshModule.kt:65-69`, `services/MeshForegroundService.kt:105` | **Fake mic permission gate & Android 14 `SecurityException` crash.** `hasMicPermission` defaults to `{ true }` and `MeshModule.kt` never overrides it. On Android 14 (API 34), calling `startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)` in `MeshForegroundService.kt:105` when `RECORD_AUDIO` permission is denied or app is in background without active mic permission throws `SecurityException` and crashes the app. | In `MeshModule.kt`, pass `hasMicPermission = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }`. In `MeshForegroundService.kt`, check `RECORD_AUDIO` permission before `startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)` and wrap `startForeground` in `try/catch (SecurityException)` that stops the service cleanly instead of crashing. |
| **H4** | `mesh/link/UdpMeshLink.kt:36,64`, `di/MeshModule.kt:61-69` | **UDP socket is not bound to the Wi-Fi `Network`.** `networkBinder` defaults to `{}`. When mobile data is on and the Wi-Fi hotspot/mesh network has no validated internet (`NET_CAPABILITY_VALIDATED` is false), Android routes `0.0.0.0` UDP traffic over cellular, breaking unicast forwarding between peers. | In `UdpMeshLink` / `AndroidNetworkLocks` (or a new `WifiNetworkBinder` in `mesh/link/NetworkLocks.kt`), look up the active `TRANSPORT_WIFI` `android.net.Network` from `ConnectivityManager` and call `wifiNetwork.bindSocket(datagramSocket)` inside `networkBinder`, and filter `discoverBroadcastEndpoints` to Wi-Fi interfaces (`wlan*`, `ap*`, `p2p*`) first. |
| **H5** | `mesh/MeshConfig.kt:46`, `mesh/forwarding/ForwardingEngine.kt:235`, `mesh/link/UdpMeshLink.kt:83` | **Multi-hop cannot be tested on real phones on the same Wi-Fi subnet.** Every phone on a hotspot hears every other phone at 1 hop. `MeshConfig.debugPeerFilter` is declared at `MeshConfig.kt:46` (`@Volatile var debugPeerFilter: ((String) -> Boolean)? = null`) but is **never read anywhere in the codebase**. | Enforce `MeshConfig.debugPeerFilter` in `ForwardingEngine.onIncomingFrame()` (`ForwardingEngine.kt:256`: if `MeshConfig.debugPeerFilter?.invoke(decoded.previousHopId) == false`, drop packet with `DropReason.PREVIOUS_HOP_INVALID` before any state update). This allows forcing arbitrary multi-hop topologies (e.g., `A — B — C — D — E — F`) on real phones on a single hotspot. |
| **H6** | `mesh/transport/LiveKitVoiceTransport.kt:63,128-140` | **Handback opens a live microphone for muted/PTT users.** `userMicEnabled` is initialized to `true` (`line 63`) and never synced with `liveKitManager.isUserMuted`. On `resume()`, `liveKitPort.setUserMuted(!userMicEnabled)` (`line 139`) unmutes the user unconditionally. | In `LiveKitVoiceTransport.pause()`, snapshot `savedUserMuted = liveKitPort.isUserMuted.value` and `savedDeafened = liveKitPort.deafened.value`. In `resume()`, restore `liveKitPort.setUserMuted(savedUserMuted)` and `liveKitPort.setDeafened(savedDeafened)`. |

### 4.3 Medium-Severity Bugs, Crashes & Security Flaws

| ID | File & Lines | Defect Description | Exact Fix Required |
| :--- | :--- | :--- | :--- |
| **M1** | `mesh/ui/OfflineMeshLobbyScreen.kt:173,297,335`, `navigation/NavGraph.kt:392-395` | **ANR / UI freeze from 120,000-iteration PBKDF2 on Main thread.** `OfflineSession.create()` and `OfflineSession.fromCode()` run synchronously inside button `onClick` handlers and inside `remember(sessionCode)` during Compose composition. | Move `OfflineSession.create()` and `OfflineSession.fromCode()` onto `withContext(Dispatchers.Default)` inside `OfflineMeshViewModel` / coroutine scope, show a loading indicator while deriving keys, and cache the derived `OfflineSession` in `OfflineMeshViewModel` so `NavGraph` never runs PBKDF2 in `remember`. |
| **M2** | `navigation/NavGraph.kt:397-423`, `AndroidManifest.xml:37-39` | **Screen rotation kills the active mesh session.** `DisposableEffect(sessionCode)` in `NavGraph.kt:417-423` calls `viewModel.stopSession()` and stops `MeshForegroundService` on every configuration change (screen rotation). | Move session ownership lifecycle into `OfflineMeshViewModel.onCleared()` and explicit user `onLeave` action (in `OfflineRoomScreen`), NOT `DisposableEffect` on composition disposal. Also add `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"` to `MainActivity` in `AndroidManifest.xml`. |
| **M3** | `mesh/ui/OfflineMeshViewModel.kt:37-42`, `mesh/transport/OfflineMeshVoiceTransport.kt:300-303` | **`BYE` packet is never sent when leaving from UI (`NetworkOnMainThreadException`).** `OfflineMeshViewModel.stopSession()` launches on `viewModelScope` (`Dispatchers.Main`), which calls `transport.stop()` -> `forwardingEngine?.originateBye()` -> `DatagramSocket.send()` on the Main thread. Android throws `NetworkOnMainThreadException`, which `runCatching` swallows silently. | In `OfflineMeshVoiceTransport.stop()`, wrap `runCatching { forwardingEngine?.originateBye() }` and `link?.stop()` in `withContext(dispatcher)` (which defaults to `Dispatchers.IO`), and send `BYE` twice (with a 10 ms gap) for UDP reliability. In `OfflineMeshViewModel.stopSession()`, also dispatch with `viewModelScope.launch(Dispatchers.IO)`. |
| **M4** | `services/MeshForegroundService.kt:50-66,84-109` | **`ForegroundServiceDidNotStartInTimeException` crash window on Android 8+.** `MeshForegroundService.start()` calls `context.startForegroundService(intent)`. If `activeTransport` transitions to `STOPPED` or `OfflineMeshGate` closes in the few milliseconds before `onStartCommand()` runs, lines 85–99 call `stopSelf()` and return **without calling `startForeground()`**, which crashes the app with `RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()`. | In `MeshForegroundService.onStartCommand()`, **always** call `startForeground(NOTIFICATION_ID, buildNotification(...))` (within `try/catch`) **before** any early `stopSelf()` return whenever `onStartCommand` is invoked on API 26+. |
| **M5** | `mesh/link/NetworkLocks.kt:21-55`, `mesh/audio/OfflineAudioCapture.kt:133`, `mesh/audio/OfflineAudioPlayback.kt:87` | **Missing audio thread priority & lock management.** Audio capture and playback coroutines run on generic `Dispatchers.IO` threads at normal priority instead of `Process.THREAD_PRIORITY_URGENT_AUDIO`, causing jitter under CPU load. Wi-Fi `LOW_LATENCY` lock is held even during warm-standby discovery when audio is disabled. | Set `android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)` inside the capture and playback loops. Only acquire `WIFI_MODE_FULL_LOW_LATENCY` lock when audio is enabled (or active mesh), using multicast lock only during discovery. |
| **M6** | `mesh/transport/OfflineMeshVoiceTransport.kt:151-220` | **Unhandled exceptions in transport coroutines.** `heartbeatJob`, `frameListenerJob`, and `peerProjectorJob` have no `CoroutineExceptionHandler` and no `runCatching` inside their `while (isActive)` bodies; a single unexpected exception kills the loop silently. | Add a `CoroutineExceptionHandler` to `transportScope` and wrap the loop bodies of `heartbeatJob`, `frameListenerJob`, and `peerProjectorJob` in `runCatching` (re-throwing `CancellationException`). |
| **M7** | `mesh/routing/RouteCalculator.kt:24-29`, `mesh/graph/MeshGraphRepository.kt:169-183` | **Routes use one-way unconfirmed links.** `RouteCalculator.compute()` includes all edges with `it.expiresAt > now`, even when `it.bidirectional == false`, whereas relay suppression (`ForwardingEngine.kt:456`) only trusts `e.bidirectional`. A one-way radio link can be chosen as a BFS `nextHop`, black-holing packets. | In `RouteCalculator.compute()`, filter edges by `it.expiresAt > now && it.bidirectional` when bidirectional paths exist (falling back to non-expired direct edges only if no bidirectional edge exists yet during initial 1-tick convergence). |
| **M8** | `di/MeshModule.kt:47-50`, `mesh/identity/MeshIdentity.kt:44-54` | **Peers see phone model (`SM-G991B`) instead of rider name.** `MeshModule` initializes `defaultDisplayName = { Build.MODEL ?: "Rider" }` and nothing in the app ever calls `MeshIdentityRepository.setDisplayName(...)`. | In `MeshModule.provideMeshIdentityRepository`, read the authenticated user's display name from `SecurePreferences` / `FirebaseAuth` if available before falling back to `Build.MODEL`, and refresh `MeshIdentityRepository` when starting a session. |
| **M9** | `mesh/forwarding/ForwardingEngine.kt:293-342`, `mesh/protocol/PayloadCrypto.kt:20-28` | **Pre-AEAD admission & duplicate cache poisoning + nonce source binding.** In `ForwardingEngine.onIncomingFrame()`, `sessionManager.admit()` (`line 293`) and `duplicateCache.markIfNew()` (`line 317`) run *before* `PayloadCrypto.open()` (`line 338`). A packet with valid outer HMAC but invalid inner GCM tag mutates membership and burns the `(sourceId, seq)` slot. Also, the 12-byte GCM nonce (`PayloadCrypto.kt:20-28`) has 3 zero bytes (`25-27`) instead of binding a 3-byte hash of `sourceId`. | Move `PayloadCrypto.open()` **before** `sessionManager.admit()` and `duplicateCache.markIfNew()` (after outer HMAC + cheap ` duplicateCache.contains()` pre-check, only calling `markIfNew` and `admit` after `PayloadCrypto.open()` succeeds!). Put the first 3 bytes of `uuidBytes(sourceId)` into bytes `9..11` of `PayloadCrypto.nonce(...)`. |
| **D3 / D4** | `AndroidManifest.xml:5-11`, `ui/screens/SettingsScreen.kt:201-205` | **Unneeded `NEARBY_WIFI_DEVICES` permission blocks Settings toggle on Android 13+.** `SettingsScreen.kt:203` requests `Manifest.permission.NEARBY_WIFI_DEVICES` and refuses to enable the mesh toggle if denied, even though `UdpMeshLink` never calls Wi-Fi Aware / P2P APIs requiring `NEARBY_WIFI_DEVICES`. | Remove `NEARBY_WIFI_DEVICES` from the permission prompt in `SettingsScreen.kt:201-205` (only require `RECORD_AUDIO`, plus `POST_NOTIFICATIONS` on API 33+ for the foreground service notification) and remove `NEARBY_WIFI_DEVICES` and `CHANGE_WIFI_STATE` from `AndroidManifest.xml`. |
| **D5 / D6** | `navigation/NavGraph.kt:386-395`, `services/MeshForegroundService.kt:59,179` | **Silent session substitution on bad code & secret pairing code exposed in notification.** `NavGraph.kt:393-394` does `OfflineSession.fromCode(...) ?: OfflineSession.create(...)`, silently creating a random session if the code is invalid. `MeshForegroundService.kt:179` puts the raw 16-char secret `sessionCode` into the notification text (`"Offline Mesh: $sessionCode | $peerCount peers"`). | If `OfflineSession.fromCode` returns `null`, navigate back to `OFFLINE_MESH_LOBBY` with an error instead of creating a random session. Mask the code in `MeshForegroundService` notification (`"XXXX-••••-••••-${sessionCode.takeLast(4)}"`). |

### 4.4 Low-Severity & UI/Localization Defects

| ID | File & Lines | Defect Description | Exact Fix Required |
| :--- | :--- | :--- | :--- |
| **L1** | `mesh/audio/JitterBuffer.kt:152-165` | `MultiSourceJitterBufferManager` keys buffers by `sourceId` only, not `(sourceId, epoch)`. If a sender restarts within `sourceTimeoutMs` (new `epoch`, `sequence = 1`), frames are dropped as late until the buffer un-primes. | Pass `epoch: Int = 0` to `onVoicePacket(sourceId, sequence, pcmBytes, epoch)`. If `epoch` changes for an existing `sourceId`, reset/recreate that `SourceJitterBuffer`. |
| **L2** | `mesh/diagnostics/MeshDiagnosticsRepository.kt:44` | `directNeighborCount` uses `graph.neighborsOf(selfDeviceId).size`, which includes remote one-way claims instead of only direct neighbors (`graph.edges.count { (it.from == selfDeviceId || it.to == selfDeviceId) && it.endpoint != null }`). | Count only direct edges incident to `selfDeviceId` with non-null `endpoint` (or pass `graphRepository.directNeighbors().size`). |
| **L3** | `mesh/audio/OfflineAudioCapture.kt:117-121` | `AcousticEchoCanceler`, `NoiseSuppressor`, and `AutomaticGainControl` instances are created and immediately eligible for GC without being retained in fields or released in `releaseInternal()`. | Retain `aec`, `ns`, and `agc` references in `AndroidAudioRecordSource` and release them in `release()`. |
| **L4** | `mesh/MeshConfig.kt:15-23,30-31` | `DUP_CACHE_MAX_ENTRIES = 32_000` is smaller than `6 talkers * 50 pps * 125 s = 37,500`. KDoc on line 15 (`Number of times a packet may still be forwarded...`) is attached to `SUPPRESS_REDUNDANT_RELAYS` instead of `DEFAULT_TTL`. | Increase `DUP_CACHE_MAX_ENTRIES` to `40_000`. Move the line 15 KDoc onto `DEFAULT_TTL`. |
| **L5** | `mesh/link/UdpMeshLink.kt:45,123-127` | `knownEndpoints` and `registerEndpoint` are dead in production; clean up or document as static-peer test hook. | Keep `registerEndpoint` documented as manual/test endpoint injection and test it explicitly. |
| **L6** | `ui/screens/SettingsScreen.kt:185-248`, `services/MeshForegroundService.kt:149,152,179,183,187`, `mesh/ui/OfflineRoomScreen.kt:98,282-293,373-379` | **Hardcoded English strings bypass localization.** `SettingsScreen` mesh toggles, `MeshForegroundService` notification strings, `OfflineRoomScreen` `DiagRow` labels (`lines 282-293`), routing text (`lines 373-379`), and raw `transportState.name` (`line 98`) are hardcoded in English instead of using `strings.xml` (`en`, `hi`, `mr`). Also `nextHopId` is shown as a raw 8-char UUID instead of resolving the peer's display name (`peers.find { it.id == peer.nextHopId }?.name`). | Add all missing `mesh_*` string keys to `values/strings.xml`, `values-hi/strings.xml`, and `values-mr/strings.xml`. Use localized state labels, localized `DiagRow` labels, localized route descriptions, and resolve `nextHopId` to the relay peer's display name. Extend `LocalizationParityTest` to scan `MeshForegroundService.kt` and the mesh section of `SettingsScreen.kt`. |
| **L7** | `mesh/ui/OfflineRoomScreen.kt:69-261`, `navigation/NavGraph.kt:214-237` | **Missing Stage 2.5 & 3.5 UI elements and `OfflineAwareRoomHost`.** `OfflineRoomScreen` does not display `routeAgeMs` (even though `UnifiedVoicePeer.routeAgeMs` is computed), mesh health status badge, audio output route, or Stage 3.5 group status (`Local online recovered`, `Some peers still offline`, `Entire known group online`). And `NavGraph.kt` renders `RoomScreen` directly at `Routes.ACTIVE_RIDE_HUD` without an `OfflineAwareRoomHost` banner/overlay when automatic fallback activates during an online ride. | Create `mesh/ui/OfflineAwareRoomHost.kt` wrapping `RoomScreen` at `Routes.ACTIVE_RIDE_HUD` (without modifying frozen `RoomScreen.kt`!). When `AutoSwitchState` is `ONLINE_SUSPECT`, `STARTING_OFFLINE_MESH`, `OFFLINE_ACTIVE`, `ONLINE_RECOVERING`, or `FALLBACK_UNAVAILABLE`, display a non-intrusive top status banner + group status indicator (`Local online recovered` / `Some peers still offline` / `Entire known group online`) and an option to expand the mesh topology sheet. Display `routeAgeMs`, mesh health, and audio route on `OfflineRoomScreen`. |
| **L8** | `mesh/ImportIsolationTest.kt:46-59` | `frozenOnlineFilesNeverImportMesh` checks `if (f.exists())`, so a typo in a frozen path silently passes. | Assert `assertTrue("Frozen file missing: $rel", f.exists())` for every frozen file path in `ImportIsolationTest.kt`. |

### 4.5 GitHub Actions & CI/CD Defects

| ID | File & Lines | Defect Description | Exact Fix Required |
| :--- | :--- | :--- | :--- |
| **CI-1** | `.github/workflows/build.yml:34-46` | **CI never runs `lintDebug`, never tests `assembleRelease` (R8/ProGuard), and never checks frozen-file isolation or minimum test count.** A ProGuard/R8 failure or Lint error or accidental edit to a frozen file will pass CI unnoticed. | Update `.github/workflows/build.yml` (`validate-android` job) to: (1) verify frozen Android files have 0 diff against `a37ef09`, (2) run `./gradlew testDebugUnitTest lintDebug assembleDebug`, (3) parse JUnit XMLs and fail if total tests `< 290` or failures `> 0` or skipped `> 0`, and (4) run `ALLOW_INSECURE_RELEASE_BUILD=true ./gradlew assembleRelease` to verify R8 minification/shrinking passes on every push/PR. |
| **CI-2** | `.github/workflows/release.yml:180-183` | **Release notes step dumps entire 51 KB `RELEASE_NOTES.md` into every GitHub Release.** Line 182 runs `CHANGELOG_NOTES=$(cat RELEASE_NOTES.md)` without extracting the current version section, risking GitHub API body length failure (`422 Unprocessable Entity` when > 125 KB) and polluting every release with all historical notes. | In `.github/workflows/release.yml:181-183`, extract only the matching version section from `RELEASE_NOTES.md` (with a 15,000-character safety cap) before falling back to the top section. |
| **CI-3** | `mobile-app/app/proguard-rules.pro` | **Mesh data classes and enums lack explicit R8 keep rules.** If `OFFLINE_MESH_BUILD_ENABLED` is enabled in an internal tester release build or inspected via reflection/Gson, R8 can strip/obfuscate `com.ridervoice.mesh.continuity.**` and `com.ridervoice.mesh.diagnostics.**`. | Add `-keep class com.ridervoice.mesh.continuity.** { *; }` and `-keep class com.ridervoice.mesh.diagnostics.** { *; }` to `mobile-app/app/proguard-rules.pro`. |
| **CI-4** | `mobile-app/app/build.gradle:118,130` | **No way to build an internal-tester release APK with mesh enabled (D7 / Plan Step 30).** `release` hardcodes `OFFLINE_MESH_BUILD_ENABLED = false`. | In `mobile-app/app/build.gradle` and `FeatureFlags.kt`, allow a Gradle property `-PenableOfflineMeshInRelease=true` (default `false`) to set `OFFLINE_MESH_BUILD_ENABLED` in `release` builds for internal tester builds (Plan Step 30) while keeping default `release` builds strictly `false`. |

---

## 5. Master AI Execution Prompt (Copy-Paste to Any AI Coding Agent)

> **Instructions for the User:** You can give this entire file (or copy-paste Section 5 below) directly to any AI coding agent. It contains every file path, class signature, step-by-step code change, test requirement, and verification command so the AI executes with zero hallucination or deviation.

```markdown
# ROLE & OBJECTIVE
You are the Principal Android & Distributed Systems Implementation Engineer for RiderVoice (`BuzyU/Rider_APP`).
Your task is to execute Work Packages **WP-18 through WP-27** in strict sequential order to remediate every audit finding (`C1`–`C4`, `H1`–`H6`, `M1`–`M9`, `L1`–`L8`, `D1`–`D9`, `CI-1`–`CI-4`, and documentation drift) so the Offline Mesh Intercom conforms 100% to `rider-app-offline-mesh-three-stage-plan.md`.

# NON-NEGOTIABLE GROUND RULES (ZERO DEVIATION)
1. **Frozen Online Files Must Have 0 Diff:**
   You MUST NOT modify any of the following frozen files:
   - `mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/network/ConnectionState.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/network/NetworkResilienceManager.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/state/ActiveRideRepository.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/state/RoomFault.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/state/AppEvents.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/audio/*`
   - `mobile-app/app/src/main/java/com/ridervoice/diagnostics/*`
   - `mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/*`
   - `backend/*`, `web-app/*`, `livekit-server/*`
   Verify after every work package with:
   `git diff --exit-code a37ef09 -- mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt mobile-app/app/src/main/java/com/ridervoice/audio mobile-app/app/src/main/java/com/ridervoice/diagnostics`
2. **Never Delete, `@Ignore`, or Weaken Existing Tests:**
   The baseline has **290 passing unit tests** across 49 test suites (`0` failures, `0` skipped). Every work package must add new regression tests and increase total test count strictly above 290.
3. **Preserve Solid Stage 1 Core:**
   Do NOT rewrite `MeshPacketCodec` wire layout (75-byte header, magic `0x52564D31`, version `2`, 16-byte HMAC-SHA256 tag), `DuplicatePacketCache` 5-tuple structure, `TtlPolicy`, or `SourceJitterBuffer` PLC algorithm except for the targeted fixes specified below.
4. **Build & Test Environment:**
   Use JDK 17 (e.g. `JAVA_HOME="C:\Users\umerz\.jdks\jdk-17"` on Windows or Temurin 17 on Linux). Run `./gradlew testDebugUnitTest lintDebug assembleDebug` from `mobile-app/` after every work package.

---

# WORK PACKAGES (EXECUTE IN STRICT ORDER)

## WP-18: Protocol, Crypto, Routing & Core Mesh Hardening (Fixes D1, D9, H5, M7, M9, L1, L2, L4, L8)

### 1. `mobile-app/app/src/main/java/com/ridervoice/mesh/MeshConfig.kt`
- Fix **D1**: Change line 5 from `const val DISCOVERY_PORT = 45455` to `const val DISCOVERY_PORT = AUDIO_PORT` (`45454`) so all callers reference the single active UDP port `45454`.
- Fix **L4**: Move the KDoc on line 15 (`/** Number of times a packet may still be forwarded. 5 reaches 6 hops, the max chain for 6 devices. */`) so it sits directly above `const val DEFAULT_TTL = 5` (line 23).
- Fix **L4**: Increase `const val DUP_CACHE_MAX_ENTRIES = 32_000` (line 30) to `const val DUP_CACHE_MAX_ENTRIES = 40_000` (covering 6 concurrent talkers * 50 pps * 125 s = 37,500 entries).

### 2. `mobile-app/app/src/main/java/com/ridervoice/mesh/link/UdpMeshLink.kt`
- Fix **D1**: On line 135, change default parameter `fun discoverBroadcastEndpoints(port: Int = MeshConfig.DISCOVERY_PORT)` to `fun discoverBroadcastEndpoints(port: Int = MeshConfig.AUDIO_PORT)`.

### 3. `mobile-app/app/src/main/java/com/ridervoice/mesh/protocol/PayloadCrypto.kt`
- Fix **M9 (Nonce Source Binding)**:
  - Update `fun nonce(epoch: Int, sequence: Int, typeCode: Int, sourceId: String? = null): ByteArray` (lines 20-28):
    - If `sourceId != null` and valid UUID, compute `val srcBytes = runCatching { MeshPacketCodec.uuidBytes(sourceId) }.getOrNull()`, and put `srcBytes[0]`, `srcBytes[1]`, `srcBytes[2]` into bytes 9, 10, 11 of the 12-byte nonce (falling back to `0, 0, 0` only if `sourceId == null`).
  - Update `ForwardingEngine.kt` lines 116, 167, 209 (`selfId`) and line 337 (`decoded.sourceId`) to pass `sourceId` into `PayloadCrypto.nonce(epoch, sequence, typeCode, sourceId)`.

### 4. `mobile-app/app/src/main/java/com/ridervoice/mesh/protocol/DuplicatePacketCache.kt` & `ForwardingEngine.kt`
- Fix **M9 (Pre-AEAD State Mutation)** and **H5 (`debugPeerFilter` Enforcement)**:
  - In `DuplicatePacketCache.kt`, add a non-mutating check:
    ```kotlin
    @Synchronized
    fun isDuplicate(sessionId: String, sourceId: String, sequence: Int, kind: Int = 0, epoch: Int = 0): Boolean {
        val now = clock()
        purgeExpired(now)
        val key = "$sessionId|$sourceId|$epoch|$kind|$sequence"
        return entries.containsKey(key)
    }
    ```
  - In `ForwardingEngine.onIncomingFrame(frame: MeshFrame)` (`ForwardingEngine.kt:235-382`):
    1. Right after step 4 (`previousHopId` check at line 267), enforce `MeshConfig.debugPeerFilter` (**Fixes H5**):
       ```kotlin
       val filter = MeshConfig.debugPeerFilter
       if (filter != null && !filter(decoded.previousHopId)) {
           droppedInvalid.incrementAndGet()
           return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID)
       }
       ```
    2. Check `duplicateCache.isDuplicate(decoded.sessionId, decoded.sourceId, decoded.sequence, dedupKind(decoded), decoded.epoch)` BEFORE decrypting (fast reject for duplicates without mutating the cache).
    3. Verify `sessionManager.canAdmit(decoded.sourceId)` (add non-mutating `canAdmit(deviceId: String): SessionManager.Admission` to `SessionManager` in `OfflineSession.kt` that checks expiry, `lastSeen.containsKey(deviceId)`, and `lastSeen.size >= session.maxDevices` without inserting into `lastSeen`). Also check `canAdmit(decoded.previousHopId)` if relayed.
    4. Run `PayloadCrypto.open(payloadKey, nonce, aad, decoded.payload)` (lines 328-342) and if `decoded.type == MeshPacketType.HELLO`, run `HelloCodec.decode(plain)` (lines 346-350), and if `VOICE`, check `plain.size == MeshConfig.BYTES_PER_FRAME`, and if `BYE`, check `plain.isEmpty()`.
    5. **ONLY AFTER** AEAD decryption and payload format validation succeed:
       - Call `if (!duplicateCache.markIfNew(decoded.sessionId, decoded.sourceId, decoded.sequence, dedupKind(decoded), decoded.epoch)) { droppedDup.incrementAndGet(); return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DUPLICATE) }`
       - Call `sessionManager.admit(decoded.sourceId)` (and `sessionManager.admit(decoded.previousHopId)` if relayed).
       - Dispatch `HELLO` / `BYE` / `VOICE` state updates!
    - Result: A forged or corrupted inner payload can NEVER poison `DuplicatePacketCache` or consume a slot in `SessionManager`.

### 5. `mobile-app/app/src/main/java/com/ridervoice/mesh/routing/RouteCalculator.kt`
- Fix **M7 (Routes Ignore Edge Direction)**:
  - In `RouteCalculator.compute(snapshot: MeshGraphSnapshot, now: Long)` (`line 24`):
    - Change `val live = snapshot.edges.filter { it.expiresAt > now }` to `val live = snapshot.edges.filter { it.expiresAt > now && it.bidirectional }`.
    - This guarantees BFS routes only traverse links confirmed by both endpoints, matching `ForwardingEngine.coveredByDirectHearing()`.

### 6. `mobile-app/app/src/main/java/com/ridervoice/mesh/audio/JitterBuffer.kt` & `OfflineMeshVoiceTransport.kt`
- Fix **L1 (Jitter Buffer Keyed by `(sourceId, epoch)`)**:
  - In `SourceJitterBuffer`, add `val epoch: Int = 0` to constructor (`line 25`).
  - In `MultiSourceJitterBufferManager.onVoicePacket(sourceId: String, sequence: Int, pcmBytes: ByteArray, epoch: Int = 0)` (`line 155`):
    - Check if `buffers[sourceId]` exists and has a different `epoch`. If `existing.epoch != epoch`, replace it with a fresh `SourceJitterBuffer(sourceId, targetDelayFrames, maxQueueFrames, clock = clock, epoch = epoch)` so a restarted sender (`sequence = 1`, new `epoch`) plays immediately without waiting for silence un-priming.
  - In `OfflineMeshVoiceTransport.kt:146`, pass `pkt.epoch`: `jManager.onVoicePacket(pkt.sourceId, pkt.sequence, pkt.payload, pkt.epoch)`.

### 7. `mobile-app/app/src/main/java/com/ridervoice/mesh/diagnostics/MeshDiagnosticsRepository.kt`
- Fix **L2 (`directNeighborCount`)**:
  - In `MeshDiagnosticsRepository.update(...)` (`line 44`), compute:
    `directNeighborCount = graph.edges.count { (it.from == selfDeviceId || it.to == selfDeviceId) && it.endpoint != null }`
    so one-way remote claims are not counted as direct neighbors.

### 8. `mobile-app/app/src/main/java/com/ridervoice/mesh/session/OfflineSession.kt`
- Fix **D9 / C4 (Deterministic Online-to-Offline Session Bootstrap)**:
  - In `OfflineSession.Companion` (`OfflineSession.kt:45-89`), add:
    ```kotlin
    private val BOOTSTRAP_KEY = "rvmesh/v2/online-room-bootstrap".toByteArray(Charsets.UTF_8)

    /**
     * Deterministically derives a shared 16-character pairing code and [OfflineSession]
     * from an online convoy's canonical room identifier (e.g. lkRoomName or roomId).
     */
    fun fromOnlineRoom(
        canonicalRoomKey: String,
        now: Long,
        iterations: Int = SessionKdf.DEFAULT_ITERATIONS
    ): OfflineSession? {
        val clean = canonicalRoomKey.trim().lowercase()
        if (clean.isBlank()) return null
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(BOOTSTRAP_KEY, "HmacSHA256"))
        val digest = mac.doFinal(clean.toByteArray(Charsets.UTF_8))
        // Encode first 80 bits (10 bytes) into 16 Crockford Base32 chars (5 bits each)
        var buffer = 0L
        var bitsLeft = 0
        val sb = StringBuilder(CODE_LENGTH)
        for (i in 0 until 10) {
            buffer = (buffer shl 8) or (digest[i].toInt() and 0xFF).toLong()
            bitsLeft += 8
            while (bitsLeft >= 5 && sb.length < CODE_LENGTH) {
                bitsLeft -= 5
                val idx = ((buffer ushr bitsLeft) and 0x1FL).toInt()
                sb.append(ALPHABET[idx])
            }
        }
        return fromCode(sb.toString(), now, iterations)
    }
    ```

### 9. `mobile-app/app/src/test/java/com/ridervoice/mesh/ImportIsolationTest.kt`
- Fix **L8**: In `frozenOnlineFilesNeverImportMesh()` (`line 55`), replace `if (f.exists())` with `assertTrue("Frozen file must exist: $rel", f.exists())`.

### 10. WP-18 Regression Tests
- Add tests in `AdversarialTest.kt`, `HonestStateTest.kt`, and `SessionKdfTest.kt`:
  - `forgedGcmTagDoesNotPoisonDuplicateCacheOrConsumeMemberSlot`: send a packet with valid outer HMAC but corrupted AES-GCM tag; verify `sessionManager.members()` does not contain the attacker `sourceId` and a subsequent authentic packet with the same `(sourceId, seq, epoch)` is accepted.
  - `oneWayEdgeIsNotSelectedByRouteCalculator`: build a `MeshGraphSnapshot` with an unconfirmed one-way edge (`bidirectional = false`) and a 2-hop bidirectional path; verify `RouteCalculator.compute()` selects the bidirectional path.
  - `debugPeerFilterEnforcesMultiHopOnSharedSubnet`: set `MeshConfig.debugPeerFilter` on node C so it ignores direct packets from A; verify A -> B -> C delivers audio with `hopCount == 1`.
  - `senderRestartWithNewEpochImmediatelyPlaysInJitterBuffer`: enqueue seq 10..15 with `epoch = 1`, then enqueue seq 1 with `epoch = 2`; verify `pollMixedFrame()` delivers the `epoch = 2` frame instead of dropping it as late.
  - `fromOnlineRoomDerivesIdenticalSessionAcrossDevices`: verify `OfflineSession.fromOnlineRoom("convoy_abc_123", now, iterations = 1000)` produces the exact same `sessionId`, `pairingCode`, `authKey`, and `payloadKey` on two devices and differs for `"convoy_xyz_999"`.

---

## WP-19: Online/Offline Arbiter Bridge, Non-Disconnecting Handoff & Stage 3 Production Wiring (Fixes C1, C2, C3, C4, D2, H6)

### 1. `mobile-app/app/src/main/java/com/ridervoice/mesh/transport/LiveKitPort.kt` & `LiveKitVoiceTransport.kt`
- In `LiveKitPort.kt`, extend the interface (with default implementations so existing test fakes compile without breaking!):
  ```kotlin
  interface LiveKitPort {
      val connectionState: StateFlow<ConnectionState>
      val activeSpeaker: StateFlow<String?>
      val participants: StateFlow<List<Participant>>
      val isUserMuted: StateFlow<Boolean> get() = MutableStateFlow(false)
      val deafened: StateFlow<Boolean> get() = MutableStateFlow(false)
      suspend fun connect(url: String, roomId: String, tokenProvider: suspend () -> String, expectedLkRoomName: String?)
      fun disconnect()
      fun setUserMuted(muted: Boolean)
      fun setDeafened(deafened: Boolean)
      fun onVoiceGate(open: Boolean) {}
      fun onNetworkRestored() {}
      fun stopVox() {}
  }
  ```
- In `LiveKitManagerPort` (`LiveKitVoiceTransport.kt:15-24`):
  - Accept `private val m: LiveKitManager, private val voxStopper: () -> Unit = {}`.
  - Override `isUserMuted` -> `m.isUserMuted`, `deafened` -> `m.deafened`, `onVoiceGate(open)` -> `m.onVoiceGate(open)`, `onNetworkRestored()` -> `m.onNetworkRestored()`, `stopVox()` -> `voxStopper()`.
- In `LiveKitVoiceTransport` (`LiveKitVoiceTransport.kt:33-158`):
  - Track:
    ```kotlin
    @Volatile private var savedUserMutedBeforePause: Boolean = false
    @Volatile private var savedDeafenedBeforePause: Boolean = false
    @Volatile var pausedByCoordinator: Boolean = false
        private set
    ```
  - **Fix C2 (Arbiter observes real online ride automatically)** & **Fix D2/C3 (Background reconnect suppression while paused)**:
    In `init` (`lines 68-78`), inside `liveKitPort.connectionState.collectLatest { conn -> ... }`:
    ```kotlin
    when (conn) {
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING -> {
            if (pausedByCoordinator) {
                // Keep LiveKit silent while offline mesh owns audio
                liveKitPort.onVoiceGate(false)
                liveKitPort.setUserMuted(true)
                liveKitPort.setDeafened(true)
                liveKitPort.stopVox()
                if (conn == ConnectionState.CONNECTED) {
                    delay(250L) // Defeat LiveKitManager.startVoxSafely() 150ms delay
                    if (pausedByCoordinator) {
                        liveKitPort.onVoiceGate(false)
                        liveKitPort.setUserMuted(true)
                        liveKitPort.setDeafened(true)
                        liveKitPort.stopVox()
                    }
                }
            } else if (audioOwnershipArbiter.owner.value != AudioOwner.OFFLINE_MESH) {
                audioOwnershipArbiter.claimLiveKit()
            }
        }
        ConnectionState.DISCONNECTED,
        ConnectionState.FAILED -> {
            if (!pausedByCoordinator && audioOwnershipArbiter.owner.value == AudioOwner.LIVEKIT) {
                audioOwnershipArbiter.release(AudioOwner.LIVEKIT)
            }
        }
    }
    ```
  - **Fix H6 (Preserve user mute & deafen state across pause/resume)**:
    ```kotlin
    override suspend fun pause() {
        if (pausedByCoordinator) return
        savedUserMutedBeforePause = liveKitPort.isUserMuted.value
        savedDeafenedBeforePause = liveKitPort.deafened.value
        pausedByCoordinator = true
        _micState.value = MicState.MUTED
        liveKitPort.onVoiceGate(false)
        liveKitPort.setUserMuted(true)
        liveKitPort.setDeafened(true)
        liveKitPort.stopVox()
    }

    override suspend fun resume() {
        if (!pausedByCoordinator) return
        pausedByCoordinator = false
        val restoreMuted = savedUserMutedBeforePause
        val restoreDeafened = savedDeafenedBeforePause
        userMicEnabled = !restoreMuted
        _micState.value = if (userMicEnabled) MicState.ON else MicState.OFF
        liveKitPort.setDeafened(restoreDeafened)
        liveKitPort.setUserMuted(restoreMuted)
    }

    fun triggerBackgroundReconnect() {
        liveKitPort.onNetworkRestored()
    }
    ```

### 2. `mobile-app/app/src/main/java/com/ridervoice/mesh/transport/VoiceSessionCoordinator.kt`
- Fix **D2, C3, C4**:
  - Add `fun setOnlineSession(session: VoiceSession.Online?) { lastOnlineSession = session }`.
  - Add `suspend fun ensureWarmStandbyDiscovery(session: OfflineSession)`:
    - If `meshTransport is MeshControl` and `meshTransport.state.value == VoiceTransportState.STOPPED`, calls `(meshTransport as MeshControl).startDiscoveryOnly(session)`.
  - Update `fallbackToMesh()` (`lines 101-125`):
    1. Check `val mesh = meshTransport as? MeshControl ?: return@withLock false`.
    2. Check `if (!mesh.hasValidRoute.value) return@withLock false`.
    3. Suspend LiveKit via arbiter handoff:
       `audioOwnershipArbiter.handoffLiveKitToOfflineMesh { liveKitTransport.pause() }`
    4. Try enabling mesh audio:
       ```kotlin
       var ok = runCatching {
           mesh.enableAudio()
           meshTransport.micState.value != MicState.HARDWARE_BUSY
       }.getOrDefault(false)
       ```
    5. Hardware-busy fallback: if `!ok` (e.g., older Android device where paused WebRTC ADM still holds `AudioRecord`), call `liveKitTransport.stop()`, re-claim `audioOwnershipArbiter.tryClaimOfflineMesh()`, and retry `mesh.enableAudio()` once.
    6. If `!ok`, roll back: `runCatching { mesh.disableAudio() }`, `audioOwnershipArbiter.claimLiveKit()`, `liveKitTransport.resume()` (or `liveKitTransport.start(online)` if stopped).
    7. If `ok`, set `_activeKind.value = PeerTransportKind.OFFLINE_MESH` and `_activeTransport.value = meshTransport`.
  - Update `recoverToLiveKit()` (`lines 133-156`):
    1. Check `val mesh = meshTransport as? MeshControl ?: return@withLock false`.
    2. Disable mesh audio first: `mesh.disableAudio()` and `audioOwnershipArbiter.release(AudioOwner.OFFLINE_MESH)`.
    3. If `liveKitTransport.state.value != VoiceTransportState.CONNECTED`:
       - If `liveKitTransport is LiveKitVoiceTransport`, call `liveKitTransport.triggerBackgroundReconnect()`.
       - Else if `lastOnlineSession != null`, call `runCatching { liveKitTransport.start(lastOnlineSession!!) }`.
    4. Wait up to `15_000L` ms for `liveKitTransport.state.first { it == VoiceTransportState.CONNECTED }`.
    5. If connected: call `audioOwnershipArbiter.claimLiveKit()`, `liveKitTransport.resume()`, set `_activeKind.value = PeerTransportKind.ONLINE_LIVEKIT`, `_activeTransport.value = liveKitTransport`, and return `true`.
    6. If not connected: roll back to mesh (`if (audioOwnershipArbiter.tryClaimOfflineMesh()) runCatching { mesh.enableAudio() }`) and return `false`.

### 3. `mobile-app/app/src/main/java/com/ridervoice/mesh/switching/SwitchingModels.kt` & `LocalHealthSignalsProvider.kt`
- Fix **Stage 3.1, 3.3, 3.4, 3.5**:
  - In `SwitchingModels.kt`:
    - Add group status enum required by Stage 3.5:
      ```kotlin
      enum class GroupConnectivityStatus {
          ENTIRE_GROUP_ONLINE,
          LOCAL_ONLINE_SOME_PEERS_OFFLINE,
          LOCAL_OFFLINE_MESH_ACTIVE
      }
      ```
    - In `LocalHealthSignals`:
      - Update `isOnlineFailing()` (`line 27`):
        ```kotlin
        fun isOnlineFailing(): Boolean =
            (!isValidatedInternet || isLiveKitReconnecting || liveKitReconnectAttempts >= 2) &&
                !isLiveKitConnected && !isHealthProbeSuccessful
        ```
      - Add `fun shouldTriggerBackgroundReconnect(): Boolean = isValidatedInternet && isHealthProbeSuccessful && !isLiveKitConnected && !isLiveKitReconnecting`.
  - In `LocalHealthSignalsProvider.kt`:
    - Add `onEmitTransitionDiagnostic: (from: AutoSwitchState, to: AutoSwitchState, reason: String) -> Unit = { _, _, _ -> }` and `onProbeMesh: (suspend () -> Unit)? = null` to `LocalHealthSignalsProvider.createMachine(...)` (`lines 107-116`) so transition diagnostics and discovery probing are wired!

### 4. Create `mobile-app/app/src/main/java/com/ridervoice/mesh/switching/MeshAutoSwitchManager.kt` & Wire in `MeshModule.kt` and `App.kt`
- Create `@Singleton class MeshAutoSwitchManager @Inject constructor(...)` in `mesh/switching/MeshAutoSwitchManager.kt`:
  - Injects:
    - `activeRideRepository: ActiveRideRepository`
    - `liveKitManager: LiveKitManager`
    - `networkObserver: NetworkObserver`
    - `coordinator: VoiceSessionCoordinator`
    - `meshTransport: OfflineMeshVoiceTransport`
    - `identityRepo: MeshIdentityRepository`
  - Exposes:
    - `val autoSwitchState: StateFlow<AutoSwitchState>`
    - `val groupStatus: StateFlow<GroupConnectivityStatus>`
    - `val lastTransitionReason: StateFlow<String?>`
  - Behavior (`fun start()` called from `App.onCreate()`):
    - Observes `activeRideRepository.state`:
      - When `ride == null`: calls `stateMachine.endSession()`, stops warm-standby mesh if it was started by auto-switch, resets `coordinator.setOnlineSession(null)`.
      - When `ride != null`:
        - Syncs rider display name: `if (ride.displayName.isNotBlank()) identityRepo.setDisplayName(ride.displayName)` (**Fixes M8**).
        - Populates `coordinator.setOnlineSession(VoiceSession.Online(url = ride.livekitUrl?.takeIf { it.isNotBlank() } ?: Constants.LIVEKIT_URL, roomId = ride.roomId, tokenProvider = { activeRideRepository.ensureFreshToken().getOrThrow() }, expectedLkRoomName = ride.lkRoomName))`.
        - Derives `val companionSession = withContext(Dispatchers.Default) { OfflineSession.fromOnlineRoom(ride.lkRoomName?.takeIf { it.isNotBlank() } ?: ride.roomId, System.currentTimeMillis()) }`.
        - While ride is active, runs the 1-second evaluation loop:
          - If `OfflineMeshGate.isWarmStandbyAllowed() || OfflineMeshGate.isAutomaticFallbackAllowed()` and `companionSession != null` and `meshTransport.state.value == VoiceTransportState.STOPPED`: starts `coordinator.ensureWarmStandbyDiscovery(companionSession)`.
          - Samples `signals = healthSignalsProvider.sampleSignals()`.
          - If `stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE && signals.shouldTriggerBackgroundReconnect()`: calls `(coordinator.liveKitTransport as? LiveKitVoiceTransport)?.triggerBackgroundReconnect()`.
          - Calls `stateMachine.evaluate(signals)`.
          - Updates `groupStatus`:
            - `LOCAL_OFFLINE_MESH_ACTIVE` when `stateMachine.state.value == AutoSwitchState.OFFLINE_ACTIVE`
            - `LOCAL_ONLINE_SOME_PEERS_OFFLINE` when local is `ONLINE` and either `liveKitManager.participants.value.any { it.isGhost }` or `meshTransport.participants.value.any { it.isOnline }`
            - `ENTIRE_GROUP_ONLINE` otherwise.
- In `di/MeshModule.kt`:
  - Provide `@Singleton LocalHealthSignalsProvider` and `@Singleton MeshAutoSwitchManager`.
  - Pass `voxEngine::stop` into `LiveKitVoiceTransport` and real `hasMicPermission` (`ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED`) into `OfflineMeshVoiceTransport`.
- In `App.kt`:
  - `@Inject lateinit var meshAutoSwitchManager: com.ridervoice.mesh.switching.MeshAutoSwitchManager`
  - Call `meshAutoSwitchManager.start()` in `onCreate()` after `OfflineMeshGate.install(...)`.

### 5. WP-19 Regression Tests
- Add tests in `WiringTest.kt` and `VoiceSessionCoordinatorTest.kt`:
  - `onlineLiveKitConnectionAutomaticallyClaimsAndReleasesArbiter`: emit `ConnectionState.CONNECTED` on `LiveKitPort` without calling `LiveKitVoiceTransport.start()`; verify `arbiter.owner.value == AudioOwner.LIVEKIT`, and `OfflineMeshViewModel.isMicOwnedByLiveKit()` is `true`. Emit `ConnectionState.DISCONNECTED`; verify `arbiter.owner.value == AudioOwner.NONE`.
  - `fallbackSuspendsLiveKitAndBackgroundReconnectStaysMutedUntilHandback`: verify `fallbackToMesh()` pauses LiveKit (does not call `disconnect()`), keeps LiveKit muted/deafened even when `ConnectionState.CONNECTED` re-emits during `OFFLINE_ACTIVE`, and `recoverToLiveKit()` restores the exact pre-fallback `isUserMuted` state (fixing **H6**).

---

## WP-20: Offline Audio Routing, Real Permission Gate, PTT/VOX & Thread Priority (Fixes H1, H2, H3, M5, L3)

### 1. Create `mobile-app/app/src/main/java/com/ridervoice/mesh/audio/MeshAudioRouteController.kt` (Fixes H2)
- Implement `MeshAudioRouteController(private val context: Context?)`:
  - Exposes `val activeRoute: StateFlow<AudioRoute>`.
  - `fun acquireCommunicationMode()`: requests `AudioFocusRequest` (`USAGE_VOICE_COMMUNICATION`), sets `audioManager.mode = AudioManager.MODE_IN_COMMUNICATION`, and selects Bluetooth SCO/CommunicationDevice if a Bluetooth headset is connected, else Wired Headset, else Speakerphone.
  - `fun setRoute(route: AudioRoute)`: switches between `BLUETOOTH`, `SPEAKER`, `WIRED`, `EARPIECE` using `AudioManager.setCommunicationDevice(...)` on API 31+ and `startBluetoothSco()`/`stopBluetoothSco()`/`isSpeakerphoneOn` on API 26–30.
  - `fun releaseCommunicationMode()`: stops SCO / clears communication device, restores `AudioManager.MODE_NORMAL`, abandons audio focus.
- Wire `MeshAudioRouteController` into `OfflineMeshVoiceTransport` (`enableAudio()`, `disableAudio()`, `stop()`, `setAudioRoute()`) and provide it in `MeshModule.kt`.

### 2. `mobile-app/app/src/main/java/com/ridervoice/mesh/audio/OfflineAudioCapture.kt` & `OfflineAudioPlayback.kt` (Fixes H1, M5, L3)
- Fix **L3**: In `AndroidAudioRecordSource` (`OfflineAudioCapture.kt:33-41`), accept optional `private val effects: List<android.media.audiofx.AudioEffect> = emptyList()` and in `release()` call `effects.forEach { runCatching { it.release() } }` before `rec.release()`. Store the created `AcousticEchoCanceler`, `NoiseSuppressor`, and `AutomaticGainControl` instances in that list.
- Fix **M5**: At the start of the capture coroutine in `OfflineAudioCapture.kt:134` and playback coroutine in `OfflineAudioPlayback.kt:88`, call:
  `runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }`.
- Fix **H1 (Single-`AudioRecord` VOX + PTT in Offline Mesh)**:
  - Add `var frameGate: ((ByteArray) -> Boolean)? = null` to `OfflineAudioCapture`:
    - When `frameGate != null`, `OfflineAudioCapture` passes each captured 640-byte PCM frame to `frameGate(frameBuf)` and only invokes `onPcmFrame(frameBuf.copyOf())` when `frameGate` returns `true`.
  - In `MeshVoxBridge.kt`:
    - Add a built-in `VoxDetector` (`com.ridervoice.audio.VoxDetector()`) mode (`val voxEnabled: StateFlow<Boolean>`, `val isPttActive: StateFlow<Boolean>`, `val isGateOpen: StateFlow<Boolean>`).
    - Provide `fun shouldTransmitPcmFrame(pcmBytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean`:
      - If `isPttHeld`: returns `true`.
      - If `!voxEnabled.value`: returns `false`.
      - Converts `pcmBytes` (little-endian 16-bit PCM, 320 shorts) to `ShortArray(320)`, runs `voxDetector.onFrame(shorts, 16_000, nowMs, isVoxSpeechActive)`, updates `isVoxSpeechActive`, and returns `isVoxSpeechActive`.
    - This gives full hands-free VOX + UI PTT + Hardware Helmet PTT in offline mesh using the **single** `OfflineAudioCapture` `AudioRecord` (never opening a second `AudioRecord`!).

### 3. `mobile-app/app/src/main/java/com/ridervoice/services/MeshForegroundService.kt` (Fixes H3, M4, D6)
- Fix **M4 (Foreground Service Crash Window)**:
  - Create helper `private fun ensureStartedForeground(notification: Notification)` that calls `startForeground(...)` inside `try/catch (e: Throwable)` (checking `ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED` before passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE`).
  - In `onStartCommand()` (`lines 84-109`), call `ensureStartedForeground(buildNotification(currentSessionCode, currentPeerCount))` **immediately at the top of `onStartCommand()`** before any `if (...) { stopSelf(); return START_NOT_STICKY }` branch!
- Fix **D6 (Mask Pairing Code in Notification)**:
  - Create `fun maskSessionCode(code: String): String`: if `code.length >= 8`, return `"${code.take(4)}-••••-••••-${code.takeLast(4)}"`, else `"••••"`.
  - Use `maskSessionCode(sessionCode)` in `buildNotification()` so the raw 80-bit session secret is never exposed in notifications or status bar logs.

### 4. WP-20 Regression Tests
- Add unit tests:
  - `meshVoxBridgeSingleCaptureGatesFramesByVoxAndPtt`: feed silence frames then speech-level sine wave frames to `MeshVoxBridge.shouldTransmitPcmFrame`; verify silence is blocked, speech opens gate after attack window, and PTT override transmits immediately.
  - `revokedMicPermissiontransitionsToPermissionDeniedNotHardwareBusy`: set `hasMicPermission = { false }` on `OfflineMeshVoiceTransport`; call `setMicrophoneEnabled(true)` and verify `micState.value == MicState.PERMISSION_DENIED`.

---

## WP-21: Wi-Fi Network Binding & Lifecycle/UI Thread Fixes (Fixes H4, M1, M2, M3, M6, D3, D4, D5)

### 1. `mobile-app/app/src/main/java/com/ridervoice/mesh/link/NetworkLocks.kt` & `UdpMeshLink.kt` (Fixes H4, M5)
- In `NetworkLocks.kt`:
  - Add `fun bindSocketToWifi(socket: java.net.DatagramSocket)` to `NetworkLocks` (default `{}` in `NoNetworkLocks`).
  - In `AndroidNetworkLocks(context: Context)`:
    - Implement `bindSocketToWifi(socket: DatagramSocket)` using `ConnectivityManager`:
      ```kotlin
      val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
      val wifiNetwork = cm?.allNetworks?.firstOrNull { net ->
          cm.getNetworkCapabilities(net)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
      }
      if (wifiNetwork != null) {
          runCatching { wifiNetwork.bindSocket(socket) }
      }
      ```
  - In `UdpMeshLink.discoverBroadcastEndpoints(port: Int)`:
    - Prefer interfaces whose name starts with `wlan`, `ap`, `p2p`, or `swlan` when present so broadcasts go out on the Wi-Fi radio.
  - In `MeshModule.provideOfflineMeshVoiceTransport`:
    - Pass `networkBinder = { sock -> locks.bindSocketToWifi(sock) }` to `UdpMeshLink`.

### 2. `mobile-app/app/src/main/java/com/ridervoice/mesh/transport/OfflineMeshVoiceTransport.kt` (Fixes M3, M6)
- Fix **M3 (`BYE` on IO Dispatcher + 2x Send)**:
  - In `stop()` (`line 300`), run `withContext(dispatcher)` around `forwardingEngine?.originateBye()` (calling `originateBye()` twice with `delay(10L)` for UDP loss resilience) and `link?.stop()`.
- Fix **M6 (Coroutine Exception Resilience)**:
  - Wrap the body of `heartbeatJob` (`line 184`), `frameListenerJob` (`line 157`), and `peerProjectorJob` (`line 193`) in `try { ... } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}` so a transient socket or parsing error never kills the transport loops.

### 3. `mobile-app/app/src/main/java/com/ridervoice/mesh/ui/OfflineMeshViewModel.kt`, `OfflineMeshLobbyScreen.kt` & `NavGraph.kt` (Fixes M1, M2, D5)
- Fix **M1 (Offload PBKDF2 from UI Thread)** & **D5 (Reject Invalid Code Instead of Silent Substitution)**:
  - In `OfflineMeshViewModel.kt`:
    - Add `val isDerivingKey: StateFlow<Boolean>`, `val sessionError: StateFlow<Boolean>`, and `val activeSession: StateFlow<OfflineSession?>`.
    - Add `fun createNewSession(onReady: (String) -> Unit)` and `fun joinWithCode(rawCode: String, onReady: (String) -> Unit, onInvalid: () -> Unit)` that run `OfflineSession.create(...)` / `OfflineSession.fromCode(...)` inside `viewModelScope.launch { withContext(Dispatchers.Default) { ... } }` and cache the resulting `OfflineSession`.
    - In `NavGraph.kt` (`Routes.OFFLINE_MESH_HUD`, `lines 386-450`):
      - Do **NOT** call `OfflineSession.fromCode(...) ?: OfflineSession.create(...)` synchronously in `remember(sessionCode)`!
      - Instead, resolve the session asynchronously on `Dispatchers.Default` via `viewModel.resolveAndStartSession(sessionCode)`. If `OfflineSession.fromCode` returns `null`, navigate back (`navController.popBackStack()`) instead of silently creating a random session (**Fixes D5**).
- Fix **M2 (Screen Rotation Must Not Stop Session)**:
  - Remove `DisposableEffect(sessionCode) { onDispose { viewModel.stopSession() ... } }` from `NavGraph.kt:417-423`.
  - Instead, call `viewModel.stopSession()` and `MeshForegroundService.stop(context)` inside `OfflineRoomScreen`'s `onLeave` callback (`NavGraph.kt:447-449`) and inside `OfflineMeshViewModel.onCleared()`.
  - In `AndroidManifest.xml:37-39`, add `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"` to `.MainActivity`.

### 4. `mobile-app/app/src/main/AndroidManifest.xml` & `SettingsScreen.kt` (Fixes D3, D4)
- In `AndroidManifest.xml`:
  - Remove `<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />` and `<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" ... />` (lines 6, 8–11). Keep `ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE`.
- In `SettingsScreen.kt` (`lines 200-206`):
  - Do **NOT** request `Manifest.permission.NEARBY_WIFI_DEVICES`. Request `Manifest.permission.RECORD_AUDIO` (and on API 33+, optionally `Manifest.permission.POST_NOTIFICATIONS`, while enabling the mesh toggle as long as `RECORD_AUDIO` is granted).
  - Add a third toggle under `OFFLINE MESH INTERCOM`: **Warm standby discovery** (`meshPrefs.warmStandbyEnabled` / `meshPrefs.setWarmStandbyEnabled(enable)`).

---

## WP-22: Complete Stage 2.5 & 3.5 UI, `OfflineAwareRoomHost` & 100% EN/HI/MR Localization (Fixes L6, L7)

### 1. Create `mobile-app/app/src/main/java/com/ridervoice/mesh/ui/OfflineAwareRoomHost.kt` (Fixes Stage 2.5 & 3.5)
- Wrap `RoomScreen` inside `NavGraph.kt` at `Routes.ACTIVE_RIDE_HUD` (`NavGraph.kt:225`) with `OfflineAwareRoomHost` (keeping `RoomScreen.kt` 100% untouched!).
- `OfflineAwareRoomHost` observes `MeshAutoSwitchManager.autoSwitchState`, `MeshAutoSwitchManager.groupStatus`, and `VoiceSessionCoordinator`:
  - When `OfflineMeshGate.isOfflineAllowed()` is `false` or state is `ONLINE` with `ENTIRE_GROUP_ONLINE`: renders `RoomScreen` with zero visual change.
  - When state is `ONLINE_SUSPECT`, `STARTING_OFFLINE_MESH`, `OFFLINE_ACTIVE`, `ONLINE_RECOVERING`, `HANDING_BACK_TO_LIVEKIT`, or `FALLBACK_UNAVAILABLE`, or when `groupStatus == LOCAL_ONLINE_SOME_PEERS_OFFLINE`:
    - Renders a compact tactical banner at the top of the screen showing:
      - Current auto-switch status (e.g., `"Offline mesh active"`, `"Online voice restored"`, `"Checking connection..."`)
      - Group connectivity status badge (`"Entire known group online"`, `"Local online recovered · Some peers still offline"`, `"Offline mesh active"`)
      - Active mesh peer count & max hops when on mesh, with a button to view the mesh peer/diagnostics sheet.

### 2. Upgrade `mobile-app/app/src/main/java/com/ridervoice/mesh/ui/OfflineRoomScreen.kt` (Fixes Stage 2.5 & L6)
- Display all required Stage 2.5 fields:
  - Resolve `peer.nextHopId` to the relay peer's display name (`peers.find { it.id == peer.nextHopId }?.name ?: peer.nextHopId.take(8)`).
  - Display route age (`peer.routeAgeMs / 1000` seconds) on each `MeshPeerCard`.
  - Display mesh health indicator, current `AudioRoute` (`SPEAKER` / `BLUETOOTH` / `WIRED` / `EARPIECE`) with a cycle/selector button, VOX toggle button, and press-and-hold PTT button in `MeshBottomBar`.
  - Replace raw `transportState.name` and all hardcoded English strings in `OfflineRoomScreen.kt`, `MeshForegroundService.kt`, and `SettingsScreen.kt` (mesh section) with localized `@StringRes` resources in `values/strings.xml`, `values-hi/strings.xml`, and `values-mr/strings.xml`.
- Update `ImportIsolationTest.kt` `meshImportAllowlist` if any new host file outside `mesh/` imports `mesh`, or keep `OfflineAwareRoomHost.kt` inside `com.ridervoice.mesh.ui` (already allowed!).

---

## WP-23: GitHub Actions CI/CD, Release Workflow & ProGuard Hardening (Fixes CI-1, CI-2, CI-3, CI-4, D7)

### 1. `mobile-app/app/build.gradle` & `FeatureFlags.kt` (Fixes D7 / CI-4)
- In `mobile-app/app/build.gradle` (`line 130`), change the `release` `buildConfigField` to:
  ```groovy
  def enableMeshInRelease = project.hasProperty('enableOfflineMeshInRelease') && Boolean.parseBoolean(project.property('enableOfflineMeshInRelease').toString())
  buildConfigField "boolean", "OFFLINE_MESH_BUILD_ENABLED", enableMeshInRelease ? "true" : "false"
  ```
  Default remains `false` for all production releases, while allowing `-PenableOfflineMeshInRelease=true` for Step 30 internal tester builds.

### 2. `mobile-app/app/proguard-rules.pro` (Fixes CI-3)
- Append keep rules for mesh models and diagnostics:
  ```proguard
  # Offline Mesh Models & Diagnostics
  -keep class com.ridervoice.mesh.continuity.** { *; }
  -keep class com.ridervoice.mesh.diagnostics.** { *; }
  ```

### 3. `.github/workflows/build.yml` (Fixes CI-1)
- In `validate-android` job (`.github/workflows/build.yml:34-46`), add steps to:
  1. Verify frozen online files have 0 diff against `a37ef09`:
     ```yaml
     - name: Verify Frozen Online Files Unchanged
       run: |
         git diff --exit-code a37ef09 -- \
           mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt \
           mobile-app/app/src/main/java/com/ridervoice/network/ConnectionState.kt \
           mobile-app/app/src/main/java/com/ridervoice/network/NetworkResilienceManager.kt \
           mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt \
           mobile-app/app/src/main/java/com/ridervoice/state/ActiveRideRepository.kt \
           mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt \
           mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt \
           mobile-app/app/src/main/java/com/ridervoice/audio \
           mobile-app/app/src/main/java/com/ridervoice/diagnostics
     ```
  2. Run `lintDebug`:
     ```yaml
     - name: Run Android Lint
       working-directory: mobile-app
       run: ./gradlew lintDebug
     ```
  3. Enforce minimum unit test count (`>= 290`, `0` failures, `0` skipped) from JUnit XML reports.
  4. Verify R8/ProGuard release compilation (`ALLOW_INSECURE_RELEASE_BUILD=true ./gradlew assembleRelease`).

### 4. `.github/workflows/release.yml` (Fixes CI-2)
- In `.github/workflows/release.yml:181-183`, extract only the current version's section from `RELEASE_NOTES.md` (using `awk` with a 15,000-byte truncation guard `head -c 15000`) instead of `cat RELEASE_NOTES.md`.

---

## WP-24: Documentation & Specification Reconciliation (Fixes Section 4.3 Hallucinations)

Update all documentation files so **zero claims contradict the code**:
1. `docs/protocol/mesh-v2.md`:
   - Port: `45454` (`MeshConfig.AUDIO_PORT`)
   - Magic: `0x52564D31` (`"RVM1"`)
   - Version: `2` (`0x02`)
   - Packet types: `HELLO(1)`, `VOICE(2)`, `BYE(3)`
   - Header length: `75` bytes (`4+1+1+16+16+4+8+1+1+16+1+4+2`)
   - Outer authentication tag: `16` bytes (truncated HMAC-SHA256)
   - Inner payload encryption: AES-256-GCM with 12-byte deterministic nonce (`epoch(4) + sequence(4) + typeCode(1) + sourceIdPrefix(3)`), 16-byte GCM tag, AAD binding `sessionId + sourceId + typeCode + epoch + sequence + captureTimestamp + audioFormat`
   - Voice datagram size: `75` (header) + `640` (PCM) + `16` (GCM tag) + `16` (hop HMAC tag) = `747` bytes
   - KDF: `PBKDF2WithHmacSHA256`, `120,000` iterations, salt `"rvmesh/v2/session"`, 80-byte output (`0..15` session UUID, `16..47` authKey, `48..79` payloadKey), 16-char Crockford Base32 alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`.
   - Include a unit test (`ProtocolSpecVectorTest.kt`) that asserts the exact constants and byte offsets documented in `docs/protocol/mesh-v2.md`.
2. `docs/implementation/mesh/state-machine.md`:
   - Document the real 8 states of `AutoSwitchState` (`ONLINE`, `ONLINE_SUSPECT`, `STARTING_OFFLINE_MESH`, `OFFLINE_ACTIVE`, `ONLINE_RECOVERING`, `HANDING_BACK_TO_LIVEKIT`, `FALLBACK_UNAVAILABLE`, `SESSION_ENDED`) and `AntiFlappingConfig` (`10_000L` ms failure confirmation, `8_000L` ms recovery stability, `20_000L` ms min dwell, `3` transitions per `120_000L` ms window).
3. `docs/implementation/mesh/README.md`, `docs/security/mesh-threat-model.md`, `docs/testing/mesh-test-plan.md`, `docs/SETTINGS_CONTRACT.md`, `README.md`, `ARCHITECTURE.md`, `CODE_DEPTH_STRUCTURE.md`, `RIDERVOICE_SYSTEM_EXPLANATION.md`:
   - Remove all references to `"bloom filter"`, `"9-state"`, `"port 40001"`, `"port 4242"`, `"55-byte"`, `"727-byte"`, `"HKDF"`, `"hardware-rooted UUID"`, `"LRU"`, `"Wi-Fi Direct"` (unless clearly marked future link layer), and non-existent file names (`DirectNeighbor.kt`, `MeshFrame.kt`, `AudioOwner.kt`, `LocalHealthSignals.kt`).
   - Update `docs/SETTINGS_CONTRACT.md` with the exact SharedPreferences keys in `mesh_runtime_prefs`: `offline_mesh_enabled`, `auto_fallback_enabled`, `warm_standby_enabled`.
   - Update `docs/testing/mesh-test-plan.md` and `docs/implementation/mesh/AUDIT_FACTS.md` with the exact JUnit XML test census after all new tests are added.

---

# FINAL ACCEPTANCE VERIFICATION CHECKLIST

Before declaring completion, run and report the exact output of every command below:

1. **Frozen Files Zero-Diff Proof:**
   ```bash
   git diff --stat a37ef09 -- \
     mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt \
     mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt \
     mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt \
     mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt \
     mobile-app/app/src/main/java/com/ridervoice/audio \
     mobile-app/app/src/main/java/com/ridervoice/diagnostics
   ```
   *(Expected: empty output / 0 files changed)*

2. **Production Wiring Proof (`git grep`):**
   ```bash
   git grep -n -E "AutomaticSwitchStateMachine|LocalHealthSignalsProvider|VoiceSessionCoordinator|MeshAutoSwitchManager" -- mobile-app/app/src/main
   ```
   *(Expected: constructed in `MeshModule.kt`, started in `App.kt`, observed in `OfflineAwareRoomHost.kt`)*

3. **Zero Hallucinated Doc Claims Proof (`git grep`):**
   ```bash
   git grep -n -i -E "bloom filter|9-state|40001|port 4242|55-byte|727-byte|0x52564D32|HKDF" -- README.md ARCHITECTURE.md CODE_DEPTH_STRUCTURE.md RIDERVOICE_SYSTEM_EXPLANATION.md RELEASE_NOTES.md docs/
   ```
   *(Expected: empty output)*

4. **Unit Tests, Lint, Debug & Release APK Build:**
   ```bash
   cd mobile-app
   ./gradlew testDebugUnitTest lintDebug assembleDebug -PallowInsecureRelease=true
   ALLOW_INSECURE_RELEASE_BUILD=true ./gradlew assembleRelease
   ```
   *(Expected: `BUILD SUCCESSFUL`, total unit tests > 300, 0 failures, 0 skipped, 0 lint errors)*
```
