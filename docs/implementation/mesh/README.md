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
bash .github/scripts/verify-test-refs.sh
```

## Core Reference Documents
- [Three-Stage Mesh Plan](../../plan/OFFLINE_MESH_PLAN.md): Authoritative architectural plan and execution requirements.
- [Protocol Specification v2](../../protocol/mesh-v2.md): Wire format, packet framing, and crypto spec.
- [Mesh Threat Model](../../security/mesh-threat-model.md): Security analysis, key derivation, and attack mitigations.
- [Physical Test Checklist](../../testing/PHYSICAL_TEST_CHECKLIST.md): Hardware verification checklist (all marked `NOT EXECUTED`).
- [Mesh Test Plan](../../testing/mesh-test-plan.md): Test census, testing philosophy, and unit test strategy.
- [State Machine Specification](state-machine.md): Formal transition rules and backoff logic.
- [Architecture Decisions Log](DECISIONS.md): Engineering decisions, rationale, and approvals.
- [Defect Register](DEFECT_REGISTER.md): Defect tracking for historical A1–A47 and B1–B27 findings.
- [Changelog](CHANGELOG.md): Consolidated work package log (WP-25 through WP-52).
