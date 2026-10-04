# Rider Voice — Remaining & Next-Step Tasks Roadmap

This document outlines everything currently missing, partially wired, or pending optimization in the Rider Voice audio pipeline following the v0.0.3.1 core fix pass.

---

## Current Status Overview

| Component | Status | Verified By |
|---|---|---|
| Mic Gate Single Source of Truth (B1, B2, B3, B9, B16) | ✅ Completed | Static Audit + JVM Unit Tests + CI Pass |
| Single Audio Owner & NoAudioHandler (B4) | ✅ Completed | Static Audit + Gradle Assemble |
| Communication Device Callbacks & Volume Fix (B12, B13) | ✅ Completed | Static Audit + Gradle Assemble |
| VOX Detector Rewrite & Adaptive Floor (B5, B6, B7, B10, B11, B14) | ✅ Completed | `VoxDetectorTest` (convergence, burst, step) |
| Settings Wiring & Crash Safety (B8, B14, B18) | ✅ Completed | Static Audit + Unit Tests |
| Per-Device Audio Options (Step 7) | ✅ Completed | Static Audit + Options Verification |
| Deafen / Defent Feature (Step 8) | ✅ Completed | Static Audit + RoomScreen state binding |
| Release v0.0.3.1 | ✅ Published | GitHub Actions tag release |
| **WakeLock & Reconnect Reset (B15 / Step 9c)** | ✅ Completed (v0.0.3.2) | `VoiceForegroundService.kt` + `LiveKitManager.kt` |
| **Hardware Button PTT Integration (Step 9b)** | ✅ Completed (v0.0.3.2) | `HardwarePTTManager.kt` + `RoomViewModel.kt` |
| **Custom Transmit High-Pass Filter (Step 9d)** | ✅ Completed (v0.0.3.2) | `VoxEngine.kt` DSP 300Hz Biquad HPF |
| **Monorepo Vercel Deployment Scoping** | ✅ Completed (v0.0.3.2) | Vercel Ignored Build Step `.vercelignore` |
| **Regional SOS Emergency Dispatch** | ✅ Completed (v0.0.3.2) | `EmergencyNumbers.kt` (cellular/SIM/locale) |
| **Crash-Proof Route Planner & Radar HUD** | ✅ Completed (v0.0.3.3) | `TacticalRadarCanvas.kt` + Mapbox fallback |
| **In-App OTA Installer Loop Fix** | ✅ Completed (v0.0.3.4) | `UpdateManager.kt` foreground launch & cache check |
| Release v0.0.3.4 | ✅ Published | GitHub Actions tag release (`Rider_APP-v0.0.3.4.apk`) |
| **Single WebRTC Capture Sink (Step 9a)** | ⏳ **PENDING** | Research & verification for v0.0.4.0 |
| **Live Settings Reactivity During Active Ride** | ⏳ **PENDING** | Scheduled for v0.0.4.0 |
| **Neural Noise Suppression (RNNoise)** | ⏳ **PENDING** | Scheduled for v0.0.4.0 |
| **Physical Hardware Field Testing (Steps 4 & 10)** | ⏳ **RUNTIME REQUIRED** | Device matrix below |

---

## 1. WakeLock & Background Execution Hardening (Bug B15 / Step 9c)

### What is Missing
In `VoiceForegroundService.kt`, a `PowerManager.WakeLock` property (`wakeLock`) is declared and checked in `onDestroy()`, but **it is never instantiated or acquired** in `onCreate()` or `onStartCommand()`. 

### Why It Matters
When a rider puts the phone into a pocket or tank bag and the screen turns off:
- Android's power manager aggressively suspends CPU execution after 15–60 seconds.
- Even with a foreground service, high-frequency audio polling in `VoxEngine` and WebRTC packet transmission will stutter, jitter, or halt entirely until the screen is turned on again.

### Where
- **File**: `mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt`
- **Functions**: `onCreate()`, `onDestroy()`

### How to Implement
1. Acquire a `PARTIAL_WAKE_LOCK` with a tag like `"RiderVoice:VoiceAudioLock"`.
2. Apply `setReferenceCounted(false)` so release cannot throw on edge cases.
3. Acquire the lock with a safe timeout (e.g. 10 hours) or while the service is alive.

```kotlin
// Inside VoiceForegroundService.kt:
override fun onCreate() {
    super.onCreate()
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "RiderVoice:VoiceAudioLock"
    ).apply {
        setReferenceCounted(false)
        acquire(10 * 3600 * 1000L) // 10h safety timeout
    }
    // ... rest of foreground service setup ...
}

override fun onDestroy() {
    wakeLock?.let {
        if (it.isHeld) it.release()
    }
    wakeLock = null
    super.onDestroy()
}
```

---

## 2. Network-Aware Reconnect Reset (Bug B15 / Step 9c)

### What is Missing
In `LiveKitManager.kt`, `reconnectAttempts` increments up to `MAX_RECONNECT_ATTEMPTS = 10` with exponential backoff up to 30 seconds. If a rider drives through a long tunnel, cellular dead zone, or mountain pass where network is lost for > 5 minutes:
- All 10 attempts are exhausted.
- The manager transitions to `ConnectionState.FAILED` and **stops reconnecting forever**.
- When the rider emerges back into 5G/LTE coverage, the app never attempts to reconnect unless the rider takes off their gloves, pulls out the phone, and manually exits and rejoins.

### Why It Matters
Motorcycle rides regularly cross cellular dead zones. The audio session must automatically heal once connectivity returns without manual rider intervention.

### Where
- **Files**:
  - `mobile-app/app/src/main/java/com/ridervoice/network/NetworkResilienceManager.kt` or `NetworkObserver.kt`
  - `mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt`

### How It Works
`ConnectivityManager.NetworkCallback` monitors default network availability. When connectivity transitions from unavailable/lost to available:
1. If `_connectionState.value == ConnectionState.FAILED` or `ConnectionState.RECONNECTING`, reset `reconnectAttempts = 0`.
2. Immediately trigger `connectInternal(lastUrl, lastToken)`.

### How to Implement
Add a `resetAndReconnect()` method to `LiveKitManager`:
```kotlin
fun onNetworkRestored() {
    if (_connectionState.value == ConnectionState.FAILED && lastUrl.isNotBlank() && lastToken.isNotBlank()) {
        Log.d(TAG, "Network restored — resetting reconnect backoff and reconnecting")
        reconnectAttempts = 0
        scope.launch { connectInternal(lastUrl, lastToken) }
    }
}
```
And wire the callback into `NetworkResilienceManager` or `ConnectivityManager.NetworkCallback`.

---

## 3. Hardware Button / Handlebar PTT Integration (Step 9b)

### What is Missing
`HardwarePTTManager.kt` contains an implementation for intercepting Bluetooth media button presses (`KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_HEADSETHOOK`) via `MediaSessionCompat`. However:
1. It is never instantiated or activated in `RoomViewModel` or `LiveKitManager` during an active ride.
2. It has an outdated internal call to `audioManager.requestAudioFocus(req)`, which violates the Single Audio Owner rule established in Step 2.

### Why It Matters
Riders wearing thick motorcycle gloves cannot tap the on-screen PTT button while riding. Motorcycle handlebar remotes and helmet intercom buttons (Sena, Cardo, FreedConn) emit standard AVRCP media events. Intercepting these events allows riders to trigger Push-To-Talk or Toggle-Mic without touching the phone screen.

### Where
- **Files**:
  - `mobile-app/app/src/main/java/com/ridervoice/audio/HardwarePTTManager.kt`
  - `mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt`
  - `mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt`

### How to Implement
1. Remove direct audio focus requests from `HardwarePTTManager.kt` (delegate focus ownership entirely to `AudioDeviceRouter` / `AudioFocusManager`).
2. In `RoomViewModel.joinRoom()`:
   ```kotlin
   hardwarePTTManager.onMicToggleRequest = { open ->
       // Toggle mode or push-to-talk override
       liveKitManager.onPttPressed(open)
   }
   hardwarePTTManager.activateSession()
   ```
3. In `RoomViewModel.cleanup()` / `leaveRoom()`:
   ```kotlin
   hardwarePTTManager.deactivateSession()
   ```

---

## 4. Single WebRTC Capture Sink (Step 9a — Architecture Optimization)

### What is Missing
Currently, the audio pipeline runs **two parallel audio captures**:
1. `VoxEngine.audioRecord` (standalone `AudioRecord` at 16 kHz `VOICE_COMMUNICATION`) reading 20 ms frames for RMS detection.
2. WebRTC's internal `AudioRecord` managed by LiveKit SDK for Opus encoding and transmission.

### Why It Matters
- **Resource Consumption**: Running two concurrent `AudioRecord` hardware capture instances consumes extra CPU and battery.
- **Hardware Route Contention**: On some Android hardware and Bluetooth headsets, two concurrent `AudioRecord` sessions on `VOICE_COMMUNICATION` can cause one instance to read silence or force Bluetooth SCO into fallback.
- **Detector Discrepancy**: VOX is measuring samples from a separate capture pipeline rather than the exact audio stream that WebRTC is transmitting.

### Where
- **Files**:
  - `mobile-app/app/src/main/java/com/ridervoice/audio/VoxEngine.kt`
  - `mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt`

### Prerequisites / Verification Needed
In LiveKit Android SDK 2.25.3, WebRTC audio frames can be tapped via `LocalAudioTrack` audio sinks or WebRTC `JavaAudioDeviceModule.SamplesReadyCallback`.
**Critical Check**: Does the audio sink continue to receive raw PCM audio buffers when the track is muted (`LocalParticipant.setMicrophoneEnabled(false)`)?
- **If YES**: `VoxEngine.audioRecord` can be deleted completely. Audio frames from the sink are routed directly into `VoxDetector.rms(frame, size)`.
- **If NO**: The separate `AudioRecord` in `VoxEngine` must be kept (current implementation), because when the gate is closed, the muted track would deliver no samples to VOX, making it impossible to detect speech to reopen the gate.

---

## 5. Live Audio Settings Reactivity During Active Ride

### What is Missing
In `RoomViewModel.kt`, `applyAudioSettings()` is executed once upon `joinRoom()`. If a rider opens the in-ride drawer, navigates to Settings, and modifies:
- VOX Sensitivity (Low / Medium / High)
- Open Mic (VOX Enabled / Disabled)
- Noise Cancellation Toggle

The changes are written to `SecurePreferences`, but **they are not re-applied to `voxEngine` or `liveKitManager` until the next ride**.

### Where
- **Files**:
  - `mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt`
  - `mobile-app/app/src/main/java/com/ridervoice/ui/viewmodels/SettingsViewModel.kt`

### How to Implement
Expose preference change flows or observe setting updates:
```kotlin
// In RoomViewModel.kt init:
viewModelScope.launch {
    settingsViewModel.settingsState.collect { state ->
        val sensitivity = when (state.voxSensitivity) {
            "Low" -> 0.2f
            "High" -> 0.8f
            else -> 0.5f
        }
        voxEngine.setSensitivity(sensitivity)
        voxEngine.setVoxEnabled(state.openMic)
        liveKitManager.noiseSuppressionEnabled = state.noiseCancellation
    }
}
```

---

## 6. Monorepo Vercel Build Scoping

### What is Missing
The repository is a monorepo containing:
- `mobile-app/` (Android Kotlin / Compose)
- `backend/` (Node.js Express / Prisma / LiveKit Server SDK)
- `web-app/` (Next.js 16 Web Dashboard)

Vercel is linked to the repository root. When commits only touch `mobile-app/` or `backend/`, Vercel triggers a deployment attempt and fails unless an Ignored Build Step is configured.

### Where
- **Vercel Dashboard**: Project Settings -> Git -> Ignored Build Step
- **Or Repo File**: `web-app/.vercelignore` / Root build script

### How to Resolve
In Vercel Project Settings for `rider-app`:
1. Ensure **Root Directory** is configured as `web-app`.
2. Under **Git -> Ignored Build Step**, set the command to:
   ```bash
   git diff HEAD^ HEAD --quiet .
   ```
   (This tells Vercel: if no files in `web-app` changed in this commit, skip the build with exit code 0 rather than failing the check).

---

## 7. Real-World Field Testing & Calibration Matrix (Steps 4 & 10)

Because audio routing and wind noise depend on physical hardware, these tests cannot be verified in an emulator and require a helmet headset on a real motorcycle:

| Test Case | Procedure | Acceptance Threshold |
|---|---|---|
| **1. Quiet Room PTT** | Disable VOX, hold PTT, speak, release | Audio heard within 300 ms; cut off within 1 s of release |
| **2. Bluetooth SCO Routing** | Connect Sena/Cardo helmet headset | Logcat shows `TYPE_BLUETOOTH_SCO`; mic input comes from helmet mic, not phone mic |
| **3. Mid-Call Reconnect** | Toggle Airplane Mode for 5s while in room | Re-connects to same room with exactly 1 mic publication; no hot mic |
| **4. Helmet Wind Noise @ 40 km/h** | Ride at 40 km/h with visor down | Noise floor adapts; no false VOX activations while silent |
| **5. Helmet Wind Noise @ 80 km/h** | Ride at 80 km/h | `speedExtra` ratio scales threshold; speech triggers gate within 2 words |
| **6. Helmet Wind Noise @ 120 km/h** | Ride at 120 km/h | Gate does not latch open; adapts within 20s of high wind blast |
| **7. Deafen Verification** | Tap DEAFEN in UI | Remote participants are muted locally; your mic can still transmit |
| **8. Background Audio (Screen Off)** | Lock screen for 10 minutes with convo active | Audio remains connected; no stutter or sleep kills |
