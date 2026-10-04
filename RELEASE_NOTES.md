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
