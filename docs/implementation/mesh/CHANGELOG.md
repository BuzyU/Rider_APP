# RiderVoice Offline Mesh — Work Package Changelog

This changelog consolidates and replaces all historical work package reports (`WP_REPORTS/`, `archive/`, `FINAL_AUDIT.md`, `POST_REMEDIATION_PLAN_CONFORMANCE_REPORT.md`, `CLEANUP_LOG.md`, and `BASELINE.md`).

---

## Round 3 Remediation (WP-43 – WP-52)

### WP-52: Test Quality, Stress Smoke Test, and Final Verification
- **Commit:** `636ed92`
- **Summary:**
  - Added explicit Stage 1 gate warning banner to `docs/testing/PHYSICAL_TEST_CHECKLIST.md` confirming all 18 scenarios remain NOT EXECUTED.
  - Refactored `ProductionWiringTest.kt` (B20) to eliminate all source-text reading in favor of 100% genuine reflection and behavioural checks across all 7 assertions.
  - Implemented `ArchitectureRulesTest.kt` (B20) with 5 named architecture rules enforcing module import isolation, frozen LiveKit contracts, approved Wi-Fi permissions, and debug receiver manifest guards.
  - Built `StressSmokeTest.kt` (B27) executing a 6-node linear mesh with 5,000 voice frames, 5% random link drop, on `Dispatchers.Default` validating zero duplicate local playback and reliable delivery across 5 hops.
  - Updated `DEFECT_REGISTER.md` marking B20 and B27 FIXED.
  - Incremented authoritative test census floor in `.github/test-census-floor` to 483 tests.

### WP-51: Documentation Consolidation, CI Hardening, and Test Census Freeze
- **Commit:** `eac28fd`
- **Summary:**
  - Deleted 25 historical doc files (`archive/**`, `WP_REPORTS/**`, `FINAL_AUDIT.md`, `POST_REMEDIATION_PLAN_CONFORMANCE_REPORT.md`, `CLEANUP_LOG.md`, `BASELINE.md`).
  - Rewrote `docs/implementation/mesh/README.md` (≤ 80 lines) with accurate architecture, package structure, test commands, and links to core docs.
  - Fixed dangling reference in `DEFECT_REGISTER.md` to `CHANGELOG.md:12`, updated defect statuses for A21, A37, B8, B19, B22, B25, validated by `verify-test-refs.sh`.
  - Replaced permanent freeze with `.github/scripts/mesh-commit-guard.sh` (≤ 40 lines) enforcing LiveKit contract freeze on `WP-` and `mesh` commits.
  - Hardened `.github/scripts/mesh-invariants.sh` (removed banned words, enforce path existence, check `MeshConfig.* =`, verify `MeshDebugReceiver` absent from release manifest, verify test census against floor).
  - Recorded authoritative test census floor `477` in `.github/test-census-floor` and updated `.github/workflows/build.yml`.
  - Added internal-tester build input `enableOfflineMeshInRelease` to `release.yml`.
  - Fixed `ModifierParameter` Compose lint issue on `OfflineRoomScreen.kt` and cleanly removed from `lint-baseline.xml`. Verified BOM pins preserved per Gradle resolution requirement.

### WP-50: Dead Code & Dependency Removal
- **Commit:** `ec8f97e`
- **Summary:**
  - Deleted Stage 4 backend continuity stubs (`ContinuityConfig.kt`, `ContinuityLeaseClient.kt`, `ContinuityModels.kt`), ProGuard rule, and test `backendContinuityAcceptance_featureFlaggedAndDefaultOff`. Recorded "Stage 4 not built" in `DECISIONS.md`.
  - Moved `FakeAudioManagerPort` and `InMemoryMeshKeyValueStore` from `src/main` to `src/test`.
  - Deleted unused `JitterBufferStats` and `getBufferStats` from `JitterBuffer.kt`.
  - Removed static facade from `OfflineSessionHolder` (`currentSession`, `getHolder`, `globalInstance`); migrated callers to Hilt singleton injection / constructor injection.
  - Deleted unused `mesh_*` string resources (`mesh_state_discovering`, `mesh_state_unavailable`, `mesh_cd_wifi_tethering`, `mesh_cd_group_add`, `mesh_peer_count`) across `values`, `values-hi`, and `values-mr`. Verified parity via `StringsParityTest`.
  - Replaced silent catches with `MeshLog.w(...)` and ensured `CancellationException` is properly rethrown.

### WP-49: Protocol Hardening, Key Zeroization, Cancellable Helpers, and Flag Isolation
- **Commit:** `038f7d1`
- **Summary:**
  - Injected `suppressRedundantRelays` into `ForwardingEngine` (default `false`); verified 5 vs 18 datagrams in `RelayAmplificationTest`.
  - Introduced `runCatchingCancellable` in `Cancellable.kt` rethrowing `CancellationException`; replaced all 6 suspend `runCatching` calls in `VoiceSessionCoordinator` and 1 in `MeshVoxBridge`.
  - Added `ForwardingEngine.close()` key zeroization (`authKey`, `payloadKey`); invoked by `OfflineMeshVoiceTransport.stop()`. Deleted dead `OfflineSession.secret`. Verified via `KeyZeroizationTest`.
  - Hardened `FeatureFlags.OFFLINE_MESH_ENABLED` to ignore `localOverride` in release builds. Removed all mutable global vars from `MeshConfig`.
  - Extracted route and peer projection into `MeshPeerProjector.kt`. Updated threat model in `mesh-threat-model.md`.

### WP-48: Honest UDP Docs, Guided Setup, Self-Test HUD, and Nearby Design
- **Commit:** `6008589`
- **Summary:**
  - Reconciled documentation with code truth (B1): explicitly documented v1 transport as UDP over shared Wi-Fi/hotspot without automatic Wi-Fi Direct group setup.
  - Added guided 3-step hotspot setup instructions and network self-test HUD across English, Hindi, and Marathi.
  - Implemented `MeshSelfTestMapper` and `SystemWifiCapabilityChecker` with hardware pre-checks disabling automatic fallback when Wi-Fi is absent.
  - Produced `NEARBY_LINK_DESIGN.md` specifying v2 Nearby Connections link with `Strategy.P2P_CLUSTER` and fail-closed gates (`WRITE_NEARBY_CODE=false`).
  - Added field-log template in `PHYSICAL_TEST_CHECKLIST.md` (all 18 scenarios confirmed `NOT EXECUTED`).

### WP-47: Link Supervisor, Wi-Fi Watcher Rebind, and Multicast Lock Lifecycle
- **Commit:** `a607d7e`
- **Summary:**
  - Decoupled `MulticastLock` and `WifiLock` in `AndroidNetworkLocks`: MulticastLock held continuously during discovery/standby, WifiLock acquired only during active audio.
  - Implemented `AndroidWifiNetworkWatcher` debouncing Wi-Fi interface switches and rebinding UDP sockets.
  - Added UDP socket buffer drop counter and telemetry reporting in `MeshDiagnosticsRepository`.
  - Added link supervisor loop with backoff and 30s timeout triggering fallback when link fails.

### WP-46: State Machine Backoff, Dead Field Removal, and Adapter-Level Signal Wiring
- **Commit:** `4695ac3`
- **Summary:**
  - Added exponential backoff to `AutomaticSwitchStateMachine` upon failed fallback (`TO_OFFLINE`) to prevent CPU spin and log flooding.
  - Added anti-flapping suppression with rate-limiting window (maximum 4 transitions in 120 seconds).
  - Wired real LiveKit reconnect attempts at adapter level in `LiveKitVoiceTransport` via rolling 60s window.
  - Reset recovery stability timer on network interface switches in `ValidatedInternetSource.validatedFlow`.
  - Removed dead fields `packetLossPercent` and `rttMs` from `LocalHealthSignals`.

### WP-45: Mic Ordering Fix, Deduplicated Capture States, and Fallback Overlay
- **Commit:** `6b27f66`
- **Summary:**
  - Conflated `MicController` command loop to strictly sequential single-consumer channel, resolving race conditions between PTT, VOX, and mic toggle.
  - Extracted `toMicState` pure mapper and eliminated duplicated `OfflineAudioCapture.CaptureState` classes.
  - Added full fallback controls overlay in `MeshControls.kt` consuming touch events over `RoomScreen`.

### WP-44: Settings Single Instance, i18n, Availability UI and Reactive Gate
- **Commit:** `5cba066`
- **Summary:**
  - Replaced duplicate settings instances with unified `MeshRuntimeSettings` backed by `SharedPreferences`.
  - Installed reactive `OfflineMeshGate` listening to settings changes without process restart.
  - Built `MeshSettingsViewModel` and `OfflineAvailabilityUi` mapping availability states to localized UI copy in English, Hindi, and Marathi.

### WP-43: Truth Reset, Contract Restored, Defect Register and Test Ref Verification
- **Commit:** `6f1fa17`
- **Summary:**
  - Restored `docs/plan/OFFLINE_MESH_PLAN.md` to its original frozen 1089 lines.
  - Initialized read ledger `docs/implementation/mesh/_readledger.tmp` and rewritten `DEFECT_REGISTER.md`.
  - Added `.github/scripts/verify-test-refs.sh` to validate that every test reference in documentation corresponds to an actual test class and method.

---

## Round 2 Remediation (WP-34 – WP-42)

| WP | Commit | Summary |
|---|---|---|
| **WP-42** | `517cfee` | Final Round 2 verification, CI guards, release keystore fallback configuration. |
| **WP-41** | `0158ab9` | Documentation alignment with code truth; removed stale docs (`AUDIT_FACTS.md`, legacy `.docx`) and dead code (`PartialMeshPeer`). |
| **WP-40** | `b50df7a` | Epoch-aware graph reports; legacy nonce fallback rejection; aligned debug receiver with permissions. |
| **WP-39** | `29926d7` | In-memory session lifecycle across Activity rotations; foreground service microphone permissions. |
| **WP-38** | `2ad382f` | Non-disruptive audio recovery; eliminated LiveKit disconnects; non-blocking switch state machine. |
| **WP-37** | `e1542dc` | Reachable Stage 3 with Crockford Base32 pairing-code backup; reactive availability; authenticated endpoint learning. |
| **WP-36** | `1b2e36e` | Single mic control path; hands-free VOX integration; non-cancelling helmet PTT semantics. |
| **WP-35** | `4fa3296` | Validated-internet connectivity source; network capabilities checks; probe throttling. |
| **WP-34** | `febcbff` | Baseline audit, defect register A1-A47 established, environment verification. |

---

## Round 1 Initial Implementation (WP-25 – WP-33)

| WP | Commit | Summary |
|---|---|---|
| **WP-33** | `afaf414` | Round 1 final audit and four green gradle verification tasks. |
| **WP-32** | `519e726` | Debug tooling (`MeshDebugReceiver`), manifest permissions, ProGuard keep rules, CI frozen-path checks. |
| **WP-31** | `b4b97a9` | Aligned documentation with protocol and state machine specifications. |
| **WP-30** | `95f1c21` | Theme-safe mesh UI, localized string resources across 3 locales, tactical status banner. |
| **WP-29** | `d024273` | Protocol hardening, forwarding TTL limits, thread safety, link state flow. |
| **WP-28** | `473044e` | Single-capture audio handoff, 400ms quiet guard, audio route controller, playback robustness. |
| **WP-27** | `148af11` | Automatic switch state machine, phase gating, probe rate limiting. |
| **WP-26** | `a443656` | Secret hygiene, session holder in-memory storage, rotation-safe navigation routes. |
| **WP-25c** | `b9550e0` | Production wiring green; eliminated insecure room-name-derived secrets. |
| **WP-25b** | `f21a991` | Red test harness establishing `ProductionWiringTest`. |
| **WP-25a** | `17639d5` | Restored Android project compilation and recorded true baseline test status. |
