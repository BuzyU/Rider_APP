# RiderVoice Offline Mesh Intercom

The `com.ridervoice.mesh` package provides zero-infrastructure, ad-hoc voice communications and automatic failover for motorcycle convoys when cellular connectivity is lost.

## Transport & Architecture Truth
- **v1 Transport:** UDP broadcast/unicast datagrams on port `45454` over a shared Wi-Fi or mobile hotspot network created by one rider and joined by the convoy.
- **Fail-Safe Gate:** Feature is disabled by default in release builds (`BuildConfig.OFFLINE_MESH_BUILD_ENABLED = false`).
- **Cryptographic Model:** Crockford Base32 pairing codes derive 256-bit encryption and authentication keys via PBKDF2 (HMAC-SHA256).

## Package Structure
- `audio/`: Low-latency PCM capture (`OfflineAudioCapture`), playback (`OfflineAudioPlayback`), jitter buffering (`JitterBuffer`), and serialized mic dispatch (`MicController`).
- `forwarding/` & `routing/`: Monotonic sequence forwarding (`ForwardingEngine`), hop decrement (`TtlPolicy`, max TTL 5), and shortest-path graph calculation (`RouteCalculator`).
- `link/` & `protocol/`: UDP socket handling (`UdpMeshLink`), Wi-Fi locks (`NetworkLocks`), packet framing (`MeshPacket`), and AES-GCM crypto (`PayloadCrypto`).
- `session/` & `identity/`: Session keys and pairing codes (`SessionKdf`), session state (`OfflineSessionHolder`).
- `switching/`: 8-state finite state machine (`AutomaticSwitchStateMachine`) coordinating failover based on network probes (`ValidatedInternetSource`) and local signals (`LocalHealthSignalsProvider`).
- `transport/`: Coordinator arbitrating between online LiveKit and offline mesh (`VoiceSessionCoordinator`, `AudioOwnershipArbiter`).
- `ui/`: Compose lobby (`OfflineMeshLobbyScreen`), active HUD (`OfflineRoomScreen`), fallback overlays (`OfflineAwareRoomHost`, `MeshControls`), and self-test HUD.

## Running Tests
Run all unit tests and verification checks via Gradle:
```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
bash .github/scripts/mesh-invariants.sh
```

## Baseline and Rollback (S-2)

### Baseline Verification
- **Baseline Commit:** `a37ef09`
- **Baseline Test Census:**
  - Unit Tests: **216** `@Test` methods passed (`0` failures, `0` ignored) in 4.93s (`testDebugUnitTest`).
  - Instrumented Tests: **0** (all mesh instrumented tests in `androidTest` were introduced in later mesh work packages).
- **Current Test Census (HEAD):**
  - Unit Tests: **550** `@Test` methods passed (`0` failures, `0` ignored).
  - Instrumented Tests: **10** (all marked `NOT RUN` in CI per environment constraint).

### Three-Level Rollback Plan
1. **Level 1 — Runtime User Setting Off:**
   - Turn off the mesh intercom toggle in app settings: `FeatureFlags.OFFLINE_MESH_ENABLED = false`.
   - Result: All mesh background services, sockets, discovery, audio capture/playback, and switching state machines remain completely dormant and stopped. The app functions exclusively as an online LiveKit client.
2. **Level 2 — Compile-Time Build Gate Off:**
   - Build with `BuildConfig.OFFLINE_MESH_BUILD_ENABLED = false` (default for release builds via `mobile-app/app/build.gradle`).
   - Result: Mesh dependency graphs, ViewModels, and services are compile-time gated or nullified; release builds cannot initialize or execute mesh subsystems.
3. **Level 3 — Complete Source Revert:**
   - Execute git revert of all mesh commits from baseline `a37ef09` to HEAD:
     ```bash
     git revert --no-commit a37ef09..HEAD
     ```
   - All mesh files, services, DI modules, UI screens, tests, and documentation are cleanly restored to the baseline commit `a37ef09`.
   - **Rollback Drill Verification:** A Level 3 rollback dry-run was executed on branch `temp-rollback-drill`. The command `git revert --no-commit a37ef09..HEAD` reverted all changes cleanly with zero conflicts, and `ALLOW_INSECURE_RELEASE_BUILD=true ./gradlew assembleRelease` succeeded (`BUILD SUCCESSFUL in 4m 22s`, 58 actionable tasks: 37 executed, 21 up-to-date).

## Core Reference Documents
- [Three-Stage Mesh Plan](../../plan/OFFLINE_MESH_PLAN.md): Authoritative architectural plan and execution requirements.
- [Protocol Specification v3](../../protocol/mesh-v2.md): Wire format, packet framing, and crypto spec.
- [Mesh Threat Model](../../security/mesh-threat-model.md): Security analysis, key derivation, and attack mitigations.
- [Physical Test Checklist](../../testing/PHYSICAL_TEST_CHECKLIST.md): Hardware verification checklist (all marked `NOT EXECUTED`).
- [Mesh Test Plan](../../testing/mesh-test-plan.md): Test census, testing philosophy, and unit test strategy.
- [State Machine Specification](state-machine.md): Formal transition rules and backoff logic.
- [Architecture Decisions Log](DECISIONS.md): Engineering decisions, rationale, and approvals.
