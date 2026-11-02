# Rider_APP Offline Mesh Intercom — Three-Stage Implementation Plan

This plan follows the required implementation order:

1. Implement and prove the offline daisy-hop mesh.
2. Integrate the offline mesh with the existing online LiveKit system.
3. Add automatic switching from online to offline and back to online.

The existing online system must not break at any stage.

---

## 1. Non-negotiable compatibility rules

The current online system is the compatibility contract.

The following behavior must remain unchanged:

- LiveKit connection and room joining
- Backend token acquisition
- Token refresh
- Room lifecycle
- Participant handling
- LiveKit reconnection
- PTT
- VOX
- Speaker output
- Bluetooth output
- Audio routing
- Audio focus
- Foreground service behavior
- Wake locks
- Diagnostics
- SOS and ride lifecycle
- Existing online UI

Do not replace or rewrite `LiveKitManager`.

Do not replace the online audio pipeline with UDP.

Do not modify online behavior while implementing Stage 1.

When offline mode is disabled, the application must not:

- Open offline sockets
- Start discovery
- Build a mesh graph
- Create offline `AudioRecord`
- Create offline `AudioTrack`
- Request offline permissions
- Register offline callbacks
- Change LiveKit behavior

Use both:

```text
Build-time feature flag
Runtime user setting
```

Recommended initial defaults:

```text
OFFLINE_MESH_ENABLED = false
offlineMeshEnabled = false
automaticFallbackEnabled = false
warmStandbyEnabled = false
```

The offline subsystem may run only when the relevant gates are enabled.

---

# Stage 0 — Baseline and online-system protection

## Objective

Capture the current online behavior before adding any offline code.

## Audit

Inspect:

```text
mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt
mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt
mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt
mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt
mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/
mobile-app/app/src/main/java/com/ridervoice/audio/
mobile-app/app/src/main/java/com/ridervoice/di/AppModule.kt
mobile-app/app/src/main/AndroidManifest.xml
mobile-app/app/build.gradle
mobile-app/app/src/main/java/com/ridervoice/permissions/PermissionManager.kt
mobile-app/app/src/main/java/com/ridervoice/network/NetworkResilienceManager.kt
mobile-app/app/src/main/java/com/ridervoice/diagnostics/
mobile-app/app/src/test/
```

Search for:

```text
offline
mesh
topology
UDP
DatagramSocket
DatagramPacket
AudioRecord
AudioTrack
Wi-Fi Direct
Wi-Fi Aware
Nearby Connections
hotspot
discovery
forwarding
```

## Baseline commands

Run before modifying code:

```bash
git status --short
git branch --show-current
git rev-parse HEAD
git log --oneline -10
./mobile-app/gradlew -p mobile-app testDebugUnitTest
./mobile-app/gradlew -p mobile-app assembleDebug
```

Record:

- Existing test count
- Existing failures
- Build result
- Current commit
- Current changed files
- APK size if useful

If the baseline is already failing, document it and do not attribute it to offline work.

## Frozen online files

During the initial mesh implementation, keep these unchanged unless a separate approved online bug-fix task is created:

```text
mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt
mobile-app/app/src/main/java/com/ridervoice/network/ConnectionState.kt
mobile-app/app/src/main/java/com/ridervoice/network/NetworkResilienceManager.kt
mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt
mobile-app/app/src/main/java/com/ridervoice/state/ActiveRideRepository.kt
mobile-app/app/src/main/java/com/ridervoice/state/RoomFault.kt
mobile-app/app/src/main/java/com/ridervoice/state/AppEvents.kt
mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt
mobile-app/app/src/main/java/com/ridervoice/audio/*
mobile-app/app/src/main/java/com/ridervoice/diagnostics/*
mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt
mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/*
backend/*
web-app/*
livekit-server/*
```

If a frozen file must change later, make that change separately with dedicated tests.

---

# Stage 1 — Implement and prove the offline daisy-hop mesh

Do not integrate with LiveKit yet.

The first objective is to prove that six devices can relay audio dynamically:

```text
A → B → C → D → E → F
```

Every relay must:

- Receive audio
- Play audio locally
- Forward audio
- Preserve the original source ID
- Prevent loops
- Suppress duplicates
- Enforce TTL

The topology must adapt from:

```text
A — B — C — D
```

to:

```text
A — C — B — D
```

without restarting the application.

## Stage 1.1 — Stable device identity

Create one stable mesh identity:

```text
meshDeviceId
```

Do not use:

- IP address
- Display name
- Bluetooth MAC address
- Firebase UID as the only identity

The identity must survive:

- App restarts
- IP changes
- Wi-Fi changes
- Hotspot changes
- Temporary disconnections

Keep the display name separate:

```kotlin
data class MeshIdentity(
    val deviceId: String,
    val displayName: String
)
```

The device ID is used as:

- Graph vertex identity
- Audio source identity
- Duplicate-cache identity
- Route identity

## Stage 1.2 — Standalone offline session

Create a standalone offline session without backend, Firebase, Internet, or LiveKit:

```kotlin
data class OfflineSession(
    val sessionId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val maxDevices: Int = 6
)
```

Define:

- Session creation
- Session joining
- Pairing code or QR flow
- Session authentication
- Six-device admission
- Seventh-device rejection
- Session expiration
- Leave behavior
- Session cleanup

Packets from another session must be rejected.

## Stage 1.3 — Transport abstraction

Create a transport-independent link interface:

```kotlin
interface MeshLink {
    val linkId: String
    val state: StateFlow<MeshLinkState>
    val incomingFrames: Flow<MeshFrame>

    suspend fun start()
    suspend fun stop()

    fun send(
        endpoint: MeshEndpoint,
        payload: ByteArray
    ): Boolean

    fun endpoints(): Set<MeshEndpoint>
}
```

The first transport should be the easiest reliable transport to test.

Recommended order:

1. UDP over a shared local subnet
2. Nearby Connections
3. Wi-Fi Aware
4. Other transports if required

Do not begin with automatic hotspot chaining. Android does not guarantee simultaneous upstream Wi-Fi, hotspot, and packet forwarding.

## Stage 1.4 — Discovery heartbeat

For UDP:

```text
Discovery port: 45455
Audio port: 45454
```

Discovery packets must include:

```text
magic
protocolVersion
packetType
sessionId
senderDeviceId
senderName
sequenceNumber
timestamp
directNeighborIds
graphVersion
boundedGraphUpdates
transportMetadata
authentication
```

Each device must:

1. Send periodic heartbeats.
2. Detect directly reachable neighbors.
3. Store the peer’s current endpoint.
4. Refresh last-seen time.
5. Remove timed-out neighbors.
6. Exchange graph information.
7. Recalculate routes.
8. Propagate graph updates.

Important invariant:

```text
IP address = current endpoint
Device ID = permanent identity
```

An IP change must update the endpoint without creating a new device.

## Stage 1.5 — Graph representation

Represent:

```text
Device = vertex
Direct wireless connection = edge
```

Example:

```text
A — B — C — D
```

must be represented as:

```text
A ↔ B
B ↔ C
C ↔ D
```

Do not store a permanent ordered list such as:

```text
[A, B, C, D]
```

Use edge metadata:

```kotlin
data class MeshEdge(
    val from: String,
    val to: String,
    val endpoint: MeshEndpoint,
    val lastSeenAt: Long,
    val expiresAt: Long,
    val latencyMs: Long?,
    val bidirectional: Boolean
)
```

Expose immutable graph snapshots through `StateFlow`.

## Stage 1.6 — Route calculation

Use BFS initially because the supported mesh has at most six devices.

For every known destination calculate:

- Reachable state
- Hop count
- Next-hop device ID
- Route expiration
- Direct/relayed state
- Optional path

For:

```text
A — B — C — D
```

routes from A are:

```text
B: hopCount=1, nextHop=B
C: hopCount=2, nextHop=B
D: hopCount=3, nextHop=B
```

For:

```text
A — C — B — D
```

routes from A are:

```text
C: hopCount=1, nextHop=C
B: hopCount=2, nextHop=C
D: hopCount=3, nextHop=C
```

Recalculate when:

- Neighbor appears
- Neighbor disappears
- Graph update arrives
- Device expires
- Endpoint changes
- Link expires
- Session membership changes

Use deterministic tie-breaking for equal-cost routes.

## Stage 1.7 — Audio packet format

Use a bounded binary packet:

```text
magic
protocolVersion
packetType
sessionId
originalSourceDeviceId
sequenceNumber
captureTimestamp
ttl
hopCount
previousHopDeviceId
audioFormat
payloadLength
payload
authenticationTag
```

Required capture format:

```text
16 kHz
mono
16-bit signed PCM
20–40 ms frames
```

A 20 ms frame contains:

```text
320 samples
640 bytes of raw PCM
```

If Opus or ADPCM is added later:

- Capture still begins as PCM.
- Codec negotiation is required.
- Unsupported codecs are rejected.
- A fallback is defined.
- CPU, latency, and bandwidth are measured.

The original source ID must never change:

```text
A → B → C → D
```

must preserve:

```text
originalSourceDeviceId = A
```

## Stage 1.8 — Daisy-hop forwarding

For every received packet:

1. Validate magic.
2. Validate protocol version.
3. Validate session ID.
4. Validate source ID.
5. Validate payload size.
6. Validate packet timestamp.
7. Validate authentication.
8. Check TTL.
9. Check duplicate cache.
10. Drop duplicates.
11. Add the packet to the duplicate cache.
12. Play the packet locally.
13. Decrease TTL.
14. Select valid next-hop neighbors.
15. Exclude the incoming neighbor.
16. Exclude the original source.
17. Exclude expired routes.
18. Forward the packet.

Duplicate key:

```text
sessionId + sourceDeviceId + sequenceNumber
```

The cache requires:

- Maximum entry count
- Expiration
- Thread safety
- Cleanup
- Oldest-entry eviction

Controlled flooding is acceptable initially for six devices, but it must be bounded and loop-safe.

## Stage 1.9 — TTL and loop prevention

Use a configurable default TTL, for example:

```text
DEFAULT_TTL = 5
```

Drop packets when:

- TTL is exhausted
- Packet is too old
- Duplicate key already exists
- Previous hop is invalid
- Route is expired
- Forwarding budget is exceeded

## Stage 1.10 — Jitter buffer

Do not play packets immediately on arrival.

Use one jitter buffer per source.

Support:

- Sequence ordering
- Target playout delay
- Late packet dropping
- Missing packet detection
- Silence insertion
- Packet-loss concealment
- Bounded queue size
- Source timeout cleanup

Start with a target of approximately 40–120 ms and tune using physical tests.

## Stage 1.11 — Stage 1 tests

Before any LiveKit integration, test:

- Device identity
- Session validation
- Discovery encoding/decoding
- Invalid packet rejection
- Graph merge
- Neighbor timeout
- Route calculation
- Route changes
- IP changes
- TTL
- Duplicate suppression
- Source-ID preservation
- Jitter ordering
- Packet loss

Simulate:

```text
A — B — C — D
A — C — B — D
A — B — C — D — E — F
```

Also simulate:

- Relay disappearance
- Relay return with a new endpoint
- Duplicate packets
- Route change during transmission
- Late graph updates
- Partition and merge
- Seventh-device rejection

Do not continue to Stage 2 until Stage 1 works with at least two physical devices and the in-memory six-device tests pass.

---

# Stage 2 — Integrate online and offline mesh

Start only after Stage 1 is stable.

## Stage 2.1 — Common voice transport model

Create:

```kotlin
interface VoiceTransport {
    val state: StateFlow<VoiceTransportState>
    val participants: StateFlow<List<VoiceParticipant>>
    val activeSpeakers: StateFlow<Set<String>>
    val audioOwner: StateFlow<AudioOwner>

    suspend fun start(session: VoiceSession)
    suspend fun stop()

    suspend fun setMicrophoneEnabled(enabled: Boolean)
    suspend fun setDeafened(deafened: Boolean)
    suspend fun setAudioRoute(route: AudioRoute)
}
```

Implement:

```text
LiveKitVoiceTransport
OfflineMeshVoiceTransport
```

`LiveKitVoiceTransport` must be an adapter around the existing `LiveKitManager` and must not rewrite its behavior.

## Stage 2.2 — Separate online and offline participant models

Do not treat offline devices as LiveKit participants.

Keep separate internal models:

```text
OnlineParticipant
OfflineMeshDevice
UnifiedVoicePeer
```

A UI projection may show:

- Name
- Online/offline state
- Transport
- Hop count
- Next hop
- Direct/relayed state

But the underlying semantics remain separate.

## Stage 2.3 — Audio ownership arbiter

Use:

```kotlin
enum class AudioOwner {
    NONE,
    LIVEKIT,
    OFFLINE_MESH
}
```

Rules:

```text
Healthy online session: LIVEKIT
Offline fallback: OFFLINE_MESH
No active session: NONE
```

Before offline capture starts:

```text
AudioOwner != LIVEKIT
```

Before LiveKit audio resumes:

```text
Offline capture is stopped
```

Never allow simultaneous audio ownership.

## Stage 2.4 — Runtime/service integration

Use a coordinator:

```text
VoiceSessionCoordinator
├── LiveKitRuntime
└── OfflineMeshRuntime
```

Each runtime must expose:

```text
start()
stop()
pause()
resume()
```

Offline runtime owns:

- Discovery
- Mesh links
- Graph
- Routes
- Forwarding
- Capture
- Playback
- Jitter buffers
- Offline diagnostics

Existing online behavior must remain unchanged when LiveKit is selected.

## Stage 2.5 — UI integration

Keep the existing online `RoomScreen` behavior unchanged.

Prefer:

```text
Existing online RoomScreen
Separate OfflineRoomScreen
OfflineAwareRoomHost
```

The offline screen displays:

- Complete mesh device list
- Online/offline status
- Direct/relayed status
- Hop count
- Next hop
- Route age
- Mesh health
- Microphone state
- Output route
- Active sources
- Diagnostics

Do not show relayed devices as direct.

Do not render a fixed star or fixed chain.

## Stage 2.6 — Stage 2 tests

Verify:

- Online mode remains unchanged.
- Offline mode runs without LiveKit.
- Online mode does not start offline sockets.
- Offline cleanup does not disconnect LiveKit.
- LiveKit cleanup does not stop offline runtime accidentally.
- Exactly one audio owner exists.
- Speaker/Bluetooth routing still works.
- PTT/VOX still works.
- Standalone offline UI works.
- Existing online RoomScreen behavior remains unchanged.

Do not add automatic switching before these tests pass.

---

# Stage 3 — Automatic online/offline switching

Start only after Stage 1 and Stage 2 pass.

## Stage 3.1 — Local online failure detection

Automatic fallback is based on the local device’s online path.

Do not switch all devices because one remote participant disconnects.

Use:

- Validated Internet state
- LiveKit connection state
- LiveKit reconnect attempts
- Active health probe
- Token refresh status
- Local interface changes

Example trigger:

```text
No validated Internet
AND LiveKit is not connected
AND active probe fails
FOR at least 10 seconds
AND a valid mesh path exists
```

A short interruption must not immediately switch transports.

## Stage 3.2 — State machine

Use:

```text
ONLINE
ONLINE_SUSPECT
STARTING_OFFLINE_MESH
OFFLINE_ACTIVE
ONLINE_RECOVERING
HANDING_BACK_TO_LIVEKIT
FALLBACK_UNAVAILABLE
SESSION_ENDED
```

Flow:

```text
ONLINE
  ↓ local online failure suspected
ONLINE_SUSPECT
  ↓ confirmed after delay
STARTING_OFFLINE_MESH
  ↓ valid mesh path found
OFFLINE_ACTIVE
  ↓ stable online recovery
ONLINE_RECOVERING
  ↓ LiveKit audio confirmed
ONLINE
```

If no mesh path exists:

```text
STARTING_OFFLINE_MESH
  ↓
FALLBACK_UNAVAILABLE
```

## Stage 3.3 — Online to offline

When local LiveKit fails:

1. Enter `ONLINE_SUSPECT`.
2. Wait for failure confirmation.
3. Verify feature flag and user setting.
4. Check permissions.
5. Check transport capability.
6. Verify at least one valid mesh neighbor.
7. Calculate a valid route.
8. Claim `AudioOwner.OFFLINE_MESH`.
9. Stop or suspend LiveKit audio capture.
10. Start offline capture.
11. Start offline playback.
12. Increase discovery and graph-update frequency.
13. Show “Offline mesh active”.
14. Preserve microphone, PTT/VOX, and output settings.
15. Emit transition diagnostics.

Do not report offline active before a valid mesh path is confirmed.

## Stage 3.4 — Offline to online

Do not switch back merely because Wi-Fi appears.

Require:

```text
Validated Internet restored
AND LiveKit connected
AND active probe succeeds
AND LiveKit remains stable for the recovery window
```

When recovery is confirmed:

1. Enter `ONLINE_RECOVERING`.
2. Stop offline microphone capture first.
3. Stop offline packet transmission.
4. Change audio owner to `NONE`.
5. Claim `AudioOwner.LIVEKIT`.
6. Resume LiveKit audio.
7. Confirm LiveKit playback.
8. Stop offline playback.
9. Stop or reduce mesh control traffic.
10. Show “Online voice restored”.
11. Emit diagnostics.
12. Return to `ONLINE`.

Never allow both transports to transmit the same audio simultaneously.

## Stage 3.5 — Meaning of “all devices are back online”

Each device must make its own transport decision based on its own online state.

For the local device to return to online:

```text
Local validated Internet is stable
AND local LiveKit is stable
```

Do not force the local device to wait for every remote device.

For group status, show separate states:

```text
Local online recovered
Some peers still offline
Entire known group online
```

A remote device may remain offline while the local device returns to LiveKit.

## Stage 3.6 — Anti-flapping

Use:

- Failure confirmation delay
- Recovery stability window
- Minimum dwell time
- Switch cooldown
- Maximum switch count
- Exponential backoff

Example starting values:

```text
Failure confirmation: 10 seconds
Recovery stability: 8 seconds
Minimum dwell time: 20 seconds
Maximum transitions: 3 per 2 minutes
```

Tune only after physical testing.

---

# Stage 4 — Optional backend continuity for online rides

This is separate from the core mesh.

If an online ride must survive a long offline interval:

- Add an optional continuity lease.
- Keep it feature-flagged and default-off.
- Validate membership.
- Do not store mesh audio secrets in the backend.
- Add migrations and tests.
- Preserve compatibility with old clients.
- Make rollback independent of the Android client.

Standalone offline mode must not depend on this backend feature.

---

# Stage 5 — Acceptance tests

## Daisy-hop acceptance

Verify:

```text
A speaks
B receives and plays
B forwards
C receives and plays
C forwards
D receives and plays
```

Verify:

- Source ID remains A.
- TTL decreases.
- Duplicates are dropped.
- Packets are not sent backward.
- Routes change dynamically.
- Relay loss recovers.

## Online integration acceptance

Verify:

- LiveKit remains unchanged.
- Existing online UI remains unchanged.
- Online audio routing remains unchanged.
- Online PTT/VOX remains unchanged.
- Offline mode does not start accidentally.
- Offline cleanup does not disconnect LiveKit.

## Automatic fallback acceptance

Verify:

- Local Internet loss triggers fallback after the configured delay.
- Short network loss does not trigger fallback.
- Remote participant loss does not trigger global fallback.
- A valid mesh path is required.
- Only offline audio transmits after fallback.
- Only one microphone capture exists.
- Audio resumes over mesh.

## Automatic recovery acceptance

Verify:

- Internet returns.
- LiveKit reconnects.
- LiveKit remains stable for the recovery window.
- Offline capture stops first.
- Online capture resumes.
- No duplicate audio occurs.
- No transport oscillation occurs.
- Local UI reports online recovery.
- Other devices may independently remain offline.

---

# Final implementation order

Implement strictly in this order:

1. Audit repository and capture baseline.
2. Freeze online compatibility boundaries.
3. Add flags with no runtime behavior.
4. Add flag-off and import-isolation tests.
5. Implement stable mesh identity.
6. Implement standalone offline session.
7. Implement discovery.
8. Implement graph repository.
9. Implement BFS routes.
10. Implement packet codec.
11. Implement TTL and duplicate suppression.
12. Implement daisy-hop forwarding.
13. Implement jitter buffering.
14. Prove the mesh with in-memory tests.
15. Prove the mesh with physical devices.
16. Add common transport interfaces.
17. Wrap existing LiveKit behind the transport abstraction.
18. Add offline runtime integration.
19. Add audio ownership arbitration.
20. Add separate offline UI.
21. Run online regression tests.
22. Implement local online-failure detection.
23. Implement online-to-offline fallback.
24. Implement offline-to-online recovery.
25. Validate anti-flapping.
26. Add optional backend continuity separately.
27. Run security review.
28. Run six-device physical validation.
29. Perform rollback drill.
30. Release to internal testers.

The most important rule is:

> Never implement transport switching before the daisy-hop mesh and online/offline integration have been independently tested.

The final system must satisfy:

> The offline mesh is added without breaking the existing online system.

and:

> When the local online voice path fails, the app attempts to continue voice through the offline mesh if and only if a valid local mesh path exists.

A remote participant going offline must not incorrectly force every other device to switch transports.


---

## Amendments (Remediation Master Prompt v3)

Following independent audit and remediation (WP-25 through WP-33), the following protocol, architecture, and implementation amendments are binding:

### Amendment 1: Single Port Consolidation
- All UDP traffic (voice frames, HELLO discovery beacons, and BYE messages) uses a single unified port: `45454` (`MeshConfig.MESH_PORT`).
- `MeshConfig.AUDIO_PORT` is retained as an alias pointing directly to `MESH_PORT` for backward API compatibility.

### Amendment 2: Cryptographic Secret Hygiene
- Room-derived offline secrets (`SessionKdf.fromOnlineRoom`) have been eliminated.
- Mesh intercom strictly derives its keys from an authentic out-of-band Crockford Base32 pairing code or pre-shared key via `SessionKdf.deriveKeys`.
- If an online ride experiences connectivity loss, it can transition to offline mesh only if an authentic pre-shared key was configured. If absent, it fail-closes to `AutoSwitchState.FALLBACK_UNAVAILABLE`.

### Amendment 3: Session Lifecycle & Phase Gating
- `AutomaticSwitchStateMachine` evaluates network health and drives transitions only when in `Phase.LIVE`.
- The session lifecycle is explicitly controlled via `beginSession()` (invoked when the ride starts) and `endSession()` (invoked when the ride terminates).
- Local HTTP health probes are throttled to at least 5-second intervals, executing strictly on `Dispatchers.IO`.
- Rule W-DEGRADED: `isTotalLoss()` strictly requires all three degradation signals: no packet received within threshold, zero active participants, and LiveKit connection state disconnected or reconnecting.

### Amendment 4: Audio HAL Mutual Exclusion & Quiet Guard Window
- Microphone capture hardware exclusivity is enforced by `AudioOwnershipArbiter` via a coroutine mutex.
- A 400 ms quiet guard window is enforced after LiveKit releases capture ownership before offline mesh may acquire it.
- `enableAudio()` returns a type-safe `EnableAudioResult` sealed hierarchy (`Success`, `Failure.OwnershipDenied`, `Failure.SocketError`, `Failure.AudioRecordError`, `Failure.SessionInvalid`) rather than throwing raw runtime exceptions.

### Amendment 5: Forwarding, TTL Limits, and Endpoint Learning
- Forwarding and hop count bounds: `TtlPolicy.MAX_HOPS = 7`, `TtlPolicy.DEFAULT_TTL = 7`. Packets are dropped immediately if `ttl <= 0`, `hopCount > MAX_HOPS`, or `ttl + hopCount > DEFAULT_TTL`.
- UDP sockets enforce D22 invariant: unauthenticated packets are never registered into endpoint caches. Endpoints are registered solely after outer HMAC-SHA256 verification or explicit verified registration.
- `RoutingTable` uses `@Synchronized` recomputation and atomic immutable snapshot swapping.

### Amendment 6: Physical Testing Contract
- All multi-device, radio propagation, Bluetooth SCO helmet headset, and physical vehicular field tests are cataloged in `docs/testing/PHYSICAL_TEST_CHECKLIST.md` and marked as **NOT EXECUTED**. Automated verification is strictly based on JVM unit test suites (358 tests).
