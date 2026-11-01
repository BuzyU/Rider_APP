# Automatic Network Switching State Machine Specification

**Target:** `com.ridervoice.mesh.switching.AutomaticSwitchStateMachine`  
**Purpose:** Autonomous, debounce-protected failover between Cloud LiveKit audio and Local Ad-Hoc Mesh Intercom.

---

## 1. State Space

The state machine implements a strict 9-state finite state machine:

```
                  ┌─────────────────────────────────────────────────────────┐
                  ▼                                                         │
          [LIVEKIT_ACTIVE] <────────────────────────┐                       │
                 │                                  │                       │
     (Network Degraded for 3s)                      │                       │
                 │                                  │                       │
                 ▼                                  │                       │
        [DEGRADED_INTERNET]                         │ (Handoff Failure /    │
                 │                                  │  Exception Rollback)  │
      (Mesh Discovery & Route)                      │                       │
                 │                                  │                       │
                 ▼                                  │                       │
     [STARTING_OFFLINE_MESH] ───────────────────────┘                       │
                 │                                                          │
          (Handoff Success)                                                 │
                 │                                                          │
                 ▼                                                          │
          [OFFLINE_ACTIVE]                                                  │
                 │                                                          │
     (Internet Restored for 5s)                                             │
                 │                                                          │
                 ▼                                                          │
    [HANDING_BACK_TO_LIVEKIT]                                               │
                 │                                                          │
      (LiveKit Reconnected)                                                 │
                 │                                                          │
                 ▼                                                          │
            [COOLDOWN] (10s Debounce) ──────────────────────────────────────┘
```

| State | Description | Mic Owner |
|---|---|---|
| `LIVEKIT_ACTIVE` | Normal operating mode. LiveKit WebRTC handles all convoy audio. | `LIVEKIT` |
| `DEGRADED_INTERNET` | Cellular/Wi-Fi connection failing (packet loss or disconnect detected). | `LIVEKIT` |
| `STARTING_OFFLINE_MESH` | Initiating local radio discovery and claiming microphone hardware. | Transitioning |
| `OFFLINE_ACTIVE` | Offline mesh intercom operational. Zero dependency on cloud servers. | `OFFLINE_MESH` |
| `HANDING_BACK_TO_LIVEKIT` | Internet verified restored; reconnecting LiveKit and releasing mesh mic. | Transitioning |
| `COOLDOWN` | LiveKit restored; 10s cooldown preventing ping-pong flapping. | `LIVEKIT` |
| `OFFLINE_ONLY` | User explicitly selected offline mesh mode without automatic fallback. | `OFFLINE_MESH` |
| `DISABLED` | Offline mesh feature flag disabled in configuration. | `LIVEKIT` |
| `FAILED` | Terminal error occurred during transition. | `NONE` |

---

## 2. Health Signals & Transition Guards

Every evaluation cycle samples a `LocalHealthSignals` vector:

```kotlin
data class LocalHealthSignals(
    val hasValidatedInternet: Boolean,
    val isLiveKitConnected: Boolean,
    val liveKitRecentReconnects: Int,
    val meshHasRoute: Boolean,
    val healthProbeSucceeds: Boolean
)
```

### 2.1 Degraded Confirmation (LiveKit -> Offline Mesh)
- **Condition:** `!hasValidatedInternet || !isLiveKitConnected || liveKitRecentReconnects >= 3`.
- **Debounce:** Condition must persist continuously for `DEGRADE_CONFIRMATION_MS` (3,000 ms).
- **Gate:** `meshHasRoute == true` (must have at least one viable radio peer before dropping cellular audio).

### 2.2 Internet Recovery (Offline Mesh -> LiveKit)
- **Key Insight (D25 Recovery Fix):** When offline mesh is active under Option B, LiveKit is disconnected to release the Android microphone. Therefore, `isLiveKitConnected` is guaranteed to be `false`.
- **Condition:** `hasValidatedInternet == true && healthProbeSucceeds == true`.
- **Debounce:** Condition must persist continuously for `RESTORE_CONFIRMATION_MS` (5,000 ms).
- **Execution:** Transitions to `HANDING_BACK_TO_LIVEKIT`. Reconnects LiveKit, releases mesh mic, re-claims `AudioOwner.LIVEKIT`, and transitions to `COOLDOWN`.

### 2.3 Exception & Failure Invariants (D4)
- If `handoffToOffline()` throws or returns `false`, state machine **must not** remain in `STARTING_OFFLINE_MESH`. It immediately reverts to `LIVEKIT_ACTIVE`.
- If `handbackToLiveKit()` throws or returns `false`, state machine **must not** remain in `HANDING_BACK_TO_LIVEKIT`. It safely reverts to `OFFLINE_ACTIVE`.
- No transition may leave the Android audio hardware stranded without an arbiter owner.
