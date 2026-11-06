# Automatic Network Switching State Machine Specification

**Target:** `com.ridervoice.mesh.switching.AutomaticSwitchStateMachine`  
**Purpose:** Autonomous, debounce-protected failover between Cloud LiveKit audio and Local Ad-Hoc Mesh Intercom.

---

## 1. State Space

The state machine implements a strict 8-state deterministic finite state machine (`AutoSwitchState`):

```
                  ┌─────────────────────────────────────────────────────────┐
                  ▼                                                         │
               [ONLINE] <──────────────────────────────┐                    │
                  │                                    │                    │
      (Online Failing for 10s)                         │                    │
                  │                                    │                    │
                  ▼                                    │                    │
          [ONLINE_SUSPECT]                             │ (Handoff Failure / │
                  │                                    │  Rollback)         │
         (Mesh Route Ready)                            │                    │
                  │                                    │                    │
                  ▼                                    │                    │
       [STARTING_OFFLINE_MESH] ────────────────────────┘                    │
                  │                                                         │
           (Handoff Success)                                                │
                  │                                                         │
                  ▼                                                         │
           [OFFLINE_ACTIVE]                                                 │
                  │                                                         │
      (Internet Restored for 8s)                                            │
                  │                                                         │
                  ▼                                                         │
          [ONLINE_RECOVERING]                                               │
                  │                                                         │
         (LiveKit Reconnected)                                              │
                  │                                                         │
                  ▼                                                         │
      [HANDING_BACK_TO_LIVEKIT]                                             │
                  │                                                         │
       (Audio Handback Success)                                             │
                  │                                                         │
                  ▼                                                         │
               [ONLINE] ────────────────────────────────────────────────────┘
                  │
      (Flapping Limit / Error)
                  │
                  ▼
        [FALLBACK_UNAVAILABLE] / [SESSION_ENDED]
```

### 1.1 State Definitions

| State | Description | Mic Owner |
|---|---|---|
| `ONLINE` | Normal operating mode. LiveKit WebRTC handles all convoy audio. | `LIVEKIT` |
| `ONLINE_SUSPECT` | Cellular/internet link failing. Failure confirmation debounce timer running. | `LIVEKIT` |
| `STARTING_OFFLINE_MESH` | Initiating audio ownership handoff from LiveKit to Offline Mesh. | Transitioning |
| `OFFLINE_ACTIVE` | Offline mesh intercom operational. Zero dependency on cloud servers. | `OFFLINE_MESH` |
| `ONLINE_RECOVERING` | Internet verified restored via independent probe; recovery stability timer running. | `OFFLINE_MESH` |
| `HANDING_BACK_TO_LIVEKIT` | LiveKit reconnected; performing audio handback from mesh to LiveKit. | Transitioning |
| `FALLBACK_UNAVAILABLE` | Fallback failed or max anti-flapping transitions exceeded. | As assigned |
| `SESSION_ENDED` | Active ride or offline session has terminated. | `NONE` |

---

## 2. Anti-Flapping Configuration

Anti-flapping parameters prevent rapid ping-pong oscillation when traveling on coverage boundaries:

```kotlin
data class AntiFlappingConfig(
    val failureConfirmationMs: Long = 10_000L,
    val recoveryStabilityMs: Long = 8_000L,
    val minDwellMs: Long = 20_000L,
    val maxTransitionsPerWindow: Int = 4,
    val windowMs: Long = 120_000L,
    val offlineRetryBaseMs: Long = 5_000L
)
```

- **Failure Confirmation (`10,000 ms`):** Internet/LiveKit failure must persist continuously for 10 seconds before initiating mesh fallback.
- **Recovery Stability (`8,000 ms`):** Internet health signals must be stable and verified for 8 seconds before initiating recovery to LiveKit.
- **Minimum Dwell (`20,000 ms`):** Must remain in current transport for at least 20 seconds before allowing another transition (except when the active mesh transport dies while online is recoverable).
- **Transition Rate Cap (`4 per 120s`):** At most 4 transitions allowed within any sliding 2-minute window (`windowMs`), with exponential retry backoff (`offlineRetryBaseMs = 5,000 ms` doubling up to 8x).

---

## 3. Health Signals & Transition Guards

Every evaluation cycle samples a `LocalHealthSignals` vector:

```kotlin
data class LocalHealthSignals(
    val isValidatedInternet: Boolean,
    val isLiveKitConnected: Boolean,
    val isLiveKitReconnecting: Boolean,
    val liveKitReconnectAttempts: Int,
    val isHealthProbeSuccessful: Boolean,
    val hasValidMeshRoute: Boolean,
    val offlineAvailable: Boolean = true,
    val isMeshTransportAlive: Boolean = true,
    val lastInterfaceChangeMs: Long = 0L
)
```

### 3.1 Total Loss Rule & Confirmation (`ONLINE` -> `ONLINE_SUSPECT` -> `STARTING_OFFLINE_MESH`)
- **Total Loss Rule (WP-35 standing rule):** Weak or degraded cellular connectivity alone NEVER triggers offline mesh. Falling back to offline mesh strictly requires confirmed, continuous **total internet loss** defined by all three:
  1. `!isValidatedInternet`: Android NetworkCapabilities lacks `NET_CAPABILITY_VALIDATED`.
  2. `!isLiveKitConnected`: LiveKit WebRTC is disconnected.
  3. `!isHealthProbeSuccessful`: Background `/health` HTTP probe fails.
- **Confirmation Time:** Total loss must persist continuously for `failureConfirmationMs` (10,000 ms). Any recovery of validated internet or probe immediately resets the suspect timer back to 0.
- **Gate:** `isMeshRouteAvailable == true` and an offline session secret is available in `OfflineSessionHolder`.
- **Session Lifecycle & Phase Gating:** `AutomaticSwitchStateMachine` evaluates switching rules only when in `Phase.LIVE` (initiated via `beginSession()` and terminated via `endSession()`). If the session is ended or uninitialized, transitions are inhibited.

### 3.2 Independent HTTP Probe Throttling
- The health probe performs lightweight HTTP requests throttled to at least 5-second intervals, executing strictly on `Dispatchers.IO` to ensure zero blocking on main or state evaluation threads.

### 3.3 Internet Recovery & Non-Disruptive Handback (`OFFLINE_ACTIVE` -> `ONLINE_RECOVERING` -> `ONLINE`)
- **Recovery Condition:** `isValidatedInternet == true && isHealthProbeSuccessful == true`.
- **Stability Time:** Must persist for `recoveryStabilityMs` (8,000 ms).
- **Non-Disruptive Transition (WP-38):** In provisional online recovery, mesh audio playback remains active. Microphone capture is paused only while awaiting LiveKit `CONNECTED` state with a 15-second timeout. If LiveKit reconnects, audio ownership transitions cleanly to LiveKit. If the 15-second timeout expires without LiveKit connected, capture rolls back to offline mesh, avoiding dead air.

### 3.4 Audio Ownership & Quiet Guard Window
- Audio exclusivity is governed by `AudioOwnershipArbiter` via mutual exclusion mutex.
- When LiveKit releases ownership, a **400 ms quiet guard window** is enforced before mesh can acquire hardware ownership. This prevents HAL race conditions, audio driver crashes, and audio bleeding between systems.

### 3.5 Exception & Failure Invariants
- If `fallbackToMesh()` throws or returns `false`, state machine safely rolls back to `ONLINE`.
- If `recoverToLiveKit()` throws or returns `false`, state machine safely rolls back to `OFFLINE_ACTIVE`.
- Android audio hardware is never left stranded without an arbiter owner.
