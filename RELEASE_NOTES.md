## RiderVoice v0.0.3.15 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.15** delivers enterprise audio hardening and completes the comprehensive master audit resolution. It features strict LiveKit room name normalization and webhook signature idempotency, Android 14+ foreground service types (`microphone`, `location`, `connectedDevice`), resilient Bluetooth SCO hardware state machine recovery, enhanced packet loss tracking, and full automated test suite coverage.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.15` |
| **Version Code** | `3015` |
| **Release Tag** | [`v0.0.3.15`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.15) |
| **Download APK** | [`Rider_APP-v0.0.3.15.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.15/Rider_APP-v0.0.3.15.apk) |

---

### 🛡️ LiveKit Audio Pipeline & Webhook Hardening
- **Signature & Idempotency**: Cryptographic verification of LiveKit webhook headers (`webhookReceiver.receive(body, authHeader)`) with Redis/in-memory deduplication caching on `event.id`, preventing repeated participant disconnect processing.
- **Room Identity Normalization**: Strict room name canonicalization across token generation, active room checks, and lobby routing.

---

### 📱 Android 14+ Foreground Service & Audio HAL
- **Service Types Compliance**: Full manifest and runtime declaration of `microphone`, `location`, and `connectedDevice` foreground service types on Android 14 (API 34).
- **Bluetooth SCO State Machine**: Resilient hardware connection listeners with automatic fallback and physical recovery handling when headsets disconnect mid-ride.

---

## RiderVoice v0.0.3.14 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.14** resolves OEM accessibility hover gesture crashes on Android 9 devices (Vivo/Oppo/MIUI), fixes convoy room fragmentation by reusing host room instances, authorizes guest riders to view the convoy lobby and wait for the ride to begin, automatically synchronizes ride start across all lobby members, and routes accepted callsign invites directly to the convoy lobby.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.14` |
| **Version Code** | `3014` |
| **Release Tag** | [`v0.0.3.14`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.14) |
| **Download APK** | [`Rider_APP-v0.0.3.14.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.14/Rider_APP-v0.0.3.14.apk) |

---

### 🛡️ Android 9 OEM Accessibility Crash Protection
- **ACTION_HOVER_EXIT Handler**: Trapped unhandled `IllegalStateException` crashes in `MainActivity.dispatchGenericMotionEvent` and `dispatchTouchEvent` caused by vendor accessibility overlays (e.g., Vivo Jovi / TalkBack) injecting synthetic hover exit events into Jetpack Compose pointer input stages.

---

### 🏍️ Convoy Room Unification & Host Room Reuse
- **Room Instance Reuse**: Creating a convoy with an existing name owned by the same host now reuses the room, resets ride start timestamps, and clears stale lobby caches instead of generating suffixed rooms (`Ride-2`, `Ride-3`), keeping hosts and riders unified in the exact same audio room.
- **Case-Insensitive Room Resolution**: Room lookup across token generation, join via token, and lobby status gracefully resolves uppercase and lowercase variations without 404 or 403 errors.

---

### 👥 Synchronized Convoy Lobby & Dispatch Routing
- **Guest Rider Lobby Authorization**: Extended lobby status endpoint authorization to all accepted convoy participants, displaying real-time squad readiness to both the host and riders.
- **Automated Ride Start Transition**: Riders in the lobby automatically transition into device setup and the active ride HUD the instant the host initiates the convoy ride.
- **Callsign Invite Navigation**: Accepting an invite from the Dispatch Inbox or joining via room code navigates riders directly into the convoy lobby with pre-warmed LiveKit session credentials.

---

## RiderVoice v0.0.3.13 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.13** delivers seamless hands-free VOX auto-gate detection for motorcycle helmets, synchronized host end-ride termination and teardown across convoy members, resilient invite code & deep link URL resolution, and refined compiler hygiene.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.13` |
| **Version Code** | `3013` |
| **Release Tag** | [`v0.0.3.13`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.13) |
| **Download APK** | [`Rider_APP-v0.0.3.13.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.13/Rider_APP-v0.0.3.13.apk) |

---

### 🎙️ Hands-Free VOX Intercom Auto-Gate
- **Speech Auto-Detection**: Microphone channel automatically opens upon detecting voice directly from the WebRTC audio frame stream, providing true hands-free communication for helmet riders without touching buttons.
- **Automatic Silence Hold & Release**: Voice gate automatically closes after speech completes, maintaining clean audio without clipping natural syllable pauses.
- **Dual Stream Evaluation**: Dynamically evaluates acoustic levels both when mic is open and closed, eliminating hardware conflicts on Bluetooth SCO helmet headsets.

---

### 🏁 Synchronized Convoy Termination & Exit Teardown
- **Host End-Ride Broadcast**: Broadcasts `CONVOY_ENDED` signal across LiveKit data channels immediately when the host ends the ride, notifying all convoy participants with zero delay.
- **Automated Teardown & Room Exit**: Non-host riders automatically exit room screens, release hardware audio devices, cleanly terminate foreground services, and navigate back to ride summary.
- **LiveKit Room Deletion Detection**: Gracefully recognizes LiveKit server room deletion and suppresses stale reconnection loops.

---

### 🔗 Resilient Invite Resolution & Deep Linking
- **URL & Code Extraction**: Automatically extracts clean convoy tokens from full web links (`https://.../join/TOKEN`, `ridervoice://...`), uppercase input, or raw join codes.
- **Case-Insensitive Resolution**: Fallback queries preserve invite link validity regardless of keyboard autocorrect or casing.

---

## RiderVoice v0.0.3.12 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.12** introduces low-latency VOX audio release tuning with responsive polling intervals, eliminates packet overhead by disabling redundant encoding for low-bitrate intercom streams, adds in-flight cache stampede protection on backend services, implements automatic rate-limiter memory reclamation, and reinforces Android foreground service coroutine lifecycle management.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.12` |
| **Version Code** | `3012` |
| **Release Tag** | [`v0.0.3.12`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.12) |
| **Download APK** | [`Rider_APP-v0.0.3.12.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.12/Rider_APP-v0.0.3.12.apk) |

---

### 🎙️ VOX Engine & Latency Optimization
- **Responsive Mic Release**: Reduced open-mic polling delay to 20ms and shortened safety-net inactivity cutoff from 5000ms to 2500ms, releasing the microphone channel faster when speaking ceases.
- **Accurate Noise Floor Calibration**: Validates non-zero acoustic frames during initialization to prevent initial threshold miscalibration.
- **Eliminated RED Packet Jitter**: Disabled redundant packet encoding on 32kbps audio channels, cutting unnecessary packet size overhead on cellular connections.

---

### 🛡️ Network Resilience & Memory Optimization
- **Emergency Limiter Pruning**: Added periodic timestamp sweeping to prevent sliding-window rate limiters from accumulating unreferenced memory on the backend.
- **Cache Stampede Guard**: Implemented in-flight Promise tracking in backend caching to ensure concurrent callers share a single fetch execution.
- **Foreground Service Hardening**: Upgraded voice foreground service to `SupervisorJob` and ensured complete coroutine scope cancellation on service destruction.

---

## RiderVoice v0.0.3.11 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.11** introduces automated convoy namespace disambiguation allowing squad hosts to freely reuse convoy names without collisions, establishes resilient SMTP connection pooling with proactive dead-socket pruning and SSL fallback on port 465, resolves relational database queries on ride invite acceptance, and isolates push notification dispatch for crash-proof squad coordination.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.11` |
| **Version Code** | `3011` |
| **Release Tag** | [`v0.0.3.11`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.11) |
| **Download APK** | [`Rider_APP-v0.0.3.11.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.11/Rider_APP-v0.0.3.11.apk) |

---

### 🏍️ Convoy Namespace Disambiguation & Room Creation
- **Identical Convoy Name Support**: Eliminated 409 Conflict errors when multiple riders create convoys sharing the same name. Automatically assigns clean disambiguated room identifiers while preserving user-facing convoy naming.
- **Relational Integrity on Invite Acceptance**: Resolved Prisma nested relation query mismatch on `RideInvite.findUnique`, preventing runtime 500 exceptions when squad members accept invitations.
- **Fail-Safe Push Notifications**: Isolated FCM notification dispatch on invite confirmation so third-party push hiccups never interrupt or fail the HTTP response.

---

### 📧 SMTP Reliability & Connection Pooling
- **Connection Pooling**: Integrated Nodemailer connection pooling (`maxConnections: 3`, `maxMessages: 100`, `idleTimeout: 30000`) preventing stale connections from timing out on successive email dispatches.
- **Port 465 SSL Default**: Defaulted diagnostic and notification dispatchers to direct SSL on port 465 with explicit socket timeouts, bypassing cloud host port 587 timeouts.
- **Auto-Recovery Reconnect**: Automatically purges stale pool sockets and provisions a fresh transporter with a seamless single retry on socket errors.

---

## RiderVoice v0.0.3.10 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.10** optimizes VOX responsiveness with reduced silence hold timing for crisper intercom turn-taking, delivers rich squad participant metadata with proper display names across LiveKit channels, introduces instant backend port binding for fast platform initialization, and enhances transactional SMTP notifications.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.10` |
| **Version Code** | `3010` |
| **Release Tag** | [`v0.0.3.10`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.10) |
| **Download APK** | [`Rider_APP-v0.0.3.10.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.10/Rider_APP-v0.0.3.10.apk) |

---

### 🎙️ VOX Engine & Audio Pipeline Optimization
- **Tuned Speech Hold Window**: Reduced VOX silence hold duration from 1400ms to 900ms (`HOLD_MS = 900L`), significantly eliminating conversational dead-air and improving helmet intercom turn-taking while riding at speed.
- **Non-Contending Capture Evaluation**: Suspends local `AudioRecord` polling while transmitting, preventing hardware driver contention and CPU wakeups.
- **Lightweight Audio Threading**: Removed noisy logging from high-frequency audio callbacks to minimize garbage collector pressure on lower-end hardware.

---

### 👥 Squad Metadata & Lobby Resilience
- **Rich Participant Models**: Upgraded LiveKit participant subscriptions to map both identities and display names into structured `Participant` objects.
- **Lobby Coroutine Lifecycle**: Bound lobby polling to coroutine scope activity (`isActive`) with proper cleanup in `onCleared()`, preventing orphan polling tasks.
- **Frictionless Error Feedback**: Added descriptive error messages for expired or invalid convoy join links (404/410/403).

---

### 🌐 Backend & Infrastructure Resilience
- **Instant Port Binding**: Server binds HTTP listener immediately on start, allowing cloud hosters (e.g. Render) to satisfy health-checks without waiting for DDL completion.
- **Pooler-Safe Async Migrations**: Executes database schema extensions asynchronously in a transaction-pooler safe manner.
- **Transactional Comms Relay**: Integrated HTML email templates via authenticated SMTP for tester onboarding, squad dispatch, and ride completion debriefs.

---

## RiderVoice v0.0.3.9 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.9** introduces full hardware route confirmation for Bluetooth headsets, strict room token security verification, and a complete user consent experience for convoy diagnostics. Audio routing now waits for Android framework hardware confirmations (`OnCommunicationDeviceChangedListener`) with automated 3-second fallback to loudspeaker, exposing separate requested and real routed device flows to the UI with in-HUD mismatch detection and one-tap re-evaluation.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.9` |
| **Version Code** | `3009` |
| **Release Tag** | [`v0.0.3.9`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.9) |
| **Download APK** | [`Rider_APP-v0.0.3.9.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.9/Rider_APP-v0.0.3.9.apk) |

---

### 🎧 Bluetooth Hardware Carrier Routing (Android)
- **AudioRouteDecisionEngine Integration**: Directly wired pure decision engine into `AudioDeviceRouter`. Devices only transition to `ACTIVE` once `AudioManager.communicationDevice?.id` matches the target hardware device.
- **Hardware Routing Listener & Fallback**: Registers `OnCommunicationDeviceChangedListener` (API 31+) with a 3-second confirmation timeout, attempting subsequent audio candidates before safely falling back to Loudspeaker (`AudioDevice.Speakerphone`, never Earpiece).
- **Separated Requested vs Routed StateFlows**: Exposes real `routedInput` (via `AudioRecord.addOnRoutingChangedListener`) and `routedOutput`.
- **LiveKit Track Synchronization**: Defers `publishAudioTrack` until router hardware status is confirmed, and republishes dynamically if the hardware route transitions late.
- **2-Line HUD Audio Badge & Device Setup UX**: Displays `"Mic: <input>"` and `"Speaker: <output>"`, prompting riders with *"Using phone audio, headset not active"* and a *"Retry"* button on routing mismatches.

---

### 🛡️ Room Token Validation & Convoy Diagnostics UX
- **Room Token Security**: Restricts token issuance to validated active rooms and accepted invites; verifies `video.room` claim on join.
- **Privacy & User Consent**: Defaults diagnostics to `false` everywhere; presents a first-time consent disclosure with an explicit *"Don't share my data"* opt-out.
- **Host End Grace Window**: Gracefully allows riders up to 90 seconds to submit outbox telemetry upon host end before closing the run.

---

## RiderVoice v0.0.3.8 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.8** introduces the end-to-end **Network Diagnostics & Convoy Telemetry** subsystem across Android and the Node.js backend. Riders can now record active network and voice-quality telemetry during a convoy ride without modifying or risking existing ride history. Telemetry samples are written immediately to a separate SQLite Room database (`DiagnosticsDatabase`), streamed opportunistically via Hilt-injected WorkManager in idempotent chunks, aggregated per convoy test run, exported into zero-disk in-memory multi-rider ZIP packages (`self.csv`, `inbound.csv`, `events.csv`, `summary.json`, `run_summary.md`), and emailed directly via authenticated SMTP relay.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.8` |
| **Version Code** | `3008` |
| **Release Tag** | [`v0.0.3.8`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.8) |
| **Download APK** | [`Rider_APP-v0.0.3.8.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.8/Rider_APP-v0.0.3.8.apk) |

---

### 📡 Network Diagnostics & Voice Telemetry (Android)
- **Separate Crash-Safe Database**: Implemented `DiagnosticsDatabase` ("diagnostics.db") completely isolated from `RideDatabase` (preventing accidental destructive migrations).
- **Foreground Service Scope**: Runs inside `VoiceForegroundService` so collection reliably survives app backgrounding.
- **2-Second WebRTC & Device Sampling**:
  - **SELF Metrics**: LiveKit RTT, WebRTC packets/bytes sent & received, retransmits, target/actual bitrate, connection quality, network transport type, cell operator/technology, signal strength (dBm), battery %, thermal status, and app lifecycle state.
  - **INBOUND Per-Remote-Rider Metrics**: Track packet loss % calculated dynamically via `DeltaLossCalculator` with automatic sequence reset detection and negative clamping, jitter buffer delay, concealed samples ratio, and audio level.
  - **Events Stream**: PTT press/release, VOX trigger, mute/unmute, reconnecting/reconnected transitions, and network handoffs.
- **HUD Indicator & Settings UX**:
  - Prominent red `[• REC]` indicator in active ride HUD when diagnostics are active.
  - First-time consent and privacy disclosure `AlertDialog` before activation.
  - Real-time outbox upload status displayed directly under the Settings toggle.
- **Reliable Outbox Upload**: Chunked into batches (<= 200 items) with persistent sequence numbers and exponential backoff retry via WorkManager.

---

### 🖥️ Backend Diagnostic Engine & SMTP Relay (Node.js)
- **Convoy Run Auto-Numbering**: Idempotent `POST /diagnostics/runs/join` allocates sequential "Test N" runs per convoy.
- **Idempotent Batch Ingestion**: Composite unique key `(runId, riderId, sequenceNumber)` ignores duplicate retries.
- **Security & Hardening**:
  - Strict authentication: Test-auth bypass disabled outside `NODE_ENV=test` and rejected on non-localhost databases.
  - CSV Formula Injection protection: neutralizes `=, +, -, @, \t, \r` prefixes with single quotes per RFC 4180.
  - Path traversal neutralization: Sanitizes rider handles and room names.
  - Constant-time admin key validation using SHA-256 digest comparison.
- **Multi-Rider Zero-Disk ZIP Export & SMTP Relay**:
  - Compiles `self.csv`, `inbound.csv`, `events.csv`, `summary.json`, and `run_summary.md` entirely in memory.
  - Automated SMTP email dispatch with attachment size thresholding (summaries emailed if archive > 20 MB).
  - Background scheduler automatically closes idle runs after 10 minutes and cleans up runs older than 30 days.

---

## RiderVoice v0.0.3.7 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.7** eliminates the critical P0 voice audio cut where VOX speech would automatically drop after ~2 seconds (`VOX-CUT-1`), bridges WebRTC audio device module transmit frames directly to the VOX RMS detector, introduces continuous speech regression test coverage (`VOX-CUT-2`), removes dead code and obsolete stubs (`DEAD-1..11`), streamlines Settings UI/UX with consumer labels and inlined headset controls (`UX-1..5`), and introduces speed-tiered GNSS battery optimizations with coroutine leak fixes (`BAT-1..3`).

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.7` |
| **Version Code** | `3007` |
| **Release Tag** | [`v0.0.3.7`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.7) |
| **Download APK** | [`Rider_APP-v0.0.3.7.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.7/Rider_APP-v0.0.3.7.apk) |

---

### 🎙️ P0 Voice Engine Fix (VOX-CUT-1 & VOX-CUT-2)
- **Eliminated 2-Second Forced Mic Cut**:
  - Previously, `openMic(isFromVox=true)` scheduled an unconditional `delay(1800ms)` hold timer (`scheduleVoxHold()`) while suspending local `AudioRecord` capture. The engine became starved of incoming audio frames during transmission, causing the mic to abruptly cut mid-sentence every ~1.8–2 seconds.
  - Completely removed the forced timer. Integrated `JavaAudioDeviceModule.setSamplesReadyCallback` in `LiveKitManager` to deliver active transmit PCM frames to `VoxEngine.onTxFrame(data, sampleRate)`.
  - Continuous speech now continuously computes RMS, updates dynamic noise floor, and keeps the channel open indefinitely until the speaker stops talking (plus the configured hold period).
  - Added safety net watchdog ensuring transmission recovers gracefully if audio frames stop arriving.
- **Regression Test & Field Test Coverage**:
  - Added `continuousSpeechDoesNotCutAfter2Seconds()` in `VoxDetectorTest.kt` simulating 30 seconds of active conversation.
  - Added test case **FT-24** to the Physical Hardware Field Testing Matrix.

---

### 🧹 Dead Code Purge & Architecture Cleanup (DEAD-1..11)
- Removed dead and obsolete stub classes: `CrashRecoveryManager`, `RoomStateSynchronizer`, `TacticalDebugOverlay`, `ConnectionQualityMonitor`, `ReconnectManager`, `SessionRestore`, and `NetworkHandoffManager`.
- Replaced monolithic `TacticalMessagingService` with clean, dedicated `FcmService` properly registered in AndroidManifest.
- Retained core active classes `SquadRepository`, `TacticalButton`, and `EmergencyNumbers`.

---

### 📱 UI & Settings Structural Overhaul (UX-1..5)
- **Unified Secure Preferences**: Migrated headset preferences (`settings_hardware_ptt`, `settings_hybrid_vox`) from unencrypted raw preferences to `SecurePreferences`, applied directly in `RoomViewModel.applyAudioSettings()`.
- **Inlined Headset Controls**: Inlined headset PTT and VOX settings directly into `SettingsScreen` under "AUDIO", eliminating the redundant `HeadsetSettingsScreen` and dedicated route.
- **Removed Fake / Non-Functional Settings**: Purged dead UI items (`micSensitivity`, `speedForHud`, `reconnectMode`, `transceiverFirmware`) and duplicate audio route picker.
- **Clean Consumer Labels**: Renamed tactical military jargon to clear rider labels ("My Profile", "Dark Mode", "AUDIO", "COMMUNICATION", "Open Mic (always transmit)", "DISPLAY", "RIDE HUD", "Glove Mode", "UPDATES").

---

### ⚡ Battery & System Optimization (BAT-1..3)
- **Speed-Tiered Dynamic GNSS Polling**:
  - `> 60 km/h`: 3,000 ms, `PRIORITY_HIGH_ACCURACY` (tight convoy tracking).
  - `15–60 km/h`: 5,000 ms, `PRIORITY_HIGH_ACCURACY`.
  - `< 15 km/h`: 10,000 ms, `PRIORITY_BALANCED_POWER_ACCURACY`.
  - Stationary `> 120 s`: 60,000 ms, `PRIORITY_BALANCED_POWER_ACCURACY`.
- **Foreground WakeLock Timeout**: Added 4-hour safety timeout cap on `PARTIAL_WAKE_LOCK` in `VoiceForegroundService` to prevent runaway battery drain.
- **Coroutine Scope Leaks**: Replaced unmanaged coroutine launches in `NetworkResilienceManager` and `RideRecorder` with managed `SupervisorJob` scopes.

---

## RiderVoice v0.0.3.6 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.6** eliminates GitHub REST API rate-limiting errors (`HTTP 403 Forbidden`) with an automated web redirect release resolver, resolves app crashes on launch caused by Android Keystore / `EncryptedSharedPreferences` invalidation with a self-healing fallback engine, fixes OEM `ContextThemeWrapper` class-cast crashes on Samsung/Xiaomi devices, hardens ProGuard/R8 reflection rules for Retrofit continuations, and introduces a built-in Diagnostic Crash Reporter modal.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.6` |
| **Version Code** | `3006` |
| **Release Tag** | [`v0.0.3.6`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.6) |
| **Download APK** | [`Rider_APP-v0.0.3.6.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.6/Rider_APP-v0.0.3.6.apk) |

---

### 🛡️ Crash-Proof Runtime & Self-Healing Architecture
- **Self-Healing `SecurePreferences`**:
  - Android `EncryptedSharedPreferences` could throw unhandled `KeyStoreException` or `AEADBadTagException` during app updates or when device keys were invalidated, causing the app to crash before the splash screen.
  - Implemented automatic two-stage recovery: clears corrupted prefs and stale keys on failure, and gracefully falls back to private `SharedPreferences` to ensure **the app never crashes on launch**.
- **OEM `ContextThemeWrapper` Fix**:
  - Fixed `ClassCastException` in `Theme.kt` by safely unwrapping `ContextWrapper` chains to locate the host `Activity` before modifying status/navigation bars.
- **R8 / ProGuard Reflection Hardening**:
  - Removed `allowobfuscation` from `kotlin.coroutines.Continuation`, `retrofit2.Response`, and `retrofit2.Call` to prevent R8 from stripping reflective metadata required by Retrofit suspend functions.
- **Global In-App Diagnostic Crash Reporter**:
  - Added a global uncaught exception handler in `App.kt` writing diagnostic reports to `last_crash.txt` and Firebase Crashlytics.
  - Added an in-app `DIAGNOSTIC CRASH REPORT` dialog in `MainActivity.kt` with a "COPY REPORT" button for instant debugging.

---

### ⚡ Rate-Limit-Proof GitHub Releases Engine
- **Transparent Web Redirect Fallback**:
  - On `HTTP 403` or `429` (GitHub API rate limit exceeded), `UpdateManager` automatically falls back to `https://github.com/BuzyU/Rider_APP/releases/latest`, inspecting the `Location` header to resolve the latest version and download assets with **zero rate limits**.
- **ETag Caching & Memory Throttling**:
  - Added `If-None-Match` HTTP ETag caching (returns 304 without consuming rate quota) and a 60-second in-memory throttle.

---

## RiderVoice v0.0.3.5 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.5** fixes a critical LiveKit WebRTC audio pipeline failure where audio showed as transmitting and sessions were live on LiveKit Cloud but no voice was transmitted or heard, resolves Android `AudioRecord` hardware lock contention on `VOICE_COMMUNICATION`, disables Opus DTX silence suppression in walkie-talkie mode, synchronizes local and remote audio track lifecycle states, and adds idempotent Supabase SQL migrations.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.5` |
| **Version Code** | `3005` |
| **Release Tag** | [`v0.0.3.5`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.5) |
| **Download APK** | [`Rider_APP-v0.0.3.5.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.5/Rider_APP-v0.0.3.5.apk) |

---

### 🎙️ LiveKit WebRTC Audio Pipeline & Hardware Contention Fix
- **Eliminated Concurrent `AudioRecord` Lock Contention**:
  - `VoxEngine` previously opened an `AudioRecord` directly targeting `MediaRecorder.AudioSource.VOICE_COMMUNICATION` while WebRTC opened a second capture on the same HAL session. Android's AudioPolicy / AudioFlinger silenced the secondary capture, resulting in zero-amplitude audio being transmitted.
  - Updated `VoxEngine.evaluateHardwareRecordingState()` to immediately suspend (`audioRecord.stop()`) while transmitting (`_isMicOpen.value || pttOverride`), granting WebRTC 100% exclusive, conflict-free access to the physical microphone hardware.
  - Implemented automatic hold timer scheduling (`scheduleVoxHold()`) so VOX hands-free speech bursts stay open through the utterance and resume monitoring smoothly.
- **Track Start, Prewarm & DTX Optimization**:
  - `LiveKitManager.publishAudioTrack` now explicitly calls `track.start()` and `track.prewarm()` upon creation.
  - Switched from `AudioTrackPublishOptions(dtx = true)` to `dtx = false` to eliminate Opus DTX silence suppression and packet cutoffs in walkie-talkie mode.
  - Explicitly synchronized `localAudioTrack?.enabled = want` in `applyMic()`.
  - Added explicit `remoteTrack.enabled = true` on `RoomEvent.TrackSubscribed` to guarantee playout.
- **Audio Routing & Call Volume Safety**:
  - Added a startup volume sanity check in `AudioDeviceRouter` ensuring `STREAM_VOICE_CALL` volume is never 0/muted upon entering communication mode.
  - Enforced `isSpeakerphoneOn = true` alongside `setCommunicationDevice` to guarantee loudspeaker output on devices without headsets.

---

### 🗄️ Supabase Database Updates
- **Complete Idempotent Migration Script**:
  - Added [`backend/supabase_migrations_latest.sql`](backend/supabase_migrations_latest.sql) and updated [`backend/supabase_schema.sql`](backend/supabase_schema.sql).
  - Safely creates `user_role` enum (`CUSTOMER`, `ADMIN`) and `User.role` column.
  - Adds `InviteStatus.REMOVED` enum value.
  - Adds `RideSession.roomName` column.
  - Creates `RoomJoinToken`, `EmergencyAlert`, and `admin_settings` tables with proper cascade foreign keys and composite performance indexes.

---

## RiderVoice v0.0.3.4 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.4** resolves an issue where the in-app OTA firmware updater could get stuck in an installation loop, introduces foreground activity launching for the system package installer, implements explicit FileProvider URI permission granting across all package installer targets, and adds intelligent update caching to prevent redundant 55MB APK re-downloads.

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `0.0.3.4` |
| **Version Code** | `3004` |
| **Release Tag** | [`v0.0.3.4`](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.4) |
| **Download APK** | [`Rider_APP-v0.0.3.4.apk`](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.4/Rider_APP-v0.0.3.4.apk) |
| **SHA-256 Checksum** | `3153d029f385d87c2c4648efedcc8fa6d78dcd037d19fcff646c8fa69cfce0f4` |

---

### 📲 In-App OTA Updater: Installation Loop Fix
- **Foreground Activity Launching (Android 10–14 Background Restriction Fix)**:
  - Added foreground `Activity` tracking via weak references in [`UpdateManager.kt`](mobile-app/app/src/main/java/com/ridervoice/update/UpdateManager.kt).
  - Directly launches the system package installer from the foreground activity instead of `ApplicationContext`, resolving background launch restrictions that silently swallowed the installer on OEM Android skins (OneUI, MIUI, ColorOS).
- **Explicit FileProvider URI Permission Granting**:
  - Explicitly grants `Intent.FLAG_GRANT_READ_URI_PERMISSION` to all package installer candidate packages via `PackageManager.queryIntentActivities(...)`, preventing `SecurityException: Permission Denial` during system APK parsing.
- **Loop-Proof State Transitions**:
  - Upon return from "Allow from this source" in Settings, immediately transitions to `ReadyToInstall` with a prominent **"INSTALL UPDATE"** button while seamlessly triggering the installer prompt.
  - Added a **"RETRY INSTALLER"** action button to [`UpdateDialog.kt`](mobile-app/app/src/main/java/com/ridervoice/ui/components/UpdateDialog.kt) so riders can re-trigger the installation prompt if accidentally dismissed without restarting the download.
- **Smart APK Caching**:
  - `UpdateManager.checkForUpdates` now validates whether an update APK matching the release version and SHA-256 digest is already present in cache. If found, it immediately offers `ReadyToInstall` without re-downloading 55 MB of mobile data.

---

## RiderVoice v0.0.3.3 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.3** resolves critical release build reflection stripping in Retrofit/R8, fixes Route Planner startup crashes by introducing a crash-proof offline Tactical Radar HUD with safe Mapbox fallback, and enhances release stability across Android devices.

---

### 🛰️ Route Planner & Crash-Proof Tactical Radar HUD
- **Crash Prevention on Route Planner Launch**:
  - Fixed an unhandled `MapboxConfigurationException` caused by a missing `mapbox_access_token` string resource during Mapbox Compose instantiation in [`RoutePlannerScreen.kt`](mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoutePlannerScreen.kt).
  - Configured safe access token fallbacks in [`mobile-app/app/src/main/res/values/strings.xml`](mobile-app/app/src/main/res/values/strings.xml).
- **Tactical Waypoint Radar Canvas (Offline-Safe)**:
  - Implemented an authentic dark military/aviation motorcycle HUD Canvas with zero third-party API dependencies.
  - Features concentric range rings (5km, 10km, 15km, 25km), cardinal axes (N, S, E, W), dynamic 360° radar sweep beam, and real-time rider GPS pulse.
  - Plots live squad rider blips relative to current position with handles and distance badges.
  - Automatically draws waypoint trajectory vectors with distance, elevation gain, and estimated duration metrics.
- **Dual Engine Toggle & In-App Mapbox Key Dialog**:
  - Added an in-HUD toggle pill between **Tactical Radar HUD** and **Mapbox Satellite/Vector View**.
  - Provides an in-app dialog allowing riders to paste custom Mapbox public keys (`pk.eyJ...`), securely persisting them to SharedPreferences without app restarts.

---

### 🛡️ R8 & Retrofit Reflection Stability
- **Fixed `Class cannot be cast to ParameterizedType` on Dispatch Inbox**:
  - Disabled R8 Full Mode aggressive metadata stripping via `android.enableR8.fullMode=false` in [`mobile-app/gradle.properties`](mobile-app/gradle.properties).
  - Enhanced [`mobile-app/app/proguard-rules.pro`](mobile-app/app/proguard-rules.pro) with comprehensive `-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod` and explicit keep rules for Retrofit service interfaces, `Call`, `Response`, Kotlin continuations, and Gson `TypeToken`.
  - Refined error copy on [`InvitesInboxScreen.kt`](mobile-app/app/src/main/java/com/ridervoice/ui/screens/InvitesInboxScreen.kt).

---

## RiderVoice v0.0.3.2 Release Notes

### 🚀 Overview
RiderVoice **v0.0.3.2** delivers mission-critical safety enhancements, an intuitive and frictionless in-app updater experience, major audio/VoIP optimizations, and battery conservation for extended group rides.

---

### 🛡️ Safety & SOS Emergency Services
- **Regional Emergency Number Detection**:
  - Replaced hardcoded `911` with automatic regional emergency number resolution via [`EmergencyNumbers.kt`](mobile-app/app/src/main/java/com/ridervoice/utils/EmergencyNumbers.kt).
  - Automatically queries the active cellular network ISO (supports international roaming) with fallback to SIM card ISO and device system Locale.
  - Covers 100+ countries and territories (e.g., **911** for US/Canada, **999** for UK, **000** for Australia, **119/110** for Japan, and **112** standard across Europe, India, and GSM global networks).
  - Dynamically updates the emergency call dialer buttons and countdown overlays in both `SosScreen` and full-screen `EmergencyAlertActivity`.

---

### 📲 Seamless In-App APK Auto-Updater
- **Frictionless Unknown App Sources Installation**:
  - Updated [`UpdateManager.kt`](mobile-app/app/src/main/java/com/ridervoice/update/UpdateManager.kt) to handle Android's `REQUEST_INSTALL_PACKAGES` permission seamlessly.
  - Automatically invokes the system package installer immediately upon successful SHA-256 checksum and package verification.
  - If "Install Unknown Apps" permission is not yet granted, securely stores the pending APK verification token and opens the specific Android Settings screen.
  - Employs `ActivityLifecycleCallbacks` to detect when the user resumes the RiderVoice application: once granted, immediately presents the system installer prompt without requiring the user to tap "Install" or re-download the APK.

---

### 🎙️ Audio, VoIP & Network Resilience
- **Opus Discontinuous Transmission (DTX)**:
  - Enabled WebRTC Opus DTX on audio tracks in [`LiveKitManager.kt`](mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt), halting silent packet transmissions to reduce cellular radio drain.
- **Background WakeLock Acquisition**:
  - Wired a non-refcounted `PARTIAL_WAKE_LOCK` with a 10-hour safety ceiling in [`VoiceForegroundService.kt`](mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt) to prevent OEM aggressive background kills while screen is off.
- **Network Restoration Auto-Reconnect**:
  - Connected network state callbacks to reset reconnection backoff counters when transitioning from cellular deadzones back into network coverage.
- **Hardware Push-to-Talk (PTT)**:
  - Enabled handlebar/headset media button interception in [`HardwarePTTManager.kt`](mobile-app/app/src/main/java/com/ridervoice/audio/HardwarePTTManager.kt) with modern `AudioAttributes` usage.
- **PTT Capture Suspension**:
  - Dynamically suspends `AudioRecord` read loops in [`VoxEngine.kt`](mobile-app/app/src/main/java/com/ridervoice/audio/VoxEngine.kt) when in PTT mode without an active push, saving significant CPU and battery power.

---

### 🔋 Battery, GPS & Compose UI Performance
- **Stationary GPS Sleep**:
  - [`LocationService.kt`](mobile-app/app/src/main/java/com/ridervoice/services/LocationService.kt) now detects when the motorcycle has been parked or stationary for >120 seconds and steps down GPS polling from 2–5s high accuracy to 60s balanced power, saving up to 40% battery at rest stops.
- **Compose 50 Hz UI Recomposition Elimination**:
  - Refactored [`RoomScreen.kt`](mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt) to isolate audio amplitude state collection to the leaf `SegmentedVuMeter` component, preventing 50 frame-per-second recomposition of the entire screen tree.
- **Ride Recording Batching & 1km Phantom Fix**:
  - Fixed initial GPS waypoint jumping bug and batched Room SQLite database writes in [`RideRecorder.kt`](mobile-app/app/src/main/java/com/ridervoice/services/RideRecorder.kt).

---

### 🔒 Backend & Security Hardening
- **Reverse Proxy Header Trust**:
  - Added `app.set('trust proxy', 1)` in [`backend/src/server.js`](backend/src/server.js) to accurately respect client IP addresses and protocol headers behind Render, Vercel, or Nginx.
- **SOS Cancellation Ownership Check**:
  - Enforced that only the user who triggered an SOS (or an authorized admin) can resolve or cancel it in [`backend/src/routes/emergencyRoutes.js`](backend/src/routes/emergencyRoutes.js).
- **Dual `@handle` and Email Search**:
  - Enhanced squad member search in [`backend/src/routes/userRoutes.js`](backend/src/routes/userRoutes.js) to seamlessly handle searches with or without leading `@` symbols as well as full email lookups.

---

### 🛠️ Build & CI/CD Pipeline
- **Target ABI Filtering**: Restricted NDK builds to `arm64-v8a` and `armeabi-v7a`, trimming unnecessary x86/x86_64 desktop emulation binaries and reducing APK size.
- **ProGuard Optimization & Resource Shrinking**: Enabled `minifyEnabled` and `shrinkResources` with comprehensive keep rules for LiveKit, Mapbox, and Room.
- **Automated Multi-Project CI**: `.github/workflows/build.yml` now runs automated validation across Android unit tests, backend syntax, and the Next.js web application.
- **Automated Dynamic Release Descriptions**: Configured `.github/release.yml` and `.github/workflows/release.yml` to automatically publish categorized changelogs and release highlights for every release tag.
