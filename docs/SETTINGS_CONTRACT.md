# RiderVoice Settings Contract

This document provides the authoritative contract for all persistent settings in RiderVoice, specifying default values, valid ranges, consumers, application timing, persistence rules, and test coverage.

## Authoritative Settings Matrix

| Setting Name | Key | Default | Allowed Values | Runtime Consumer | Apply Timing | Dependencies | Reset on Logout | Verification Test |
|---|---|---|---|---|---|---|---|---|
| **App Language** | `app_language` | `"system"` | `"system"`, `"en"`, `"hi"`, `"mr"` | `LocaleHelper`, `MainActivity`, `LocalContext` | **Live** (via `CompositionLocalProvider` & Activity recreation) | Strings XML per locale | **No** (Preserved across logout & force-stop) | `StringResourceParityTest`, `SecurePreferencesTest` |
| **Dark Theme** | `dark_theme_enabled` | `false` | `true`, `false` | `ThemeState`, `RiderVoiceTheme` | **Live** (via Compose state) | None | No | `ThemeStateTest` |
| **VOX Sensitivity** | `settings_vox_sensitivity` | `"Medium"` | `"Low"`, `"Medium"`, `"High"` | `VoxEngine.setSensitivity` (0.2f, 0.5f, 0.8f) | **Live** (via `AudioSettingsApplier`) | `RECORD_AUDIO` permission | Yes | `AudioSettingsApplierTest` |
| **Voice Activation (VOX)** | `settings_open_mic` | `true` | `true`, `false` | `VoxEngine.setVoxEnabled` | **Live** (via `AudioSettingsApplier`) | `RECORD_AUDIO` permission | Yes | `AudioSettingsApplierTest` |
| **Noise Cancellation** | `settings_noise_cancellation` | `true` | `true`, `false` | `LiveKitManager.noiseSuppressionEnabled` | **Live** (via `AudioSettingsApplier`) | LiveKit Audio Module | Yes | `AudioSettingsApplierTest` |
| **Hardware PTT (Helmet Button)** | `settings_hardware_ptt` | `true` | `true`, `false` | `VoxEngine.setHardwarePttEnabled`, `HardwarePTTManager` | **Live** (via `AudioSettingsApplier`) | Bluetooth headset button / key events | Yes | `AudioSettingsApplierTest` |
| **Hybrid VOX + PTT Mode** | `settings_hybrid_vox` | `true` | `true`, `false` | `VoxEngine.setHybridVoxMode` | **Live** (via `AudioSettingsApplier`) | None | Yes | `AudioSettingsApplierTest` |
| **Network Diagnostics (Rider)** | `network_diagnostics_enabled` | `false` | `true`, `false` | `NetworkDiagnosticsCollector` | **Live** | Consent disclosure | Yes | `DiagnosticUploadManagerTest` |
| **Share Diagnostics When Host Enables** | `share_diagnostics_host_enabled` | `true` | `true`, `false` | `NetworkDiagnosticsCollector` | **On ride join/start** | Host diagnostics enabled | Yes | `DiagnosticsCollectorTest` |
| **Diagnostics Consent Shown** | `diagnostics_consent_shown` | `false` | `true`, `false` | `RoomViewModel` consent dialog | On dialog dismissal | None | Yes | `DiagnosticsDialogTest` |
| **Active Ride Room** | `active_ride_room` | `null` | String (Room ID) | `ActiveRideRepository` | On start/join | Server room record | Cleared on ride end/leave/isolation | `ActiveRideRepositoryTest` |
| **Active Ride LK Room** | `active_ride_lk_room` | `null` | String (`rv_${roomId}`) | `LiveKitManager`, `JwtRoomClaimDecoder` | On connection verification | P0-A canonical room format | Cleared on ride end/leave/isolation | `RoomIdentityVerificationTest` |
| **Device Configured** | `device_configured` | `false` | `true`, `false` | `AuthViewModel`, `NavGraph` | App startup | None | No | `DeviceSetupTest` |
| **Emergency Country** | `emergency_country` | `null` (auto-detect device locale region) | ISO-3166 alpha-2 country (e.g. `"IN"`, `"US"`, `"GB"`) or empty for auto | `EmergencyNumbers.resolve`, `SosScreen`, `EmergencyAlertActivity` | **Live** on SOS / Emergency resolution | None (TelephonyManager strictly omitted for Play Protect safety) | **No** (Preserved across logout) | `EmergencyNumbersTest` |

## Removed Decorative Controls

Per the P4-C audit:
- `settings_auto_hud` (High-Contrast Mode) and `settings_glove_mode` (Glove Mode) had zero runtime consumers in the codebase and were purely decorative toggles. They have been cleanly removed from the UI. Touch targets throughout Room and Lobby action bars are unconditionally gloved-hand friendly (PTT 72dp > 56dp minimum, SOS 52dp > 48dp minimum).
- Hardcoded `CH-01 / Default` in `ProfileDrawer` has been removed.
