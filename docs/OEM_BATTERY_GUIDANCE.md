# OEM Battery Killer Workaround Runbook (WP-147)

Aggressive background process killers implemented by customized Android vendor skins (MIUI/HyperOS, One UI, ColorOS, FuntouchOS, OxygenOS, EMUI) will terminate voice foreground services, drop UDP multicast mesh packets, or throttle GPS location updates unless explicit device-level exceptions are configured.

This runbook documents the required operating system configurations and in-app mitigation mechanisms used by RiderVoice.

---

## 1. System-Level Architecture Mitigations

RiderVoice implements the following platform protections in code:
1. **Ongoing Foreground Service (`VoiceForegroundService` & `MeshForegroundService`)**:
   - `startForeground` with non-dismissible ongoing notifications.
   - Declared foreground service types: `microphone` for active voice and audio communication.
2. **Hardware Power Lock Caps**:
   - Partial wake lock (`PARTIAL_WAKE_LOCK`) with a hard 4-hour safety timeout to prevent permanent battery drain while protecting communication during active rides.
3. **Wi-Fi & Multicast Radio Keep-Alive**:
   - `WifiManager.MulticastLock` held during mesh discovery/active transport to prevent the Wi-Fi chip from dropping UDP broadcast packets on port 45454.
   - Automatic socket rebinding upon Wi-Fi network change.
4. **Adaptive GPS Dead-Banding**:
   - Movement threshold (`MIN_DISTANCE_METERS = 2f`) and speed-based intervals (3s at >60 km/h, 30s when stationary) preventing GPS chip thermal overload.

---

## 2. Vendor-Specific Configuration Steps

### A. Xiaomi / Redmi / Poco (MIUI & HyperOS)
1. **Battery Saver**:
   - Open **Settings** -> **Apps** -> **Manage Apps** -> **RiderVoice**.
   - Tap **Battery saver** -> Select **No restrictions** (prevents MIUI from killing background audio threads).
2. **Autostart**:
   - In the same app settings page, toggle **Autostart** to **ON**.
3. **Lock in Recent Apps**:
   - Open App Switcher / Recent Apps.
   - Long-press the RiderVoice card and tap the **Padlock** icon to lock it in memory.

### B. Samsung (One UI)
1. **Never Sleeping Apps**:
   - Open **Settings** -> **Battery and device care** -> **Battery** -> **Background usage limits**.
   - Tap **Never sleeping apps** -> Tap **+** and add **RiderVoice**.
2. **Disable Put Unused Apps to Sleep**:
   - Under **Background usage limits**, ensure RiderVoice is excluded from adaptive sleeping policies.
3. **Battery Optimization**:
   - **Settings** -> **Apps** -> **RiderVoice** -> **Battery** -> Select **Unrestricted**.

### C. Oppo / Realme (ColorOS & Realme UI)
1. **App Battery Management**:
   - **Settings** -> **Battery** -> **More battery settings** -> **App battery management** -> **RiderVoice**.
   - Enable **Allow background activity** and **Allow auto-launch**.
2. **Lock in Recents**:
   - Open Recent Apps -> Tap the three dots (or swipe down) on RiderVoice -> Select **Lock**.

### D. Vivo / iQOO (Funtouch OS & OriginOS)
1. **High Background Power Consumption**:
   - Open **Settings** -> **Battery** -> **Background power consumption management**.
   - Locate **RiderVoice** -> Select **Allow high background power consumption**.
2. **Autostart**:
   - **Settings** -> **Apps & Permissions** -> **Permission management** -> **Autostart** -> Enable for **RiderVoice**.

### E. OnePlus (OxygenOS)
1. **Battery Optimization**:
   - Open **Settings** -> **Battery** -> **Advanced settings** -> **Optimize battery use**.
   - Find **RiderVoice** and choose **Don't optimize**.
2. **Lock in Recents**:
   - In Recents view, tap the lock icon on the RiderVoice preview.

### F. Huawei / Honor (EMUI & MagicOS)
1. **App Launch Control**:
   - Open **Settings** -> **Battery** -> **App launch**.
   - Toggle **RiderVoice** from *Manage automatically* to **Manage manually**.
   - Enable all three toggles:
     - **Auto-launch**
     - **Secondary launch**
     - **Run in background**

---

## 3. Standard Android Battery Optimization Intent

To guide users directly to battery optimization settings without manual menu navigation, RiderVoice triggers:

```kotlin
val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
    data = Uri.parse("package:${context.packageName}")
}
context.startActivity(intent)
```

This presents the standard system dialog: *"Let RiderVoice always run in the background? RiderVoice will be able to run in the background, and its battery use will not be restricted."*

