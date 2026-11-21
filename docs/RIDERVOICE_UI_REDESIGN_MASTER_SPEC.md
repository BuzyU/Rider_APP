# RiderVoice ("RiderLink") — Full UI/UX Redesign Master Specification & Implementation Blueprint

**Version:** 3.0 (Final Authoritative Spec — Roadside Paper Edition)  
**Repository:** `BuzyU/Rider_APP` (branch `main`, base commit `5961df13`)  
**Scope:** Android Jetpack Compose UI/UX visual redesign & navigation shell ONLY. Zero functional, ViewModel, repository, protocol, audio, network, or route-name changes.

---

## Table of Contents

- [(A) Repository & Platform Facts](#a-repository--platform-facts)
- [(B) Functionality Map & Data Source Map](#b-functionality-map--data-source-map)
- [(C) Palette Evaluation & Design Decisions Table](#c-palette-evaluation--design-decisions-table)
- [(D) Element Necessity Table (Every Screen)](#d-element-necessity-table-every-screen)
- [(E) Complete Design System ("Roadside Paper")](#e-complete-design-system-roadside-paper)
- [(F) Navigation Architecture & Orphan Audit](#f-navigation-architecture--orphan-audit)
- [(G) Per-Screen Design Briefs, Zone Budgets & Composable Parity Tables](#g-per-screen-design-briefs-zone-budgets--composable-parity-tables)
- [(H) Interactive HTML Design Prototype v3](#h-interactive-html-design-prototype-v3)
- [(I) Splash Video Asset Brief](#i-splash-video-asset-brief)
- [(J) Compatibility, Contrast Audits, Frozen Paths & CI Guard Risk Checks](#j-compatibility-contrast-audits-frozen-paths--ci-guard-risk-checks)
- [(K) Complete File-by-File Implementation Blueprint](#k-complete-file-by-file-implementation-blueprint)
- [(L) Verification Matrix & Self-Check](#l-verification-matrix--self-check)

---

## (A) Repository & Platform Facts

### A.1 Repository Snapshot
| Property | Verified Value |
|---|---|
| Base Commit | `5961df1` (`fix(wp173-175): account deletion, SOS/emergency hardening, layout tests, and allowlist`) |
| Mobile Root | `mobile-app/app/src/main/java/com/ridervoice/` |
| Build Script | `mobile-app/app/build.gradle` (Groovy DSL, **not** `.kts`) |
| Strings Locales | `res/values/strings.xml`, `res/values-hi/strings.xml`, `res/values-mr/strings.xml` (412 lines each, exact key parity enforced on `mesh_*` by `StringsParityTest`) |
| Local Font Folder | **None** (`res/font/` does not exist). Fonts load via `androidx.compose.ui:ui-text-google-fonts:1.5.4` using GMS font provider (`Barlow Condensed`, `Space Mono`, `Inter`) with safe platform fallbacks offline. |
| Release Mesh Flags | `OFFLINE_MESH_BUILD_ENABLED = true`, `PER_ROOM_OFFLINE_CONFIG_ENABLED = true` in both `debug` and `release` (enforced by Gradle task `verifyReleaseOfflineMeshFlags`). |

### A.2 Android & Compose Platform Constraints (Verified from `mobile-app/app/build.gradle`)
| Constraint | Value | Architectural Implication |
|---|---|---|
| `compileSdk` / `targetSdk` | `34` | Edge-to-edge enabled in `MainActivity` via `enableEdgeToEdge()`. Must apply `statusBarsPadding()` / `navigationBarsPadding()` / `imePadding()`. |
| `minSdk` | `26` | Standard Material3 + Canvas APIs safe. |
| Kotlin / JVM Toolchain | Java `17`, Compose Compiler `1.5.14` | No Kotlin 2.x syntax. |
| `androidx.compose.ui:ui` | `1.5.4` | Stable `mutableIntStateOf`, `mutableFloatStateOf`, `mutableLongStateOf`. |
| `androidx.compose.material3:material3` | **`1.0.1` (OLD)** | **CRITICAL:** No `ModalBottomSheet`, no `HorizontalDivider` (use `Divider`), no `OutlinedTextFieldDefaults` (use `TextFieldDefaults.outlinedTextFieldColors(...)`), no `TopAppBarDefaults.topAppBarColors` (use `TopAppBarDefaults.smallTopAppBarColors(...)`), no `SegmentedButton`. Bottom sheets MUST use `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` anchored to `Alignment.BottomCenter` (matching existing `AddRidersBottomSheet.kt`). |
| `material-icons-extended` | `1.3.1` | Icons from Material 1.3.1 available (`Icons.Default.*`). Avoid icons added in 1.6+. |
| `navigation-compose` | `2.7.6` | `currentBackStackEntryAsState()`, `launchSingleTop`, `restoreState`, `saveState` supported. |
| `hilt-navigation-compose` | `1.1.0` | Keep all `hiltViewModel()` calls unchanged. |
| `coil-compose` | `2.5.0` | Used in `AccountScreen` (`SubcomposeAsyncImage`). |
| `mapbox-maps` | `11.2.0` | Used in `RoutePlannerScreen` with offline Canvas fallback. |
| Lottie / Animations | **None** | Zero new libraries allowed. Use Compose `animateColorAsState`, `animateFloatAsState`, `AnimatedVisibility`, `rememberInfiniteTransition`. |
| Lint | `abortOnError true`, `lint-baseline.xml` | Must introduce zero new lint errors. |
| Unit Test Stack | JUnit 4.13.2, Robolectric 4.11.1, Compose UI Test `1.5.4`, MockK 1.13.8 | Tests run on JVM via `./gradlew :app:testDebugUnitTest`. |

### A.3 CI Guards & Frozen Contract (Verified from `.github/`)
1. **`.github/scripts/mesh-commit-guard.sh`** blocks modifications to:
   - `mobile-app/app/src/main/java/com/ridervoice/network/{LiveKitManager,ConnectionState,NetworkResilienceManager}.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/state/`
   - `mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/audio/`
   - `mobile-app/app/src/main/java/com/ridervoice/diagnostics/`
   - `mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt` (**FROZEN — NOT allowlisted!**)
   - `mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/` (**EXCEPT** the 3 allowlisted files below!)
   - `backend/`, `web-app/`, `livekit-server/`
   - `docs/plan/OFFLINE_MESH_PLAN.md` (pinned to commit `4c6aa85`)
2. **`.github/frozen-allowlist`** permits UI edits inside `ui/screens/room/` ONLY for:
   - `mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/RideTopBar.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/TalkControls.kt`
   - `mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/RiderList.kt`
   - **STRICTLY DO NOT TOUCH:** `RoomScreen.kt`, `AudioRouteSheet.kt`, `AudioVuMeter.kt`, `DiagnosticsDialog.kt`, `LeaveRideDialog.kt`, `SosAlertBanner.kt`. (They automatically inherit the new theme via `Color.kt`, `Type.kt`, `Dimens.kt`, and `Theme.kt`!)
3. **`.github/scripts/mesh-invariants.sh`** checks 18 grep invariants + `mesh-commit-guard.sh` + test census floor (`704` in `.github/test-census-floor`; current suite has ~793 tests).
4. **`StringsParityTest.kt`** enforces:
   - Exact 1:1 parity of all `mesh_*` string keys across `values`, `values-hi`, and `values-mr`.
   - **Zero `Color(0x...)` hex literals in `com/ridervoice/mesh/ui/*.kt` outside `MeshColors.kt`**.
5. **`RoomLobbyLayoutTest.kt`** enforces across `320dp`, `360dp`, `411dp`, `640x320` landscape, `fontScale = 2.0f`, RTL, and `1/6/30` riders:
   - Exact `testTag` preservation: `ride_topbar_left`, `ride_topbar_center`, `ride_topbar_right`, `ride_topbar_audio_badge`, `ride_topbar_leave_button`, `talk_controls_mic`, `talk_controls_mic_label`, `talk_controls_deafen`, `talk_controls_deafen_label`, `talk_controls_ptt`, `talk_controls_sos`, `talk_controls_sos_label`, `rider_list_lazy`, `participant_card_<id>`, `participant_name_<id>`, `participant_distance_<id>`, `participant_speed_<id>`, `rider_avatar_inner`, `rider_row_name`, `rider_row_subtitle`, `lobby_start_button`, `mesh_peer_card_<id>`, `mesh_peer_name_<id>`, `mesh_peer_speaking_<id>`, `mesh_bottom_audio_route`, `mesh_bottom_vox`, `mesh_bottom_ptt`, `mesh_bottom_mic`, `mesh_bottom_deafen`, `mesh_bottom_leave`.
   - `>= 48.dp` clickable bounds on every interactive control.
   - Zero bounding-box overlap between siblings.
   - **Zero pixel shift** (`<= 0.25dp`) on sibling bounds when `activeSpeaker`, `isPttPressed`, `isVoxOpen`, `isMicEnabled`, or `speaking` toggles.
   - Zero single-line text clip (`didOverflowWidth == false`, `didOverflowHeight == false`, `lineCount == 1`) on labels and telemetry.

---

## (B) Functionality Map & Data Source Map

Every route in `Routes.kt` (22 routes) plus 2 standalone lock-screen Activities (`EmergencyAlertActivity`, `RideInviteActivity`):

| # | Route Constant / Activity | Screen Composable | ViewModel / Data Sources | Read Fields | User Actions / Callbacks |
|---|---|---|---|---|---|
| 1 | `Routes.SPLASH` | `SplashScreen` | `FirebaseAuth.currentUser` (in NavGraph) | `startRoute` | Auto-advances `onSplashFinished()` |
| 2 | `Routes.LOGIN` | `LoginScreen` | `AuthViewModel`, `CredentialManager`, `DeepLinkHolder` | `isLoading`, `loginSuccess`, `errorMessage`, `resetEmailSent` | Email/password sign-in, Forgot password reset, Google One-Tap, Phone OTP callback, Guest sign-in, Register nav |
| 3 | `Routes.REGISTER` | `RegisterScreen` | `AuthViewModel` | `isLoading`, `loginSuccess`, `errorMessage` | Create account (`handle`, `email`, `password`), Back to login |
| 4 | `Routes.HOME` | `HomeScreen` | `HomeViewModel`, `AuthViewModel`, `MeshSettingsViewModel`, `OEMBatteryWarning` | `hasActiveRide`, `activeRideName`, `activeRideSubtitle`, `deviceName`, `deviceStatus`, `isDeviceConnected`, `isDeviceConfigured`, `networkStatus`, `isNetworkStrong` | Host ride, Join ride, Invites inbox, SOS, Squad, Route planner, Ride history, Settings, Account, Device setup, Resume active ride, Sign out (with confirmation) |
| 5 | `Routes.HOST_SETUP` | `HostSetupScreen` | `HostSetupViewModel` | `isLoading`, `error`, `createState` (`Idle`/`Submitting`/`Succeeded`) | Create ride (`convoyName`, `origin`, `destination`, `meetupPoint`, `diagnosticsEnabled`, `clientRequestId`), Learn more dialog, Import route, Discard confirmation |
| 6 | `Routes.INVITE_FRIENDS` | `InviteFriendsScreen` | `InviteFriendsViewModel` | `friends`, `isLoading`, `error` | Add friend dialog, Invite all, Search filter, Per-rider invite, Continue to lobby |
| 7 | `Routes.LOBBY` | `LobbyScreen` | `LobbyViewModel`, `HomeViewModel`, `StartRideGate` | `lobbyStatus` (`roomId`, `convoyName`, `joinCode`, `isHost`, `isRideStarted`, `invites`, `offlineMeshEnabled`, `offlineBackupCode`), `loadState`, `activeRideState` (`meshConfig`), `error`, `terminal`, `shareLink`, `isGeneratingLink`, `linkError` | Copy/share ride code, Toggle offline mesh, Retry offline step, Opt out/in offline, Remove rider, Cancel invite, Add riders bottom sheet (Squad + 24h Share Link), Start ride (gated by `StartRideGate`, solo confirm), Leave lobby confirm |
| 8 | `Routes.INVITES_INBOX` | `InvitesInboxScreen` | `InvitesInboxViewModel` | `invites`, `isLoading`, `error` | Refresh, Accept invite -> Lobby, Decline invite (with confirm), Empty-state Host ride |
| 9 | `Routes.JOIN_ROOM` | `JoinRoomScreen` | `JoinRoomViewModel`, `FirebaseAuth` | `isConnecting`, `error` | Enter callsign + ride code/link -> `smartJoin` -> Lobby |
| 10 | `Routes.JOIN_VIA_TOKEN` | `JoinRoomScreen(initialToken)` | `JoinRoomViewModel`, `DeepLinkHolder` | `isConnecting`, `error` | Auto `joinViaToken`, Retry, Fallback to manual code |
| 11 | `Routes.DEVICE_SETUP` | `DeviceSetupScreen` | `DeviceSetupViewModel` | `routerState`, `activeDevice`, `requestedDevice`, `routedInput`, `routedOutput` | Route override (`BLUETOOTH`/`SPEAKER`/`WIRED`), Rescan devices, Open Android Bluetooth settings, Finish (`DONE` / `CONTINUE TO LOBBY` / `READY TO JOIN CONVOY`) |
| 12 | `Routes.ACTIVE_RIDE_HUD` | `OfflineAwareRoomHost` -> `RoomScreen` + `MeshActiveOverlay` | `RoomViewModel`, `HomeViewModel`, `OfflineAwareRoomViewModel`, `OfflineMeshGate` | `connectionState`, `participants`, `activeSpeaker`, `remoteLocations`, `currentLocation`, `isMicEnabled`, `isVoxOpen`, `activeAudioDevice`, `routerState`, `requestedAudioDevice`, `routedInput`, `routedOutput`, `audioStatusLine`, `error`, `inRideSosAlert`, `deafened`, `convoyEnded`, `activeRide`, `latestSelfMetric`, `diagSampleCount`, `isCollectingDiagnostics`, `autoSwitchState`, `groupStatus`, `meshParticipants`, `meshDiagSnapshot`, `offlineAvailability`, `micState` | PTT press-and-hold, Mic toggle, Deafen toggle, SOS, Audio route sheet, Diagnostics dialog, Diagnostics consent dialog, Leave/Transfer/End dialog, Rescan audio, Pair backup chip, Mesh fallback overlay controls |
| 13 | `Routes.SOS` | `SosScreen` | `SosViewModel`, `EmergencyNumbers` | `state` (`Idle`/`Sending`/`Sent`/`Failed`/`NotSent`), local `countdown` (10s) | Auto-dispatch after 10s (MUST NOT ALTER), Send SOS now / Retry, Dial local emergency number, Cancel emergency ("I'm okay") |
| 14 | `Routes.POST_RIDE_SUMMARY` | `PostRideSummaryScreen` | `PostRideSummaryViewModel` | `summary` (`durationMinutes`, `distanceKm`, `topSpeedKmh`), `convoyName`, `testNumber`, `sampleCount`, `latestMetric` | Title/notes input, Privacy chip (`SQUAD`/`PRIVATE`), Save to logbook, Discard (with confirm) |
| 15 | `Routes.SQUAD` | `SquadScreen` | `SquadViewModel`, `FirebaseAuth` | `uiState` (`isLoading`, `friends`, `invites`, `errorMessage`) | Refresh, Add friend dialog, Filter by handle, Quick-add from search empty state, Accept incoming invite, Remove friend (with confirm) |
| 16 | `Routes.RIDE_STATS` | `RideStatsScreen` | `RideStatsViewModel` | `uiState` (`totalDistance`, `totalTime`, `avgSpeed`, `topSpeed`, `speedDataPoints`, `elevationDataPoints`) | Share ride summary, Tab switch (`OVERVIEW`/`SPEED`/`ELEVATION`), Replay session -> `RIDE_REPLAY` |
| 17 | `Routes.RIDE_REPLAY` | `RideReplayScreen` | `RideStatsViewModel` | `replayData` (`sessionId`, `waypoints`, `events`) | Scrub timeline slider, Play/Pause, Rewind/Forward, Cycle playback speed (`1x`/`2x`/`4x`), Back |
| 18 | `Routes.ROUTE_PLANNER` | `RoutePlannerScreen` | `RoutePlannerViewModel`, `SharedPreferences` | `uiState` (`origin`, `destination`, `routeName`, `distanceKm`, `duration`, `elevationGain`, `nearbyRiders`) | Origin/Destination inputs, GPS fill, Swap, Route preference chips, Radar vs Mapbox toggle, Mapbox token dialog, Export GPX toast, Clear route, Nearby rider tap, Save route |
| 19 | `Routes.SETTINGS` | `SettingsScreen` | `SettingsViewModel`, `AuthViewModel`, `UpdateViewModel`, `MeshSettingsViewModel` | `settingsState` (`darkTheme`, `appLanguage`, `voxSensitivity`, `noiseCancellation`, `hardwarePtt`, `hybridVox`, `openMic`, `shareDiagnosticsWhenHostEnabled`, `emergencyCountry`), `updateState`, `showDiagnosticDisclosure` | Profile nav, Dark Mode toggle, Language picker (System/EN/HI/MR), Headset setup nav, VOX sensitivity picker, Noise cancellation, Hardware PTT, Hybrid VOX+PTT, Open Mic/VOX, Offline mesh info, Diagnostics consent toggle + disclosure dialog, Emergency country picker, Check for updates (`UpdateDialog`), Sign out (with confirm) |
| 20 | `Routes.ACCOUNT` | `AccountScreen` | `AccountViewModel`, `UpdateViewModel` | `uiState` (`isLoading`, `isSaving`, `errorMessage`, `successMessage`, `displayName`, `handle`, `email`, `phone`, `bikeModel`, `bio`, `photoUrl`, `authProvider`, `initialLetter`) | Edit profile dialog (`updateProfile`), Navigate to settings, Check for updates (`UpdateDialog`) |
| 21 | `Routes.OFFLINE_MESH_LOBBY` | `OfflineMeshLobbyScreen` | `OfflineMeshViewModel`, `UdpMeshLink`, `MeshSelfTestMapper` | `backupSession`, `isDerivingKey`, `sessionError`, `audioFailure`, `diagSnapshot` | Clear backup, Backup mode toggle, Generate 16-char pairing code, Copy code, Enter room / Pair backup, Join existing code |
| 22 | `Routes.OFFLINE_MESH_HUD` | `OfflineRoomScreen` | `OfflineMeshViewModel`, `OfflineMeshVoiceTransport` | `transportState`, `peers`, `activeSpeakers`, `micState`, `diagSnapshot`, `micToggleOn`, `isVoxEnabled`, `isPttActive`, `activeRoute`, `deafened` | Cycle audio route, VOX toggle, PTT press-and-hold, Mic toggle, Deafen toggle, Mesh diagnostics dialog, Leave |
| 23 | `EmergencyAlertActivity` | `EmergencyAlertContent` | `ApiService`, `LocationService`, `Vibrator`, `EmergencyNumbers` | `alertType`, `roomName`, `emergencyNumber`, 15s countdown | Auto-dispatch after 15s (MUST NOT ALTER), Send immediately, Call emergency services, Cancel emergency |
| 24 | `RideInviteActivity` | Inline Compose in `onCreate` | `ApiService`, `ActiveRideRepository` | `inviter`, `roomName`, `inviteId` | Accept & enter Lobby, Decline & finish |

---

## (C) Palette Evaluation & Design Decisions Table

### C.1 Blue-Free 3-Candidate Palette Evaluation (Measured WCAG 2.1 Contrast Ratios)

All blue, cyan, indigo, violet, teal, neon, and gradient accents are strictly banned. Every palette follows the **60% Paper / 30% Ink / ≤10% Pop** rule plus one desaturated **Deep Brick** reserved exclusively for SOS, Leave/End, Delete, and real errors.

#### Candidate A — Aged Parchment + Dusty Umber + Deep Forest
- **Paper:** `#F2E3C8` (raised `#FBF4E6`, sunken `#E5D2B0`)
- **Ink:** `#411E04` (secondary `#6E4A2E`, hairline `#C6AE8A`)
- **Pop:** Deep Forest `#1F6B4A` (onPop `#FBF4E6`)
- **Brick:** `#8C2518`
- **Measured Contrast:**
  - Ink on Paper: **11.75:1** (AAA)
  - InkSecondary on Paper: **6.19:1** (AA)
  - Pop on Paper: **5.09:1** (AA)
  - PaperRaised on Pop: **5.88:1** (AA)
- **Pop-Budget Mock Verdict (Dashboard / Ride / Lobby):** High legibility, calm, dignified, natural outdoor contrast. However, `#F2E3C8` reads noticeably sepia/yellowed under harsh midday glare, reducing perceived screen luminance on dim phone panels, and Deep Forest can feel slightly close to "safe/go" status semantics rather than an unmistakable primary action hue for motorcycling gloves.

#### Candidate B — Warm Cream + Espresso Ink + Sun Yellow (Fill-Only Pop)
- **Paper:** `#F6F1E7` (raised `#FFFCF7`, sunken `#EAE2D3`)
- **Ink:** `#2E1E12` (secondary `#614E3E`, hairline `#C7BCAE`)
- **Pop:** Sun Yellow `#EAB308` / `#FEDF17` (fill-only; Ink `#2E1E12` on Pop)
- **Brick:** `#8C2518`
- **Measured Contrast:**
  - Ink on Paper: **14.23:1** (AAA)
  - InkSecondary on Paper: **6.99:1** (AA+)
  - Pop `#EAB308` on Paper: **1.70:1** (FAILS AA text & 3:1 non-text — strictly fill-only!)
  - Pop `#FEDF17` on Paper: **1.18:1** (FAILS — invisible as border/icon/dot on light paper)
  - Ink `#2E1E12` on `#EAB308`: **8.35:1** (AAA); Ink on `#FEDF17`: **12.04:1** (AAA)
- **Pop-Budget Mock Verdict (Dashboard / Ride / Lobby):** Striking high-visibility safety-vest look for filled buttons (PTT / Start Ride), **but** because yellow fails 3:1 on cream paper, it cannot serve as active tab indicators, speaking rings, focus outlines, or progress tracks without switching hue or adding thick dark ink strokes everywhere — creating visual clutter and risking neon glare outdoors.

#### Candidate C (CHOSEN DEFAULT) — "Roadside Paper": Warm Cream + Warm Charcoal + Burnt Terracotta
- **Paper (Light Base 1):** `#F5F0E8` (raised `#FDFAF5`, sunken `#EAE4D8`)
- **Ink (Light Base 2):** `#282722` (secondary `#5A5650`, hairline `#C2B8A8`, disabled `#857F76`)
- **Pop (Single Action Hue):** Burnt Terracotta `#A84424` (`PopContainer` `#F8E5DD`, `PaperOnPop` `#FDFAF5`)
- **Brick (Emergency / Destructive Only):** Deep Brick `#8C2518` (`BrickContainer` `#F5DEDB`, `PaperOnBrick` `#FDFAF5`)
- **Measured Contrast (Light Theme — Default):**
  - `Ink (#282722)` on `Paper (#F5F0E8)`: **13.19:1** (AAA)
  - `Ink (#282722)` on `PaperRaised (#FDFAF5)`: **14.37:1** (AAA)
  - `Ink (#282722)` on `PaperSunken (#EAE4D8)`: **11.82:1** (AAA)
  - `InkSecondary (#5A5650)` on `Paper (#F5F0E8)`: **6.42:1** (AA+)
  - `InkSecondary (#5A5650)` on `PaperRaised (#FDFAF5)`: **7.00:1** (AAA)
  - `InkSecondary (#5A5650)` on `PaperSunken (#EAE4D8)`: **5.76:1** (AA)
  - `Pop (#A84424)` on `Paper (#F5F0E8)`: **5.26:1** (AA — safe for text, icons, dots, borders, AND fills!)
  - `Pop (#A84424)` on `PaperRaised (#FDFAF5)`: **5.73:1** (AA)
  - `Pop (#A84424)` on `PopContainer (#F8E5DD)`: **4.90:1** (AA)
  - `PaperOnPop (#FDFAF5)` on `Pop (#A84424)`: **5.73:1** (AA)
  - `White (#FFFFFF)` on `Pop (#A84424)`: **5.97:1** (AA)
  - `Brick (#8C2518)` on `Paper (#F5F0E8)`: **7.74:1** (AAA)
  - `Brick (#8C2518)` on `PaperRaised (#FDFAF5)`: **8.43:1** (AAA)
  - `Brick (#8C2518)` on `BrickContainer (#F5DEDB)`: **6.83:1** (AA+)
  - `PaperOnPop (#FDFAF5)` on `Brick (#8C2518)`: **8.43:1** (AAA)
- **Measured Contrast (Warm Charcoal Dark Theme — preserved for existing Settings toggle, zero blue/cyan/violet/neon):**
  - `PaperDk` `#1A1714`, `PaperRaisedDk` `#24201C`, `PaperSunkenDk` `#2E2924`, `HairlineDk` `#463F37`
  - `InkDk (#F5F0E8)` on `PaperDk (#1A1714)`: **15.73:1** (AAA)
  - `InkDk (#F5F0E8)` on `PaperRaisedDk (#24201C)`: **14.26:1** (AAA)
  - `InkSecDk (#B5AEA3)` on `PaperDk (#1A1714)`: **8.12:1** (AAA)
  - `InkSecDk (#B5AEA3)` on `PaperRaisedDk (#24201C)`: **7.35:1** (AAA)
  - `PopDk (#D9724E)` on `PaperDk (#1A1714)`: **5.50:1** (AA); `PaperDk` on `PopDk`: **5.50:1** (AA)
  - `BrickDk (#E56B55)` on `PaperDk (#1A1714)`: **5.58:1** (AA); `PaperDk` on `BrickDk`: **5.58:1** (AA)
- **Verdict:** **Candidate C ("Roadside Paper") wins decisively.** `#F5F0E8` stays bright and glare-resistant in direct sunlight (like a paper topo map on a tank bag) without harsh `#FFFFFF` eye-strain; `#282722` charcoal delivers 13.19:1–14.37:1 crisp readability; and Burnt Terracotta `#A84424` is the ONLY candidate that clears WCAG AA both as a filled button (`5.73:1` with warm white text) AND as an accent/icon/ring/tab indicator on paper (`5.26:1`), while sitting cleanly apart from Deep Brick `#8C2518` (`7.74:1`) in both hue and darkness. In grayscale, Pop sits at mid-dark value (`L*=40`) between Paper (`L*=95`) and Ink (`L*=16`), preserving hierarchy without relying on hue.

### C.2 Master Design Decisions Table

| # | Decision | Alternatives Considered | Rationale |
|---|---|---|---|
| D1 | **Candidate C ("Roadside Paper") palette** (`#F5F0E8` / `#282722` / `#A84424` + `#8C2518` Brick) | Candidate A (Parchment/Forest), Candidate B (Cream/Yellow) | Only blue-free palette clearing WCAG AA in both fill and text/icon roles while maximizing outdoor screen brightness. |
| D2 | **Zero blue/cyan/violet/neon in BOTH Light and Dark palettes** (including `MeshColors.kt`) | Light-only redesign leaving Dark & Mesh neon-blue | Existing `DarkPalette` and `MeshColors.kt` had navy `#0F172A`, cyan `#38BDF8`, indigo `#A5B4FC`, and neon green `#00E676`. Unifying both themes + Mesh UI eliminates visual whiplash when offline fallback activates mid-ride. |
| D3 | **Preserve existing Kotlin token names in `Color.kt`** (`GraphiteBase`, `DarkSlate`, `Gunmetal`, `NeonOrange`, `ElectricCyan`, `TechGreen`, `AlertRed`, etc.) while adding semantic aliases (`Paper`, `PaperRaised`, `PaperSunken`, `Ink`, `InkSecondary`, `InkHairline`, `Pop`, `PopContainer`, `PaperOnPop`, `Brick`, `BrickContainer`) | Rename/delete legacy token names | **Mandatory for Frozen Files:** `RoomScreen.kt`, `AudioRouteSheet.kt`, `AudioVuMeter.kt`, `DiagnosticsDialog.kt`, `LeaveRideDialog.kt`, and `SosAlertBanner.kt` are frozen by `mesh-commit-guard.sh` and import legacy token names directly. Keeping legacy getters backed by Roadside Paper values skins all frozen files automatically with zero guard violations. |
| D4 | **Status without extra hues** (`TechGreen` -> `Ink`, `WarningAmber` -> `Ink`/`Pop`, `ElectricCyan` -> `InkSecondary`, `NeonViolet` -> `InkSecondary`) | Keep green/amber/cyan semantic hues | Follows Anti-Slop Rule 6: online/joined/connected/warning/info use Icon + Text + Ink weight; zero rainbow badges; 100% colorblind-safe and grayscale-valid. |
| D5 | **Fix every `Color.Black` text-on-colored-button contrast bug** | Leave existing `Color.Black` on `NeonOrange`/`TechGreen` | With dark Charcoal `TechGreen` (`#282722`) and Terracotta `NeonOrange` (`#A84424`), `Color.Black` text drops to 1.1:1–3.1:1 (unreadable!). Replacing with `PaperOnPop` (`#FDFAF5` light / `#1A1714` dark) guarantees ≥5.5:1 everywhere. |
| D6 | **Replace `ModalNavigationDrawer` on Home with persistent 4-tab BottomNavShell + "More" bottom sheet** | Keep hidden side drawer; use 5 tabs | Gloves make edge-swipe drawers unreliable. 4 bottom tabs (`Home`, `Squad`, `History`, `More`) at `>= 48dp` targets give 1-tap reach for core tabs and surface Account, Settings, Audio Setup, Route Planner, Invites, and Sign Out cleanly via the More sheet with zero orphans. |
| D7 | **Host BottomNavShell at `NavGraph.kt` level** (visible ONLY on `Routes.HOME`, `Routes.SQUAD`, `Routes.RIDE_STATS`; hidden on auth/setup/lobby/ride/SOS/settings/account/mesh screens) | Put bottom bar inside `HomeScreen` only | Wrapping `NavHost` keeps tab state consistent across top-level tabs and guarantees full-bleed focus on active voice HUD, Lobby, SOS, and forms. |
| D8 | **Keep ALL `HomeScreen` parameters wired** (no signature change) | Remove `onAccountClick` / `onSettingsClick` / `onDeviceSetupClick` / `onLogoutSuccess` from `HomeScreen` | Enforces Parity Rule 1.3: `HomeScreen` retains every parameter. Its top-right profile avatar opens the clean "More / Profile" sheet (wiring `onAccountClick`, `onSettingsClick`, `onDeviceSetupClick`, `onLogoutSuccess` with confirmation), AND the bottom bar `More` tab accesses the same destinations. |
| D9 | **Remove military/tactical roleplay copy in functional UI** | Keep "DISENGAGE TRANSCEIVER", "CALIBRATE OPERATOR PROFILE", "COMMISSION ACCOUNT", "── X ──" | Replaces 18+ roleplay strings with calm, clear, human rider language ("Dashboard", "Ride Status", "Quick Actions", "Edit Profile", "Save", "Sign Out?", "Starting up..."). Keeps all safety-critical SOS copy and all existing `strings.xml` keys intact. |
| D10 | **Flat surfaces with crisp `1dp InkHairline` borders; zero elevation shadows, zero glow, zero gradients** | Material elevation shadows / tinted cards | Flat paper-on-paper with hairline borders stays legible in direct sunlight where soft drop-shadows wash out completely. |
| D11 | **12dp standard card/button radius (`RvRadius.md = 12.dp`)** | 16dp–24dp bubbly cards | Tighter, disciplined utilitarian geometry; matches tactile physical instrument panels. |
| D12 | **Glove-first touch targets: `48dp` min, `56dp` primary CTA, `76dp` PTT capsule** | Standard 40–48dp buttons | Motorcyclists wear thick armored gloves; 76dp PTT capsule + 56dp primary buttons minimize mis-taps while keeping `RoomLobbyLayoutTest` green at `320dp` width & `2.0x` font scale. |
| D13 | **Chrome font-scale cap (`1.3f`) on bottom nav & non-scrolling bars; scrollable content uncapped** | Uncapped everywhere (causes overlap at 2.0x) or capped globally (hurts accessibility) | Keeps fixed chrome from collapsing at 2.0x system font scale while letting rider lists, forms, and settings scale freely for low-vision users. |
| D14 | **Remove duplicate Leave icon (`ExitToApp`) from `LobbyScreen` TopAppBar actions** | Keep both `ArrowBack` and `ExitToApp` opening the exact same `showExitDialog` | Eliminates redundant top-bar crowding while preserving exact Leave confirmation semantics via `ArrowBack` and system back button. |
| D15 | **Leave `RoomScreen.kt` and 5 frozen room dialogs untouched; style active HUD via `Color.kt`, `RideTopBar.kt`, `TalkControls.kt`, `RiderList.kt`, `ParticipantCard.kt`** | Edit `RoomScreen.kt` directly | Strictly obeys `.github/scripts/mesh-commit-guard.sh` and `.github/frozen-allowlist` with zero CI violations. |
| D16 | **Preserve zero-shift invariants on `RideTopBar`, `TalkControls`, `ParticipantCard`, `RiderRow`, `MeshPeerCard`, `MeshBottomBar`** | Dynamic visibility (`if (isSpeaking) Badge()`) that shifts siblings | Fixed-width/fixed-height slots + `alpha` / color swaps guarantee `<= 0.25dp` layout shift on every speaking/PTT/VOX toggle, passing `RoomLobbyLayoutTest`. |
| D17 | **Preserve `SosScreen` 10s countdown & `EmergencyAlertActivity` 15s countdown + cancel logic byte-for-byte** | Alter timer or add extra steps | Safety-critical requirement (Rule 1.5); only color tokens & borders updated to Deep Brick `#8C2518`. |
| D18 | **Pop Budget: max 1 filled Pop element + max 2 small Pop accents per screen** | Multiple terracotta buttons/cards per screen | Forces instant visual focus onto the single primary action on every screen (Host/Resume on Home, Start Ride in Lobby, PTT active in Room, Save in Summary, Create in Host Setup). |
| D19 | **Material3 1.0.1 compatible bottom sheet (`Dialog` + bottom-aligned `Surface`)** | Upgrade Material3 dependency | Zero new/upgraded Gradle dependencies allowed; matches `AddRidersBottomSheet.kt` proven pattern. |
| D20 | **Keep `ThemeState.isDarkTheme` toggle functional in Settings** | Delete dark mode toggle | Preserves existing user preference and functionality freeze (Rule 1.2), while ensuring Light "Roadside Paper" is default (`false`) and Dark uses warm charcoal Roadside Paper Dark. |

---

## (D) Element Necessity Table (Every Screen)

| Screen | Element | Action | Data Source | Necessity & Rationale |
|---|---|---|---|---|
| **Splash** | Brand title "RIDERVOICE" + tagline | **Keep** | Static | Brand identity during Firebase auth check |
| **Splash** | "INITIALIZING RF HARDWARE & SENSORS…" | **Replace** -> "Starting up..." | Static | Remove jargon; calm status text |
| **Splash** | Top badge "HANDS-FREE GROUP VOICE FOR MOTORCYCLISTS" | **Keep (Restyle)** | Static | Concise product descriptor on `PaperSunken` |
| **Splash** | Progress bar | **Keep (Restyle)** | Static | Pop `#A84424` on `PaperSunken` track |
| **Login** | "VHF / CB VOICE NETWORK" badge | **Replace** -> "GROUP VOICE INTERCOM" | Static | Remove radio jargon; keep clean descriptor |
| **Login** | "COMMUNICATIONS CONSOLE LOGIN" / "Tune in..." | **Replace** -> "Sign In" / "Sign in to connect with your riding group." | Static | Clear functional copy |
| **Login** | Email & Password fields + Forgot Password + Error banner | **Keep (Restyle)** | `AuthViewModel` | Core auth flow |
| **Login** | "SIGN IN TO TRANSCEIVER" button | **Replace** -> "Sign In" (1 Pop fill) | `AuthViewModel.signInWithEmail` | Primary CTA |
| **Login** | "SECONDARY CHANNELS" divider | **Replace** -> "or continue with" | Static | Clean separator |
| **Login** | Google / Phone SMS / Guest Rider buttons + Register link | **Keep (Restyle)** | `CredentialManager` / `onPhoneOtpClick` / `signInAnonymously` / `onRegisterClick` | Full parity |
| **Login** | Forgot Password view ("RESET FREQUENCY KEY" / "DISPATCH RESET LINK") | **Replace** -> "Reset Password" / "Send Reset Link" | `AuthViewModel.sendPasswordReset` | Clear recovery flow |
| **Register** | TopBar "CALLSIGN COMMISSION" + badge "NEW TRANSCEIVER OPERATOR" + "COMMISSION ACCOUNT" | **Replace** -> "Create Account" / "New Rider" / "Create Account" | `AuthViewModel.createAccountWithEmail` | Remove jargon; preserve all 4 inputs & validations |
| **Home** | `ModalNavigationDrawer` wrapper | **Replace** -> BottomNavShell (`Home`/`Squad`/`History`/`More`) + Profile/More Sheet | `NavGraph` + `HomeScreen` callbacks | Eliminates glove-unfriendly edge swipe; zero orphans |
| **Home** | "TRANSCEIVER DASHBOARD" overline | **Replace** -> "Dashboard" (clean `InkSecondary` labelSmall, no decorative green dot) | Static | Calm minimalist header |
| **Home** | Profile Avatar button (top-right, 48dp) | **Keep (Restyle)** -> opens More/Profile sheet | `HomeScreen` callbacks | Preserves `contentDescription = "Open Profile Drawer"` & all 4 drawer callbacks |
| **Home** | OEM Battery warning banner | **Keep (Restyle)** | `OEMBatteryWarning.isAggressiveOEM()` | Prevents Android killing background voice |
| **Home** | Ride Status Card ("CHANNEL FREQUENCY MONITOR" -> "Ride Status") | **Keep (Restyle)** | `HomeViewModel.uiState` (`hasActiveRide`, `activeRideName`, `activeRideSubtitle`) | Pop border + LIVE chip when active; InkHairline when idle |
| **Home** | "TACTICAL CHANNELS & CONTROLS" header | **Replace** -> "Quick Actions" | Static | Clear section header |
| **Home** | 6 Action Rows (Host, Invites, Join Code, Route Planner, Squad, Ride History) | **Keep (Restyle)** — clean title case, flat `PaperRaised` cards, Host icon circle in `PopContainer` | `HomeScreen` callbacks | Zero orphans; 1-tap glove-friendly list |
| **Home** | Emergency SOS bar | **Keep (Restyle)** — `BrickContainer` fill + `Brick` border & text ("Emergency SOS") | `onSosClick` | Immediate safety access |
| **Home** | Logout dialog ("DISENGAGE TRANSCEIVER?") | **Replace** -> "Sign out?" (in More/Profile sheet) | `viewModel.clearActiveRide()` + `onLogoutSuccess` | Same confirmation semantics, clear copy |
| **HostSetup** | 4 inputs (Ride Name, Meet-up, Destination, Start) + More Options accordion + Diagnostics toggle + Learn More dialog + Import Route + Create Ride CTA | **Keep (Restyle)** | `HostSetupViewModel` | Already uses `RvScreen` & `PrimaryButton`; inherits Roadside Paper |
| **Lobby** | TopAppBar `ExitToApp` action icon | **Remove (Duplicate)** | `showExitDialog` | Duplicates `ArrowBack` which triggers identical `showExitDialog` |
| **Lobby** | TopAppBar title + subtitle status | **Keep (Restyle)** | `LobbyViewModel.lobbyStatus`, `loadState` | Clear waiting/retrying/failed status |
| **Lobby** | `CodeCard` (8-char ride code + Copy + Share) | **Keep (Restyle)** | `effectiveJoinCode` | Share button outlined/ink or Pop-safe; obeys Pop budget |
| **Lobby** | `OfflineConfigCard` (Host toggle, 5 setup steps, hotspot hint, opt-out/in) | **Keep (Restyle)** | `activeRide.meshConfig`, `LobbyViewModel` | Critical offline backup readiness |
| **Lobby** | Joined / Invited / Declined sections + `RiderRow` + `StatusChip` | **Keep (Restyle)** | `lobbyStatus.invites` | Icon + Ink weight status chips (no hue-only encoding) |
| **Lobby** | Bottom bar: `StartRideGate` progress OR `PrimaryButton` (`lobby_start_button`) + `SecondaryButton` (Invite Friends) / Waiting pill | **Keep (Restyle)** | `StartRideGate.evaluate`, `LobbyViewModel` | Never composes Start until gate opens (FX-03 invariant) |
| **Room (Active HUD)** | `RoomScreen.kt` + 5 frozen sub-components (`AudioRouteSheet`, `AudioVuMeter`, `DiagnosticsDialog`, `LeaveRideDialog`, `SosAlertBanner`) | **Keep Frozen (Skin via `Color.kt`)** | `RoomViewModel` | Strictly obeys `mesh-commit-guard.sh` |
| **Room (Active HUD)** | `RideTopBar` (Connection pill, DIAG badge, Ride name + speaker, Audio device badge, Leave Call button) | **Keep (Restyle)** — replace hardcoded `Color(0xFFFF3B30)` with `AlertRed`/`HazardContainer`, `ElectricCyan` speaking text with `Pop`, `NeonOrange` title with `Ink` | `RoomViewModel` | Preserves all `testTag`s, `>= 48dp` targets, zero sibling shift |
| **Room (Active HUD)** | `TalkControls` (Mic, Deafen, 76dp PTT Capsule, SOS) | **Keep (Restyle)** — Mic active uses `PopContainer`/`Pop`; Deafen active uses `BrickContainer`/`Brick`; PTT pressed uses `Pop` fill + `PaperOnPop` text; SOS uses `BrickContainer`/`Brick` | `RoomViewModel` | Glove-safe 76dp PTT, zero `DarkPalette` leakage in light mode, all `testTag`s intact |
| **Room (Active HUD)** | `RiderList` + `ParticipantCard` | **Keep (Restyle)** — Speaking ring/border/badge uses `Pop`; replace "TRANSMITTING LIVE" / "CONNECTED ON SQUAD NET" with "Speaking" / "Connected"; zero layout shift | `RoomViewModel` | Preserves `participant_card_*`, `participant_name_*`, `participant_distance_*`, `participant_speed_*` |
| **Offline Mesh HUD & Lobby** | `MeshColors.kt`, `MeshControls.kt`, `OfflineRoomScreen.kt`, `OfflineMeshLobbyScreen.kt`, `OfflineAwareRoomHost.kt` | **Keep (Restyle to Roadside Paper)** | `OfflineMeshViewModel`, `OfflineAwareRoomViewModel` | Eliminates jarring dark navy/cyan/indigo look; keeps zero `Color(0x...)` outside `MeshColors.kt` and all `mesh_*` testTags |
| **SOS Screen & EmergencyAlertActivity** | Countdown ring (10s / 15s), Call emergency button, Send SOS Now button, Cancel ("I'm Okay") button, Sent/Failed/NotSent cards | **Keep (Restyle tokens only)** | `SosViewModel`, `EmergencyNumbers` | Countdown & cancel logic 100% untouched; Deep Brick `#8C2518` border & fill |
| **Squad** | Header ("COMMUNICATIONS DIRECTORY" -> "Riding Group"), Search bar, Incoming Invites, Trusted Squad list, Add/Remove dialogs | **Keep (Restyle)** | `SquadViewModel` | Fix `Color.Black` on Join/Add buttons; remove jargon; 48dp header action targets |
| **RideStats & RideReplay** | Tabs (`Overview`/`Speed`/`Elevation`), Stat card, Velocity/Elevation Canvas chart, Share, Replay canvas + playback controls | **Keep (Restyle)** | `RideStatsViewModel` | Unified Roadside Paper surfaces; Ink & Pop chart strokes |
| **RoutePlanner** | Origin/Dest inputs, Swap, Routing chips, Mapbox / Offline Radar canvas, Nearby riders, Route summary & Save | **Keep (Restyle)** | `RoutePlannerViewModel` | Clean headers ("Route Planner"), warm radar colors without cyan |
| **PostRideSummary** | Ride Complete card, Stats, Diagnostics card, Title/Notes inputs, Privacy chips, Save / Discard buttons | **Keep (Restyle)** | `PostRideSummaryViewModel` | Primary CTA ("Save to Logbook") uses Pop `#A84424` + `PaperOnPop` (1 Pop fill); Discard uses Brick outline |
| **DeviceSetup** | Radar/Status icon, Mismatch alert, Mic/Speaker card, Route chips, Active BT card, Open BT Settings, Done/Continue CTA | **Keep (Restyle)** | `DeviceSetupViewModel` | Remove "SYNCHRONIZING SCO CARRIER" / "AUDIO HARNESS ACTIVE" jargon -> calm audio status |
| **InvitesInbox & InviteFriends** | Pending invite cards (Decline/Accept), Squad picker, Add friend dialog, `AddRidersBottomSheet` | **Keep (Restyle)** | `InvitesInboxViewModel`, `InviteFriendsViewModel` | Remove "DISPATCH REINFORCEMENTS" / "FREQUENCY LOCK" / "COMMISSION SQUAD RIDER" jargon |
| **Settings** | Section headers (`── X ──` -> plain `InkSecondary`), Profile link, Dark Mode, Language, Audio/VOX toggles, Offline Mesh info, Diagnostics toggle + disclosure, Emergency Country, Updates, Sign Out | **Keep (Restyle)** | `SettingsViewModel`, `AuthViewModel`, `UpdateViewModel`, `MeshSettingsViewModel` | Fix `Color.Black` on `AGREE & ENABLE`; clean section headers; `Pop` checked switch track |
| **Account** | Header, Profile hero card, Profile & Motorcycle info rows, Edit Profile / Check Updates buttons, `EditProfileDialog`, `UpdateDialog` | **Keep (Restyle)** | `AccountViewModel`, `UpdateViewModel` | Remove `── IDENTIFICATION & COMMS ──` / `── EQUIPMENT & RIG ──` / "Tactical Notes"; fix `Color.Black` on Save & UpdateDialog buttons |

---

## (E) Complete Design System ("Roadside Paper")

### E.1 Color Token Architecture (`ui/theme/Color.kt`)

| Semantic Token | Legacy Alias (Kept for Frozen Files) | Light Hex (Default) | Dark Hex | Usage Rules |
|---|---|---|---|---|
| `Paper` | `GraphiteBase` | `#F5F0E8` | `#1A1714` | Primary screen canvas (60% area) |
| `PaperRaised` | `DarkSlate` | `#FDFAF5` | `#24201C` | Cards, dialogs, bottom bar, elevated sheets |
| `PaperSunken` | `Gunmetal`, `GraphiteSurface` | `#EAE4D8` | `#2E2924` | Input fills, icon chips, neutral pills, recessed containers |
| `InkHairline` | `BorderColor` | `#C2B8A8` | `#463F37` | `1.dp` flat borders & dividers |
| `Ink` | `TextPrimary`, `TechGreen`, `SuccessGreen`, `WarningAmber` | `#282722` | `#F5F0E8` | Primary text, primary icons, active non-pop states (with icon) |
| `InkSecondary` | `TextSecondary`, `TextMuted`, `ElectricCyan`, `NeonViolet` | `#5A5650` | `#B5AEA3` | Secondary text, metadata, section headers, muted icons |
| `InkDisabled` | *(new)* | `#857F76` | `#7A7369` | Disabled text & icons |
| `Pop` | `NeonOrange` | `#A84424` | `#D9724E` | **Max 1 filled element + 2 small accents per screen** (Primary CTA, active PTT, speaking ring, active tab) |
| `PopContainer` | `AmberContainer` | `#F8E5DD` | `#331E17` | Soft terracotta tint for active mic button, host badge, primary action icon container |
| `PaperOnPop` | *(new)* | `#FDFAF5` | `#1A1714` | Text/icon on `Pop` or `Brick` or `Ink` solid buttons (`≥5.5:1` both themes) |
| `Brick` | `AlertRed` | `#8C2518` | `#E56B55` | **Restricted strictly to:** SOS, Leave/End, Delete/Remove, Deafen active, and real errors |
| `BrickContainer` | `HazardContainer` | `#F5DEDB` | `#3E1C18` | Soft brick surface for SOS bar, error banners, deafen active button |
| `PaperOnBrick` | *(new)* | `#FDFAF5` | `#1A1714` | Text/icon on `Brick` solid fills (`8.43:1` light / `5.58:1` dark) |

### E.2 Typography (`ui/theme/Type.kt`)
Fonts loaded via existing GMS GoogleFont provider (`BarlowCondensedFamily`, `SpaceMonoFamily`, `InterFamily`) with clean system fallbacks offline:
- `displayLarge`: Barlow Condensed Black, `40sp` / `44sp` line-height, `1.5sp` letter-spacing
- `titleLarge`: Barlow Condensed ExtraBold, `22sp` / `26sp`, `0.5sp`
- `titleMedium`: Barlow Condensed Bold, `17sp` / `22sp`, `0.5sp` (tightened from 1.2sp for calmer readability)
- `labelLarge`: Barlow Condensed Bold, `14sp` / `18sp`, `0.6sp`
- `labelSmall`: Space Mono Bold, `11sp` / `14sp`, `0.6sp` (used for numeric telemetry, codes, compact labels)
- `bodyLarge`: Inter Medium, `15sp` / `22sp`, `0.2sp`
- `bodyMedium`: Inter Normal, `13sp` / `18sp`, `0.2sp`

### E.3 Spacing, Radius & Touch Targets (`ui/theme/Dimens.kt`)
- **Spacing (`RvSpacing`):** `xs = 4.dp`, `sm = 8.dp`, `md = 12.dp`, `lg = 16.dp`, `xl = 24.dp`, `xxl = 32.dp`
- **Radius (`RvRadius`):** `sm = 8.dp`, `md = 12.dp`, `pill = 100.dp`
- **Touch Targets (`RvTouchTarget`):** `min = 48.dp`, `primary = 56.dp`, `rideScreen = 56.dp`, `ptt = 76.dp`
- **Elevation:** `0.dp` everywhere (flat paper cards separated by `1.dp` `InkHairline` borders).

### E.4 Motion & Accessibility
- **Transitions:** Color state changes use `animateColorAsState` (`tween(180–300ms)`). No decorative bouncing or parallax.
- **PTT / Speaking Pulse:** Subtle alpha modulation (`0.55f..1.0f` on active rim/dot only) — never scale transforms that could alter layout bounds.
- **Chrome Font-Scale Cap:** `CapChromeFontScale(maxScale = 1.3f)` helper wraps persistent bottom bar and non-scrolling top/bottom chrome so `2.0x` system font scale never causes overlap; scrollable content remains uncapped.
- **Colorblind & Grayscale Invariance:** Every status badge/chip pairs a Material icon + clear text label + Ink weight. Zero states rely solely on color.
- **Semantics & TestTags:** Every existing `contentDescription`, `testTag`, and `semantics { onClick(...) }` block is preserved verbatim.

---

## (F) Navigation Architecture & Orphan Audit

### F.1 Persistent Shell (`ui/components/BottomNavShell.kt`)
Hosted in `NavGraph.kt`, wrapping `NavHost` inside a `Scaffold`:
- **Visible ONLY on top-level tabs:** `Routes.HOME`, `Routes.RIDE_STATS`, `Routes.SQUAD`, `Routes.SETTINGS`
- **Hidden on all focused/modal/ride flows:** `SPLASH`, `LOGIN`, `REGISTER`, `HOST_SETUP`, `INVITE_FRIENDS`, `LOBBY`, `INVITES_INBOX`, `DEVICE_SETUP`, `ACTIVE_RIDE_HUD`, `SOS`, `POST_RIDE_SUMMARY`, `ACCOUNT`, `ROUTE_PLANNER`, `JOIN_ROOM`, `JOIN_VIA_TOKEN`, `RIDE_REPLAY`, `OFFLINE_MESH_LOBBY`, `OFFLINE_MESH_HUD`.
- **4 Bottom Bar Tabs (capped at 1.3x font scale, `minHeight = 72.dp`, `>= 48.dp` targets):**
  1. **Dashboard** (`Icons.Default.GridView`) -> `Routes.HOME`
  2. **History** (`Icons.Default.History`) -> `Routes.RIDE_STATS`
  3. **My Squad** (`Icons.Default.Group`) -> `Routes.SQUAD`
  4. **Settings** (`Icons.Default.Tune`) -> `Routes.SETTINGS`
- **Active tab styling:** `56dp x 30dp` pill-shaped Ember tint (`#F3DDD2` `PopContainer`) housing the `Ink` icon + `11sp` bold label below (exact Stitch Roadside Paper design).
- **Dashboard Bento (`HomeScreen.kt`):**
  - Top bar: `"Dashboard"` (`28sp` Manrope Bold) + circular profile avatar (`42dp` inside `48dp` target) with Moss online dot (opens `MoreMenuBottomSheet` for Account, Headset Setup, Route Planner, Inbox, Settings, Sign Out).
  - Hero Bento Card (`28dp` radius, `176dp` height): Full-bleed mountain-pass motorcycle photo (`res/drawable/hero_create_ride.jpg`) + warm espresso-terracotta scrim + `CONVOY LEAD` / `Create a ride` / `Start a convoy and invite your squad` + white circular `→` arrow button (`onHostRideClick`).
  - 2-Column Bento Row (`28dp` radius, `12dp` gutter): Left Sand tile (`#ECE5D6`, Charcoal icon badge) `"Join a ride"` (`onJoinRideClick`) + Right Paper tile (`#FFFDF8`) `"Inbox"` (`onInvitesInboxClick`).
  - Live / Ready Convoy Card (`24dp` radius, `PaperRaised`): Moss dot + `LIVE CONVOY`, ride title & audio status, pill-shaped Ember `"Join Convoy →"` button (`onStartRideClick`) when live, or quick pills (`Route Planner`, `Headset Setup`) when idle.
  - Secondary quick pills (`My Squad`, `Ride History`) + pinned Deep Brick `"Emergency SOS"` strip (`onSosClick`).
- **History Tab (`RideStatsScreen.kt`):**
  - Top bar: `"History"` (`28sp` Manrope Bold) + circular Share button.
  - Filter pills (`All rides` active in `#F3DDD2`/`#9C3E1F`, `This month`, `Synced`, `Longest` on `#ECE5D6`).
  - **Season Totals Bento Card** (`24dp` radius, Sand `#ECE5D6`, cartographic contour waves on Canvas, Moss `"Active Fleet"` pill, 3-column metrics: `Rides` / `km logged` / `Saddle time` + `Avg` & `Top` speed).
  - **Month-Grouped Convoy Cards** (backed by real `RideStatsState.sessions` from Room `RideDao`): `62dp` stylized mini-route Canvas thumbnail, ride name, date/time, distance/duration/avg-speed pills, synced badge & chevron (`onReplayRide(session.id)`).
  - **Field Journal Card** (`20dp` radius, Moss flag circle, opens latest replay).

### F.2 Orphan Audit (Zero Unreachable Destinations)

| Previously Reachable Destination | Old Entry Point | New Entry Point(s) | Status |
|---|---|---|---|
| `Routes.HOST_SETUP` | Home action row | Home primary action row ("Host a Ride") | Reachable |
| `Routes.INVITES_INBOX` | Home action row | Home action row + More sheet | Reachable |
| `Routes.JOIN_ROOM` | Home action row | Home action row ("Join with Code") | Reachable |
| `Routes.JOIN_VIA_TOKEN` | Deep link (`DeepLinkHolder`) | Unchanged (`NavGraph` `LaunchedEffect`) | Reachable |
| `Routes.ROUTE_PLANNER` | Home action row | Home action row + More sheet | Reachable |
| `Routes.SQUAD` | Home action row | BottomNav Tab 2 ("Squad") + Home action row | Reachable |
| `Routes.RIDE_STATS` | Home action row | BottomNav Tab 3 ("History") + Home action row | Reachable |
| `Routes.RIDE_REPLAY` | RideStats "Replay Session" | RideStats "Replay Session" (unchanged) | Reachable |
| `Routes.ACCOUNT` | Home `ProfileDrawer` + Settings | Top-right Avatar sheet on Home + BottomNav "More" sheet + Settings | Reachable |
| `Routes.SETTINGS` | Home `ProfileDrawer` + Account | Top-right Avatar sheet on Home + BottomNav "More" sheet + Account | Reachable |
| `Routes.DEVICE_SETUP` (GLOBAL) | Home `ProfileDrawer` + Settings | Top-right Avatar sheet on Home + BottomNav "More" sheet + Settings | Reachable |
| `Routes.DEVICE_SETUP` (Convoy) | Home Active Ride card / Lobby / Squad | Unchanged | Reachable |
| `Routes.SOS` | Home SOS button / Room SOS button / Mesh SOS | Unchanged (Home SOS bar + Room TalkControls + MeshTopBar) | Reachable |
| `Routes.LOBBY` | HostSetup / JoinRoom / InvitesInbox / RideInviteActivity | Unchanged | Reachable |
| `Routes.INVITE_FRIENDS` | Direct route | Unchanged (`NavGraph`) | Reachable |
| `Routes.ACTIVE_RIDE_HUD` | DeviceSetup `onReady` | Unchanged | Reachable |
| `Routes.POST_RIDE_SUMMARY` | Room `onLeave` | Unchanged | Reachable |
| `Routes.OFFLINE_MESH_LOBBY` | Room `onPairBackupClick` + Settings callback | Unchanged | Reachable |
| `Routes.OFFLINE_MESH_HUD` | OfflineMeshLobby `onEnterSession` | Unchanged | Reachable |
| Sign Out | Home `ProfileDrawer` + Settings | Top-right Avatar sheet on Home + BottomNav "More" sheet + Settings | Reachable |

**Orphan Count: 0.**

---

## (G) Per-Screen Design Briefs, Zone Budgets & Composable Parity Tables

### G.1 `HomeScreen`
- **Zone Budget (720dp height):**
  - Top Header (Title + 48dp Profile Avatar): `64dp`
  - Optional OEM Battery Banner (dismissible): `0dp` or `60dp`
  - Active/Idle Ride Status Card: `104dp` (Pop border + `LIVE` badge when active; `InkHairline` when idle)
  - Quick Actions (`LazyColumn` weight `1f`): `~340dp` (6 flat `PaperRaised` rows, `56dp` min height each; Host row icon circle uses `PopContainer` + `Pop`)
  - Pinned SOS Strip: `56dp` (`BrickContainer` fill + `1.5dp Brick` border)
  - BottomNavShell (rendered by `NavGraph` Scaffold): `64dp` + system nav bar inset
- **Pop Budget:** 1 Pop accent (Active Ride border OR Host icon badge) + 0 filled Pop buttons when idle (or 1 when active). Brick strictly on SOS strip.
- **Parity Table (`HomeScreen`):**
  | Parameter | Type | Visual Home in Redesign | Same Wiring Confirmed |
  |---|---|---|---|
  | `viewModel` | `HomeViewModel` | Ride Status Card (`hasActiveRide`, `activeRideName`, `activeRideSubtitle`), `LaunchedEffect` refresh, `clearActiveRide()` on sign-out | Yes |
  | `onHostRideClick` | `() -> Unit` | Quick Actions Row 1 ("Host a Ride") | Yes |
  | `onJoinRideClick` | `() -> Unit` | Quick Actions Row 3 ("Join with Code") | Yes |
  | `onStartRideClick` | `(String) -> Unit` | Ride Status Card tap when `hasActiveRide && activeRideName.isNotBlank()` | Yes |
  | `onSquadClick` | `() -> Unit` | Quick Actions Row 5 ("My Squad") | Yes |
  | `onSettingsClick` | `() -> Unit` | Profile/More Bottom Sheet -> "Settings" | Yes |
  | `onRoutePlannerClick` | `() -> Unit` | Quick Actions Row 4 ("Route Planner") | Yes |
  | `onRideHistoryClick` | `() -> Unit` | Quick Actions Row 6 ("Ride History") | Yes |
  | `onDeviceSetupClick` | `() -> Unit` | Profile/More Bottom Sheet -> "Audio & Helmet Setup" | Yes |
  | `onInvitesInboxClick` | `() -> Unit` | Quick Actions Row 2 ("Pending Invites") | Yes |
  | `onSosClick` | `() -> Unit` | Pinned Bottom Emergency SOS Strip | Yes |
  | `onAccountClick` | `() -> Unit` | Profile/More Bottom Sheet -> "Account" | Yes |
  | `onLogoutSuccess` | `() -> Unit` | Profile/More Bottom Sheet -> "Sign Out" -> Confirm Dialog -> `clearActiveRide()` + `onLogoutSuccess()` | Yes |

### G.2 Active Ride HUD (`RoomScreen` [FROZEN] + `RideTopBar` + `TalkControls` + `RiderList` + `ParticipantCard`)
- **Zone Budget (720dp height):**
  - `RideTopBar`: `56dp` min (`statusBars` + horizontal `displayCutout` inset). Left: `ConnectionPill` (`Ink` connected, `Pop` connecting/reconnecting, `Brick` no signal) + optional `DIAG` pill (`BrickContainer`/`Brick`). Center: Ride name (`Ink` bold) + active speaker (`Pop` bold when speaking, `InkSecondary` count when idle, fixed `min = 16.dp` height). Right: `AudioDeviceBadge` (`PaperRaised`/`InkHairline`, `Brick` on mismatch) + `48dp` Leave Call circle (`BrickContainer` fill, `Brick` border & icon).
  - Banners (`SosAlertBanner` [FROZEN] + error banner [FROZEN in `RoomScreen`]): dynamic `0..80dp`.
  - `RiderList` (`LazyColumn` weight `1f`, `minHeight = 120.dp`): `ParticipantCard` rows on `PaperRaised` (`PopContainer` tint + `1.5dp Pop` border when speaking, `Brick` border when ghost, `InkHairline` default). Telemetry column (`participant_distance_*`, `participant_speed_*`) uses `Ink` & `InkSecondary` with `softWrap = false`, fixed `min = 56.dp` width.
  - Bottom Dock (`DarkSlate` -> `PaperRaised` + `1dp InkHairline` border): `AudioVuMeter` [FROZEN] + `audioStatusLine` (`28dp`) + `TalkControls` (`76dp` min).
    - `TalkControls` row: `52dp` Mic toggle (`PopContainer`/`Pop` when ON, `PaperSunken`/`InkSecondary` when MUTED) | `52dp` Deafen toggle (`BrickContainer`/`Brick` when DEAFENED, `PaperSunken`/`InkSecondary` when HEARING) | Flexible `weight(1f)` x `76dp` PTT Perforated Capsule (`Pop` fill + `PaperOnPop` label when pressed; `PaperSunken` + `Pop` rim when VOX open; `PaperRaised` + `InkHairline` when idle) | `52dp` SOS square (`BrickContainer` + `2dp Brick` border).
- **Zero-Shift Invariant:** All heights/widths in `RideTopBar`, `TalkControls`, and `ParticipantCard` are fixed across state toggles (`<= 0.25dp` shift verified by `RoomLobbyLayoutTest`).
- **Parity Tables:** Every single parameter of `RideTopBar` (17 params), `TalkControls` (10 params), `RiderList` (8 params), and `ParticipantCard` (7 params) remains 100% identical and wired identically.

### G.3 `LobbyScreen`
- **Zone Budget:**
  - `TopAppBar`: `56dp` — Back icon (`ArrowBack`, opens `showExitDialog`), Title (`effectiveDisplayName`), Subtitle (`Waiting to start` / `Connecting` / `Retrying` / `Failed`). Duplicate `ExitToApp` action icon removed.
  - Scrollable `LazyColumn` (weight `1f`): Error/connecting banner -> `CodeCard` (monospaced 8-char code in `Ink`, Copy outlined + Share solid `Ink` or outlined to keep 1-Pop-fill rule so `Start ride` owns the single Pop fill!) -> `OfflineConfigCard` (`PaperRaised` + `1dp InkHairline`, 5 step rows with icons/symbols in `Ink`/`Pop`/`Brick`) -> `Joined (n)` (`Ink`) -> `Invited (n)` (`InkSecondary`) -> `Declined (n)` (`InkSecondary`).
  - Bottom Bar: Host sees `StartRideGate` progress OR `PrimaryButton` (`lobby_start_button`, single Pop fill) + `SecondaryButton` ("Invite friends"). Participant sees Waiting pill (`PaperRaised` + `InkHairline`) + destructive `SecondaryButton` ("Leave").
- **Parity Table (`LobbyScreen`):** All 6 parameters (`convoyName`, `viewModel`, `homeViewModel`, `onStartRide`, `onBackClick`, `onInviteMoreClick`) and all 10 `OfflineConfigCard` parameters remain identical and wired.

### G.4 `SosScreen` & `EmergencyAlertActivity`
- **Zone Budget:**
  - Full-screen `PaperRaised` (`#FDFAF5`) with unmistakable `8.dp` `Brick` (`#8C2518`) perimeter frame (dark mode: `#1A0808`).
  - Top bar: Brick dot + "EMERGENCY BEACON ACTIVE" + Close button (`isCancelled = true; onCancelClick()`).
  - Center: `220dp` (`SosScreen`) / `180dp` (`EmergencyAlertActivity`) countdown ring (`10s` / `15s` untouched) or `Sent` / `Sending` / `Failed` / `NotSent` card.
  - Bottom Action Stack (`12dp` spacing):
    1. Call local emergency number (`56dp`, `Pop` fill + `PaperOnPop` text)
    2. Send SOS Now / Retry (`56dp`, `Brick` fill + `PaperOnBrick` text)
    3. Cancel Emergency "I'm Okay" (`64dp`, `Ink` fill + `PaperOnPop` text)
- **Parity Table (`SosScreen`):** `state: SosState`, `onCancelClick: () -> Unit`, `onSend: () -> Unit` — 100% preserved, countdown coroutine untouched.

### G.5 `SettingsScreen` & `AccountScreen`
- **`SettingsScreen`:** Clean `Paper` background, Top header without ALL-CAPS clutter, plain `InkSecondary` section headers (no `── X ──` dashes), `PaperRaised` flat `12dp` cards with `1dp InkHairline`, `PaperSunken` icon circles with `InkSecondary` icons, `Pop` checked switch track (`PaperOnPop` thumb), `Pop` value labels, `Brick` outlined Sign Out button. Fix `AGREE & ENABLE` button in Diagnostics Disclosure to use `Pop` container + `PaperOnPop` text. All 9 parameters preserved verbatim.
- **`AccountScreen`:** Clean header ("Account"), Profile card (`PaperRaised`, `96dp` avatar with `2dp InkHairline` border, handle chip on `PaperSunken` with `Ink` text, auth provider row), plain section headers ("Profile", "Motorcycle"), flat info rows, `SecondaryButton` / outlined buttons ("Edit Profile", "Check for Updates"), `EditProfileDialog` with `PaperOnPop` text on `Pop` "Save" button (fixing `Color.Black` contrast bug). All 5 parameters preserved verbatim.

### G.6 Secondary Screens (`Login`, `Register`, `HostSetup`, `JoinRoom`, `DeviceSetup`, `Squad`, `RideStats`, `RideReplay`, `RoutePlanner`, `PostRideSummary`, `InvitesInbox`, `InviteFriends`, `RideInviteActivity`, `OfflineMeshLobbyScreen`, `OfflineRoomScreen`, `OfflineAwareRoomHost`)
- Every screen uses `Paper` canvas, `PaperRaised` `12dp` flat cards with `1dp InkHairline`, `PaperSunken` input/chip surfaces, `Ink`/`InkSecondary` hierarchy, at most **1 filled Pop (`#A84424`) primary CTA** with `PaperOnPop` (`#FDFAF5`) text, and `Brick` (`#8C2518`) strictly for errors/destructive actions.
- **Offline Mesh UI (`mesh/ui/`):** `MeshColors.kt` becomes theme-aware (delegating to Roadside Paper Light / Warm Charcoal Dark palettes — zero navy/cyan/indigo/neon). All `Color.White` text on light-surface cards in `MeshControls.kt` and `OfflineAwareRoomHost.kt` updated to `MeshColors.TextPrimary` (or `MeshColors.TextOnAccent` for filled Pop/Brick buttons). Zero `Color(0x...)` literals outside `MeshColors.kt` (`StringsParityTest` compliant).
- **Every `Color.Black` on `NeonOrange`/`TechGreen` button fixed** across `AccountScreen`, `SettingsScreen`, `UpdateDialog`, `MainActivity`, `OfflineMeshLobbyScreen`, `SquadScreen`, `PostRideSummaryScreen`, `InvitesInboxScreen`, `InviteFriendsScreen`, `JoinRoomScreen`, `DeviceSetupScreen`, `RoutePlannerScreen`, `RideReplayScreen`.

---

## (H) Interactive HTML Design Prototype v3

Saved at: `C:\Users\umerz\.gemini\antigravity\brain\92de0dcf-8ea0-487a-b676-bb1db4abb4f1\ridervoice_design_prototype_v3.html`

Includes:
- **10 interactive screens:** Splash, Login, Home (with live/idle toggle & More sheet), Lobby, Active Ride HUD (interactive press-and-hold PTT, Mic, Deafen, VOX, rider count stress), SOS (live 10s countdown + cancel), Settings, Account, Post-Ride Summary, Offline Mesh HUD.
- **Live Controls:** Palette Switcher (Candidate A / Candidate B / Candidate C Roadside Paper), Viewport Width (`320px` / `360px` / `411px`), Font Scale Slider (`1.0x`–`2.0x` demonstrating `1.3x` chrome cap), Locale Switcher (`EN` / `HI` / `MR` using real `strings.xml` translations), Grayscale Simulation toggle, Data-Source Overlay toggle, Stress controls (Speaking / Offline / 30 Riders).
- **8 Automated Live Checkers** running per palette & viewport: Contrast AA, Pop Budget (≤1 fill), Blue-Free Audit, Touch Target ≥48px, Zero Horizontal Overflow, Zero Sibling Shift on Speaking/PTT, Chrome Font-Scale Cap, Orphan Navigation Check.

---

## (I) Splash Video Asset Brief

For a future real video/animated splash upgrade (outside current zero-dependency implementation scope):
- **Duration:** `1.2s–1.8s` seamless fade-in, skippable/non-blocking (immediately hands off when Firebase Auth resolves, matching current `SplashScreen` behavior).
- **Visual Direction:** Minimalist editorial line-art animation on `#F5F0E8` Warm Cream paper: two parallel contour road lines curve gently into a subtle terracotta (`#A84424`) soundwave pulse that settles into the `RIDERVOICE` Barlow Condensed wordmark in `#282722` Charcoal Ink.
- **Format:** `1080x1920` (9:16 portrait), `60fps`, H.264 MP4 or AV1 WebM (`< 400 KB`), silent (never interrupts helmet Bluetooth audio focus!), exact `#F5F0E8` matte background so edges blend seamlessly with `GraphiteBase`.
- **Fallback:** Current Compose `SplashScreen` (`alpha` + `scale` animation on `Paper`) serves as zero-latency default.

---

## (J) Compatibility, Contrast Audits, Frozen Paths & CI Guard Risk Checks

### J.1 Frozen Path Audit (Verified Against `.github/scripts/mesh-commit-guard.sh`)
| Path | Guard Status | Action |
|---|---|---|
| `ui/screens/RoomScreen.kt` | **FROZEN (Not allowlisted)** | **DO NOT EDIT** — styled automatically via `Color.kt` tokens |
| `ui/screens/room/AudioRouteSheet.kt` | **FROZEN** | **DO NOT EDIT** — uses `DarkSlate`, `Gunmetal`, `TextPrimary`, `TextSecondary`, `ElectricCyan`, `NeonOrange` -> all WCAG AA in Roadside Paper! |
| `ui/screens/room/AudioVuMeter.kt` | **FROZEN** | **DO NOT EDIT** — uses `LightPalette.SurfaceAlt`, `ElectricCyan` (`InkSecondary`), `NeonOrange` (`Pop`), `AlertRed` (`Brick`), `BorderColor` -> clean 3-stage meter! |
| `ui/screens/room/DiagnosticsDialog.kt` | **FROZEN** | **DO NOT EDIT** — uses `DarkSlate`, `Gunmetal`, `TextPrimary`, `TextSecondary`, `TechGreen` (`Ink`), `NeonOrange` (`Pop`), `AlertRed` (`Brick`), and `DiagnosticsConsentDialog` uses `Color.White` on `TechGreen` (`#282722` -> **14.9:1 contrast!**) |
| `ui/screens/room/LeaveRideDialog.kt` | **FROZEN** | **DO NOT EDIT** — uses `DarkSlate`, `NeonOrange` with `Color.White` (light: **5.97:1**) / `Color.Black` (dark: **5.50:1**), `AlertRed` with `Color.White` (light: **8.78:1**) |
| `ui/screens/room/SosAlertBanner.kt` | **FROZEN** | **DO NOT EDIT** — uses `#330B0B` dark banner with `Color.White` text + `AlertRed` button with `Color.White` text (**8.78:1**). Note: `AlertRed` (`#8C2518`) title on `#330B0B` is frozen; we keep `AlertRed` at `#8C2518` light / `#E56B55` dark. |
| `ui/screens/room/RideTopBar.kt` | **ALLOWLISTED** | **EDITABLE** — apply Roadside Paper tokens, preserve all `testTag`s & zero-shift bounds |
| `ui/screens/room/TalkControls.kt` | **ALLOWLISTED** | **EDITABLE** — apply Roadside Paper tokens, 76dp PTT, remove `DarkPalette` leak in light mode, preserve all `testTag`s |
| `ui/screens/room/RiderList.kt` | **ALLOWLISTED** | **EDITABLE** — clean spacing & empty state |

### J.2 Contrast Bugs Discovered & Fixed in Implementation
1. `MainActivity.kt` L238–240: `TechGreen` (`#282722`) button had `Color.Black` text (**1.19:1**!) -> fix to `PaperOnPop`.
2. `AccountScreen.kt` L571–574: `NeonOrange` (`#A84424`) Save button had `Color.Black` text (**3.5:1**!) -> fix to `PaperOnPop`.
3. `SettingsScreen.kt` L435–438: `TechGreen` (`#282722`) "AGREE & ENABLE" button had `Color.Black` text (**1.19:1**!) -> fix to `Pop` + `PaperOnPop`.
4. `UpdateDialog.kt` L234, 276, 440, 490, 532, 598: `NeonOrange` / `TechGreen` buttons had `Color.Black` text -> fix all to `PaperOnPop`.
5. `OfflineMeshLobbyScreen.kt` L478, 482, 560, 656, 665: `NeonOrange` / `ElectricGreen` buttons had `Color.Black` text -> fix all to `MeshColors.TextOnAccent`.
6. `TalkControls.kt` L81, 123: Used `DarkPalette.SurfaceAlt` and `DarkPalette.EmergencyContainer` unconditionally even in Light Theme! -> fix to `PopContainer` and `BrickContainer`.
7. `Color.kt` `DarkPalette`: Previously left cool blue-gray (`#20252D`, `#2E353E`, `#8E98A5`), cyan (`#26C6DA`), neon green (`#4ADE80`), and violet (`#818CF8`) -> replace all with warm Roadside Paper Dark tokens.

---

## (K) Complete File-by-File Implementation Blueprint

1. **`ui/theme/Color.kt`**: Finalize `LightPalette` (including `PopContainer = #F8E5DD`, `Disabled = #857F76`) and warm blue-free `DarkPalette`. Export semantic tokens (`Paper`, `PaperRaised`, `PaperSunken`, `Ink`, `InkSecondary`, `InkDisabled`, `InkHairline`, `Pop`, `PopContainer`, `PaperOnPop`, `Brick`, `BrickContainer`, `PaperOnBrick`) alongside all existing legacy aliases.
2. **`ui/theme/Theme.kt`**: Wire `LightScheme` and `DarkScheme` cleanly (`onPrimary = PaperOnPop`, `onError = PaperOnBrick`, `surfaceVariant = PaperSunken`, `outline = InkHairline`).
3. **`ui/theme/Type.kt` & `ui/theme/Dimens.kt`**: Refine letter-spacing (`displayLarge = 1.5sp`, `titleLarge = 0.5sp`, `titleMedium = 0.5sp`, `labelLarge = 0.6sp`, `labelSmall = 0.6sp`); `RvRadius.md = 12.dp`; `RvTouchTarget.ptt = 76.dp`.
4. **`ui/components/BottomNavShell.kt`** *(NEW)*:
   - `CapChromeFontScale(maxScale = 1.3f, content)` helper.
   - `RvBottomNavigationBar(currentRoute, onHomeClick, onSquadClick, onHistoryClick, onMoreClick)` — 4 tabs, flat `PaperRaised` surface, `1.dp` top `InkHairline`, `2.dp` top `Pop` active indicator bar, `>= 48.dp` targets, `navigationBarsPadding()`.
   - `MoreMenuBottomSheet(onDismiss, onAccountClick, onSettingsClick, onDeviceSetupClick, onRoutePlannerClick, onInvitesInboxClick, onSignOutClick)` — Material3 1.0.1 compatible bottom-anchored `Dialog` sheet (`PaperRaised`, `InkHairline`, 48dp+ rows, Brick Sign Out row).
5. **`navigation/NavGraph.kt`**: Wrap `NavHost` with persistent `BottomNavShell` (shown only on `Routes.HOME`, `Routes.SQUAD`, `Routes.RIDE_STATS`), handle More sheet & Sign Out confirmation dialog cleanly without altering any route name, argument, back-stack rule, or deep link.
6. **`ui/screens/HomeScreen.kt`**: Remove `ModalNavigationDrawer`, keep all 12 parameters wired (top-right 48dp Profile Avatar opens `MoreMenuBottomSheet` with Account, Settings, Audio Setup, Sign Out + confirmation), clean Roadside Paper copy & layout, 1-Pop budget, `BrickContainer`/`Brick` SOS bar.
7. **`ui/components/RvComponents.kt` & `RvScreen.kt` & `ParticipantCard.kt` & `TacticalButton.kt` & `ProfileDrawer.kt` & `AddFriendDialog.kt` & `AddRidersBottomSheet.kt` & `UpdateDialog.kt`**:
   - `PrimaryButton`: `Pop` container, `PaperOnPop` content.
   - `SecondaryButton`: `InkHairline` border (`Brick` if destructive).
   - `StatusChip`: Icon + text + Ink weight (`PaperSunken`/`Ink` for Joined/Ready, `PopContainer`/`Pop` for Host, `PaperSunken`/`InkSecondary` for Invited/Declined/Left).
   - `RiderAvatar`: Speaking ring uses `Pop` (zero inner-bounds shift!).
   - `CodeCard`: Code in `Ink`, Copy outlined, Share outlined/ink (so `Start ride` remains the single Pop fill in Lobby).
   - `ParticipantCard`: Speaking border/avatar uses `Pop` + `PopContainer`, ghost uses `Brick`, replace roleplay status strings ("Speaking" / "Connected"), preserve all `testTag`s and zero-shift heights.
   - `UpdateDialog`, `AddFriendDialog`, `AddRidersBottomSheet`: Fix all `Color.Black` on `Pop`/`Ink`, remove roleplay jargon ("Firmware Upgrade" -> "App Update", "OTA Transmission" -> "Downloading", "Dispatch Reinforcements" -> "Invite Riders", "Commission Squad Rider" -> "Add Squad Rider").
8. **`ui/screens/room/RideTopBar.kt` & `TalkControls.kt` & `RiderList.kt`** *(Allowlisted)*:
   - `RideTopBar`: Replace hardcoded `Color(0xFFFF3B30)` with `Brick`/`BrickContainer`, Ride name `Ink`, Active speaker `Pop`, Connected pill `Ink`, Connecting `Pop`, Failed `Brick`, Leave button `BrickContainer`/`Brick`. Preserve every `testTag`, `contentDescription`, and bound.
   - `TalkControls`: Mic active `PopContainer`/`Pop`, Deafen active `BrickContainer`/`Brick`, PTT capsule `76.dp` height, pressed `Pop`/`PaperOnPop`, VOX `PaperSunken`/`Pop`, SOS `BrickContainer`/`Brick`. Preserve every `testTag` and `semantics`.
9. **`ui/screens/LobbyScreen.kt`**: Remove duplicate `ExitToApp` TopAppBar action; restyle section headers (`Ink` / `InkSecondary`), `OfflineConfigCard` (`PaperRaised` + `InkHairline`), retry buttons (`PaperOnPop`).
10. **`ui/screens/SettingsScreen.kt` & `AccountScreen.kt`**: Plain `InkSecondary` section headers (no `── X ──`), clean labels, fix `Color.Black` button text bugs, `Pop` checked switches.
11. **`ui/screens/SplashScreen.kt`, `LoginScreen.kt`, `RegisterScreen.kt`, `HostSetupScreen.kt`, `JoinRoomScreen.kt`, `DeviceSetupScreen.kt`, `SosScreen.kt`, `EmergencyAlertActivity.kt`, `RideInviteActivity.kt`, `SquadScreen.kt`, `RideStatsScreen.kt`, `RideReplayScreen.kt`, `RoutePlannerScreen.kt`, `PostRideSummaryScreen.kt`, `InvitesInboxScreen.kt`, `InviteFriendsScreen.kt`**: Full Roadside Paper token alignment, remove remaining roleplay jargon, fix all contrast bugs, enforce 1-Pop-fill rule, ensure 48dp min touch targets.
12. **`mesh/ui/MeshColors.kt`, `MeshControls.kt`, `OfflineAwareRoomHost.kt`, `OfflineMeshLobbyScreen.kt`, `OfflineRoomScreen.kt`**: Make `MeshColors` theme-aware Roadside Paper (zero blue/cyan/indigo/neon, zero `Color(0x...)` outside `MeshColors.kt`), replace raw `Color.White` text on paper surfaces with `MeshColors.TextPrimary` / `MeshColors.TextOnAccent`, preserve every `mesh_*` testTag and zero-shift layout.
13. **`MainActivity.kt`**: Fix Update Complete dialog `CONTINUE` button (`Pop` + `PaperOnPop`), clean crash report dialog spacing/contrast.
14. **Cleanup**: Delete untracked `gradle_out.txt` if leftover.

---

## (L) Verification Matrix & Self-Check

After implementation, ALL checks below MUST pass:
1. `./gradlew :app:testDebugUnitTest` (from `mobile-app/`): **0 failures**, total tests `>= 704` (census floor; expect `~793`), including:
   - `RoomLobbyLayoutTest` (320/360/411dp, 2.0x font scale, landscape, RTL, 1/6/30 riders, 48dp targets, 0 overlap, 0 shift, 0 text clip)
   - `StringsParityTest` (`mesh_*` EN/HI/MR parity + zero hex color literals outside `MeshColors.kt`)
   - `SosCountdownTest` & `SosViewModelTest`
   - `FallbackControlsTest`
2. `./gradlew :app:lintDebug` (from `mobile-app/`): **0 new lint errors**.
3. `bash .github/scripts/mesh-commit-guard.sh`: **0 frozen path violations**.
4. `bash .github/scripts/mesh-invariants.sh`: **0 invariant violations**.
5. Manual grep audit: Zero `Color(0xFF0F172A)` / `Color(0xFF38BDF8)` / `Color(0xFF26C6DA)` / `Color(0xFF818CF8)` / `Color(0xFFFF6D00)` / `Color(0xFF00E676)` remaining in editable UI files; zero `Color.Black` on `NeonOrange` or `TechGreen` buttons.
