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
    val maxTransitionsInWindow: Int = 3,
    val transitionWindowMs: Long = 120_000L
)
```

- **Failure Confirmation (`10,000 ms`):** Internet/LiveKit failure must persist continuously for 10 seconds before initiating mesh fallback.
- **Recovery Stability (`8,000 ms`):** Internet health signals must be stable and verified for 8 seconds before initiating recovery to LiveKit.
- **Minimum Dwell (`20,000 ms`):** Must remain in current transport for at least 20 seconds before allowing another transition.
- **Transition Rate Cap (`3 per 120s`):** At most 3 transitions allowed within any sliding 2-minute window. Exceeding this marks `FALLBACK_UNAVAILABLE` to maintain voice continuity.

---

## 3. Health Signals & Transition Guards

Every evaluation cycle samples a `LocalHealthSignals` vector:

```kotlin
data class LocalHealthSignals(
    val isValidatedInternet: Boolean,
    val isLiveKitConnected: Boolean,
    val isLiveKitReconnecting: Boolean,
    val liveKitReconnectAttempts: Int,
    val isMeshRouteAvailable: Boolean,
    val isHealthProbeSuccessful: Boolean
)
```

### 3.1 Degraded Confirmation (`ONLINE` -> `ONLINE_SUSPECT` -> `STARTING_OFFLINE_MESH`)
- **Failing Condition:** `(!isValidatedInternet || isLiveKitReconnecting || liveKitReconnectAttempts >= 2) && !isLiveKitConnected && !isHealthProbeSuccessful`.
- **Confirmation Time:** Must persist for `failureConfirmationMs` (10,000 ms).
- **Gate:** `isMeshRouteAvailable == true` (must have at least one viable radio route before switching).

### 3.2 Internet Recovery (`OFFLINE_ACTIVE` -> `ONLINE_RECOVERING` -> `HANDING_BACK_TO_LIVEKIT`)
- **Recovery Condition:** `isValidatedInternet == true && isHealthProbeSuccessful == true`.
- **Background Reconnect Trigger:** Evaluated independently while offline mesh holds the microphone so LiveKit reconnects in the background before handback.
- **Stability Time:** Must persist for `recoveryStabilityMs` (8,000 ms).
- **Execution:** Transitions to `HANDING_BACK_TO_LIVEKIT`. Reconnects LiveKit, releases mesh mic, re-claims `AudioOwner.LIVEKIT`, and transitions back to `ONLINE`.

### 3.3 Exception & Failure Invariants
- If `fallbackToMesh()` throws or returns `false`, state machine safely rolls back to `ONLINE`.
- If `recoverToLiveKit()` throws or returns `false`, state machine safely rolls back to `OFFLINE_ACTIVE`.
- Android audio hardware is never left stranded without an arbiter owner.
