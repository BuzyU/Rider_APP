# RiderVoice — Reimagined Minimalist & Modern UI/UX Specification (`WP-61`)

**Status:** Visual Design Representation & Interactive Prototype Specification (Generate-Only Showcase)  
**Deliverables in this Directory:**
- [`index.html`](./index.html) — Unified Light & Dark Side-by-Side Studio Showcase + Motion Director Bar
- [`ridervoice-light.html`](./ridervoice-light.html) — Standalone **"Daylight Alpine"** Light Theme Interactive Visual Representation (Simulator + All-Screens Blueprint Grid)
- [`ridervoice-dark.html`](./ridervoice-dark.html) — Standalone **"Obsidian Night"** Dark Theme Interactive Visual Representation (Simulator + All-Screens Blueprint Grid)
- [`REIMAGINED_UIUX_SPEC.md`](./REIMAGINED_UIUX_SPEC.md) — Architectural Design System, Information Architecture, Curated Motion Suite, and Complete Screen (`S01`–`S18`) & Modal/Sheet (`M01`–`M11`) Verification Matrix

---

## 1. Design Philosophy: Minimalist, Modern, Glove-First Clarity

The reimagined RiderVoice UI/UX discards heavy military/CB-radio skeuomorphism, cluttered stacked action lists, and the hidden side drawer (`ProfileDrawer`) in favor of a **clean-slate minimalist and modern aesthetic** engineered specifically for motorcycle riders in both glaring midday sun and pitch-black night rides:

1. **5-Tab Minimalist Bottom Navigation + Persistent Live-Ride Mini-Player:**
   - Replaces the hidden hamburger side drawer (`ProfileDrawer`) with a thumb-natural `64dp` docked Bottom Navigation bar (`Ride`, `Routes`, `Squad`, `Activity`, `You`).
   - Automatically hides during active full-screen voice HUDs (`S09`, `S11`), focused setup/staging flows (`S05`–`S08`, `S10`, `S12`), and emergency/incoming-call overlays (`S17`, `S18-Invite`).
   - Whenever an online ride or offline mesh session is active and the rider minimizes the HUD to check `Routes`, `Squad`, `Activity`, or `You`, a **Persistent Floating Live-Ride Mini-Player Pill** docks `8dp` above the Bottom Navigation bar with a live 5-bar voice equalizer, Convoy Name, `Cloud` / `Mesh · 4 Riders` badge, 1-tap `Mute/Unmute`, and 1-tap expand back to full-screen `S09`/`S11`.
2. **Calm, Human Rider Terminology:**
   - Replaces cryptic internal strings (`LIVEKIT SFU`, `UDP MESH`, `DEAFEN`, `PEER CAP`) with scannable rider language (`Cloud Intercom`, `Offline Mesh`, `Mute Speaker`, `4/6 Riders`, `Max 5 Hops`).
3. **Glove-First Touch Ergonomics:**
   - Primary sculpted Push-to-Talk (`HOLD TO TALK` / `VOX ACTIVE`) zone: **`88dp` height**, full-width thumb target at the bottom edge of active HUDs.
   - Primary CTAs (`Start a Ride`, `Join Convoy`, `Launch Ride Intercom`, `Start Offline Mesh`): **`>= 56dp` height**.
   - Secondary HUD and toolbar controls (`Mute`, `Mute Speaker`, `Audio Route`, `Leave`, `SOS`, segmented tabs): **`>= 48dp` touch target height** with `>= 8dp` inter-control spacing.
   - Deliberate **`1.2s` Hold-to-Trigger SOS** progress bar to eliminate accidental single-tap false alarms with winter or armored gloves.

---

## 2. Dual Color Systems: "Daylight Alpine" (Light) & "Obsidian Night" (Dark)

| Semantic Token | Light Theme ("Daylight Alpine") | Dark Theme ("Obsidian Night") | Purpose |
|---|---|---|---|
| `--bg-canvas` | `#F7F8FA` (Warm Alabaster) | `#090B0E` (True OLED Obsidian) | Primary screen background |
| `--bg-surface` | `#FFFFFF` (Pure Elevated Card) | `#12161C` (Carbon Slate Card) | Primary cards, HUD modules, bottom nav |
| `--bg-recessed` | `#EFF2F6` (Soft Cool Well) | `#191F27` (Elevated Graphite Pill) | Segmented controls, input fields, chips |
| `--border-hairline` | `#E5E8EE` (`1px solid`) | `rgba(255, 255, 255, 0.08)` | Crisp architectural surface separation |
| `--ink-primary` | `#0F172A` (Deep Slate Ink) | `#F8FAFC` (Ice White Ink) | Headlines, callsigns, primary telemetry |
| `--ink-secondary` | `#475569` (Balanced Slate) | `#94A3B8` (Cool Muted Silver) | Labels, subtitles, secondary metadata |
| `--ink-muted` | `#64748B` (Neutral Caption) | `#64748B` (Subtle Slate) | Hints, inactive icons, timestamps |
| `--accent-primary` | `#FF5A1F` (Safety Orange) | `#FF6B35` (Luminous Safety Amber) | Primary CTAs, active nav indicator, slipstream |
| `--voice-active` | `#0D9488` (Emerald Teal) | `#14B8A6` (Luminous Voice Teal) | Speaking rings, active voice equalizer, mesh ready |
| `--mesh-accent` | `#0284C7` (Alpine Sky) | `#38BDF8` (Tactical Cyan) | Multi-hop relay topology, hop badges (`Max 5 Hops`) |
| `--warning-amber` | `#D97706` (Road Amber) | `#F59E0B` (High-Vis Amber) | Transport switching, hardware mic busy, notices |
| `--danger-sos` | `#DC2626` (Emergency Crimson) | `#EF4444` (High-Vis Coral Red) | Guarded SOS hold bar, crash countdown, leave/end |

### Typography & Numeric Discipline
- **UI & Display Sans:** `-apple-system, BlinkMacSystemFont, "Plus Jakarta Sans", "Inter", "Segoe UI", Roboto, sans-serif`
- **Telemetry & Code Monospace:** `"JetBrains Mono", "SFMono-Regular", Consolas, monospace` with mandatory `font-variant-numeric: tabular-nums` on all elapsed timers, countdowns (`15s` SOS / `10s` Crash), speeds (`km/h`), distances (`km`), hop counts (`Max 5 Hops`), and pairing codes so digits never jitter during live updates.

---

## 3. Information Architecture: 5-Tab Bottom Navigation Bar

| Tab # | Label | Primary Screen | Unified Scope (Replacing Legacy `ProfileDrawer`) |
|---|---|---|---|
| **Tab 1** | **`Ride`** | `S04` Ride Command Hub | Greeting, 1-tap `Helmet & Audio Readiness Pill` (`M03`), `Resume Active Convoy` banner, `Start a Ride` (`S05`), `Join Ride` (`M01`/`S07`), `Offline Mesh Intercom` card (`S10`), Battery tip, guarded `Hold for SOS` (`S17`). |
| **Tab 2** | **`Routes`** | `S13` Route Planner & Radar | Full-bleed minimalist Map + 1-tap `Offline Compass-Radar` toggle, `Origin` (`Use Current GPS`), `Destination`, `Swap`, Ride Style pills (`Fastest`, `Scenic`, `Twisty`, `Off-Road`), Elevation spline, `Save`, `Export GPX`, `Invert`, `Clear`, `Start Ride With Route`. |
| **Tab 3** | **`Squad`** | `S16` Squad & Invites | Unified segmented view: **`My Squad`** (`@handle` instant search, online/in-convoy status, `Invite to Ride`, `Remove` `M09`, `+ Add Rider` `M08`) + **`Invites`** (unread dot, `Accept & Join`, `Decline`). |
| **Tab 4** | **`Activity`** | `S14` Telemetry & Logbook | Timeframe filter (`7D`, `30D`, `Season`, `All`), `Share Stats`, 4 KPI tiles, segmented Bezier chart (`Overview`, `Speed`, `Elevation`), and `Recent Rides Logbook` with 1-tap **`Replay`** (`S15`). |
| **Tab 5** | **`You`** | `S18` Profile, Helmet & Settings | Unified `Profile & Bike` (`Edit Profile` `M10`), `Light / Dark / System` + `Language` (`System`, `EN`, `हिन्दी`, `मराठी`), `Helmet, Mic & Audio Calibration` (live mic meter, loopback test, VOX sensitivity, wind noise, BT PTT test), `Offline Mesh & Backup` (`Max 5 Hops`, 8h encrypted backup status + `Clear Backup`), Regional Emergency Number, Privacy & 8-state OTA Updater (`M11`), and `Sign Out`. |

---

## 4. Curated Motion & Micro-Interaction Suite (`60fps`, Reduced-Motion Safe)

Every animation is built with GPU-accelerated transforms, opacities, and SVG path attributes, and honors both `@media (prefers-reduced-motion: reduce)` and the Studio **`Reduced Motion: OFF / ON`** toggle:

| # | Animation / Micro-Interaction | Target Screen(s) | Timing & Choreography | UX Rationale |
|---|---|---|---|---|
| **A1** | **Right-to-Left Motorcycle Sweep & Luminous Slipstream Logo Reveal** | `S01` (`Splash`) | `1.15s cubic-bezier(0.22, 1, 0.36, 1)` | Minimalist geometric motorcycle silhouette transits smoothly from right (`+115%`) to left (`-115%`). Its safety-orange slipstream trail draws across the center and reveals the **RiderVoice** mark via synchronized clip-path. Always includes an immediate `Skip` pill and `Replay Intro` trigger. |
| **A2** | **Geometry-Matched Skeleton Shimmer Loading** | `S04`, `S08`, `S13`, `S14`, `S16` | `1.6s ease-in-out infinite` directional sheen | Zero-CLS layout-matched placeholders for network-fetched convoy cards, route metrics, squad lists, and telemetry charts. **Safety Restraint:** Emergency `SOS`, `PTT`, and `Offline Mesh` actions never skeleton-block. |
| **A3** | **Tactile PTT Spring & Concentric Rider Voice Aura** | `S08`, `S09`, `S11` | `180ms cubic-bezier(0.34, 1.56, 0.64, 1)` | Pressing the `88dp` PTT bar compresses smoothly (`scale(0.965)`), illuminates a perimeter voice glow, drives a 7-bar live equalizer, and radiates concentric speaking rings around active riders. |
| **A4** | **Zero-Dead-Air Online ↔ Offline Mesh Capsule Morph** | `S09` (`Room`) | `320ms cubic-bezier(0.22, 1, 0.36, 1)` | Top connectivity capsule morphs smoothly across `Cloud Intercom · 24ms` → `Switching to Mesh (400ms quiet guard)…` → `Offline Mesh · 4/6 · Max 5 Hops` without interrupting the rider with modal popups. |
| **A5** | **Guarded Hold-to-SOS Progress Fill & Emergency Beacon** | `S04`, `S09`, `S11`, `S17` | `1.2s linear` hold fill + `1.0s` radial pulse | Holding any SOS pill fills a high-contrast progress bar before triggering `S17`. Active `15s` SOS and `10s` Crash Detection screens pulse a clear concentric beacon with `tabular-nums` countdowns. |
| **A6** | **Animated Elevation/Speed Spline & Live GPS Replay Puck** | `S13`, `S14`, `S15` | `600ms` SVG `stroke-dashoffset` + `60fps` interpolation | Smooth Bezier curve drawing on telemetry charts and interactive rider puck movement along the GPS polyline with `-10%`, `Play/Pause`, `+10%`, and `1x / 2x / 4x` speed controls. |

---

## 5. Complete Screen Inventory (`S01`–`S18`) & Interactive Control Matrix

| ID | Screen Name | Legacy Route / Component | Interactive Buttons, Inputs & Controls Included (100% Parity) |
|---|---|---|---|
| **`S01`** | **App Launch & Bike Reveal** | `Routes.SPLASH` (`SplashScreen`) | Right-to-left bike sweep + slipstream logo reveal, `Replay Launch Animation`, `Skip to Sign In` (`S02`), `Continue to Ride Hub` (`S04`). |
| **`S02`** | **Sign In** | `Routes.LOGIN` (`LoginScreen`) | `Email or @handle` input, `Password` input, `Show/Hide Password` eye button, inline validation banner, `Sign In` primary CTA (`S04`), `Create an Account` (`S03`), `Offline Mesh Quick Mode (No Signal)` bypass pill (`S10`). |
| **`S03`** | **Create Account** | `Routes.REGISTER` (`RegisterScreen`) | `Back` (`S02`), `Full Name`, `@RiderHandle` (live availability chip), `Bike Model` input, `Email`, `Password` + strength bar + `Show/Hide`, `Create Account` CTA (`S04`), `Already have an account? Sign In` (`S02`). |
| **`S04`** | **Tab 1: `Ride` Hub** | `Routes.HOME` (`HomeScreen`) | Rider Avatar (`S18`), `Helmet & Audio Readiness Pill` (`M03`), Connectivity pill (`Online` / `Mesh Ready`), `Resume Active Convoy` banner (`Rejoin S09`), `Start a Ride` card (`S05`), `Join Ride` card (`M01` / `S07`), `Offline Mesh Intercom` card (`S10` + `Backup Code Ready` chip), `Battery Optimization Tip` (`Fix Background Audio`, `Dismiss X`), guarded `Hold for Emergency SOS` (`S17`). |
| **`S05`** | **Start a Ride Setup** | `Routes.HOST_SETUP` (`HostSetupScreen`) | `Back` (`S04`), `Convoy Name` input + quick chips (`Sunday Twisties`, `Dawn Patrol`, `Alpine Pass`), `Talk Mode` segmented picker (`VOX Hands-Free`, `Push-to-Talk`, `Hybrid`, `Open Mic`), `Auto-Arm Offline Mesh Backup (8h)` switch, `Next: Invite Squad` (`S06`), `Start Immediately` (`S08`). |
| **`S06`** | **Invite Squad to Ride** | `Routes.INVITE_FRIENDS` (`InviteFriendsScreen`) | `Back` (`S05`), `@handle / Name` search input, `Select All Online` pill, selectable Squad rows with status badges, `Share External Invite Link`, `Send Invites & Open Lobby` CTA (`S08`), `Skip` (`S08`). |
| **`S07`** | **Join a Ride** | `Routes.JOIN_ROOM` (`JoinRoomScreen`) | `Back` (`S04`), 6-character `Room Code` boxes, `Paste Code` button, `Recent Convoys` 1-tap rejoin rows, `Join Nearby Offline Mesh Convoy` card (`S10`), `Join Convoy` primary CTA (`S08`). |
| **`S08`** | **Pre-Ride Staging Lobby** | `Routes.LOBBY` (`LobbyScreen`) | `Back / Cancel` (`M05`), Convoy Title + `Room Code` copy chip + `Share Link` + `+ Invite More` (`M02`), Participant Grid (Host crown, mic test aura, host `Remove` action), `Audio & Mic Check` bar (`Mute`, `Audio Route` `M03`, `Test Helmet PTT`), `Offline Mesh Backup Armed` card (`Copy Backup Code`, `Open Mesh Setup` `S10`), `Launch Ride Intercom` (`S09`). |
| **`S09`** | **Active Ride Voice HUD** | `Routes.ACTIVE_RIDE_HUD` (`RoomScreen` + `OfflineAwareRoomHost`) | Top HUD: `Minimize` (docks Floating Mini-Player), Convoy Name, **Morphing Transport Pill** (`Cloud · HD` / `Switching…` / `Offline Mesh · 4/6 · Max 5 Hops` → `M04`), elapsed timer, `Pair/Copy Backup` chip (`S10`), `+ Invite` (`M02`), guarded `Hold SOS` (`S17`). Conditional `Squad SOS Alert Banner` (`Navigate`, `Call`, `Acknowledge`). Rider Matrix with speaking rings & hop badges + `Radar Peek` toggle. Bottom Dock: `Mic Mute`, `Mute Speaker (Deafen)`, `Audio Route` (`M03`), `Talk Mode` (`VOX/PTT/Open`), `Leave / End` (`M05`), and `88dp` **`HOLD TO TALK` / `VOX ACTIVE`** bar. |
| **`S10`** | **Standalone Offline Mesh Setup** | `Routes.OFFLINE_MESH_LOBBY` (`OfflineMeshLobbyScreen`) | `Back` (`S04`), segmented **`Start Convoy (Host)`** / **`Join Convoy`** tabs, `Active Backup Session Status Card` (`Save as Backup & Finish`, `Clear Saved Backup`), `Generate New Pairing Code`, `Copy Pairing Code` (sensitive clipboard), `Open Wi-Fi / Hotspot Settings`, `Join with Pre-Shared Code` input + `Paste Code`, live `Self-Test Readiness` summary (`M06`), `Start / Join Offline Mesh` CTA (`S11`). |
| **`S11`** | **Standalone Offline Mesh HUD** | `Routes.OFFLINE_MESH_HUD` (`OfflineRoomScreen`) | `Minimize`, Convoy/Session ID, `Peers (4/6) · Max 5 Hops` pill (`M04`), `Copy Pairing Code`, `Mesh Self-Test` (`M06`), guarded `Hold SOS` (`S17`), Multi-Hop Rider Cards (`Direct` vs `Relayed · 2 Hops` + packet health), `Hardware Mic Busy — Tap to Retry` demo toggle, `Mic Mute`, `Mute Speaker (Deafen)`, `Audio Route` (`M03`), `Disconnect Mesh` (`M05`), and `88dp` **`HOLD TO TALK`** bar. |
| **`S12`** | **Post-Ride Summary** | `Routes.POST_RIDE_SUMMARY` (`PostRideSummaryScreen`) | Convoy Name + Completion badge, `Total Duration`, `Distance`, `Avg / Top Speed`, `Cloud vs Offline Mesh Uptime` bar, `Squad Mates` row, `Save to Logbook`, `Share Summary Card`, `Watch GPS Replay` (`S15`), `Back to Ride Hub` (`S04`). |
| **`S13`** | **Tab 2: `Routes` Planner & Radar** | `Routes.ROUTE_PLANNER` (`RoutePlannerScreen` + `RadarHudView`) | `Map View` / `Offline Compass-Radar` toggle, `Map Token Gear` (`M07`), `Recenter GPS` button, `Origin` (`Use Current GPS`), `Destination`, `Swap Origin/Destination`, Ride Style pills (`Fastest`, `Scenic`, `Twisty`, `Off-Road`), Elevation spline, `Save Route`, `Export GPX`, `Invert Route`, `Clear`, `Start Ride With Route` (`S05`). |
| **`S14`** | **Tab 4: `Activity` Telemetry** | `Routes.RIDE_STATS` (`RideStatsScreen`) | `Share Stats` button, Timeframe pills (`7D`, `30D`, `Season`, `All`), 4 KPI cards (`Distance`, `Ride Time`, `Top Speed`, `Convoys`), Chart mode pills (`Overview`, `Speed`, `Elevation`), interactive SVG spline, `Recent Rides Logbook` with 1-tap **`Replay`** buttons (`S15`). |
| **`S15`** | **Interactive GPS Ride Replay** | `Routes.RIDE_REPLAY` (`RideReplayScreen`) | `Back to Activity` (`S14`), animated GPS Route Canvas with moving Rider Puck + telemetry callout (`Speed`, `Elevation`, `Time`), interactive **Timeline Scrubber Slider**, `-10%`, `Play / Pause`, `+10%`, and speed selector (`1x`, `2x`, `4x`). |
| **`S16`** | **Tab 3: `Squad` & Invites** | `Routes.SQUAD` + `Routes.INVITES_INBOX` | Segmented **`My Squad (8)`** / **`Invites (2)`** switcher, `+ Add Rider` (`M08`), `@handle` instant search + `Add "@handle"` empty-state CTA, Squad rows (`Invite to Ride`, `Remove` `M09`), Incoming Invite cards (`Accept & Join` `S08`, `Decline`). |
| **`S17`** | **Emergency Safety Suite (`SOS` & Crash)** | `Routes.SOS` + `EmergencyAlertActivity` | Sub-view toggle: **`Manual SOS (15s Countdown)`** vs **`Crash Impact Lock-Screen Alert (10s Countdown)`**. Includes `CANCEL — I AM SAFE` giant CTA, `Send SOS Immediately`, live GPS coordinates, and `Call Emergency (112 / 911)`. |
| **`S18`** | **Tab 5: `You` + Incoming Invite Overlay** | `Routes.ACCOUNT` + `Routes.DEVICE_SETUP` + `Routes.SETTINGS` + `RideInviteActivity` | `Profile & Bike` (`Edit Profile` `M10`), `Theme` (`Light / Dark / System`), `Language` (`System / EN / हिन्दी / मराठी`), `Helmet & Audio Setup` (live mic bar, `Test Audio Loopback`, `Default Talk Mode`, `VOX Sensitivity` slider, `Wind Noise Filter`, `Test Helmet PTT`), `Offline Mesh Settings` (`Enable Offline Mesh`, `Auto-Switch`, `Warm Standby`, `Clear Saved Backup`), `Regional Emergency Number` picker, `Diagnostics Consent` (`M11`), `Check for Updates` (`M11` 8-state OTA), `Sign Out` (`M09`), plus **`S18-Invite` Lock-Screen Incoming Convoy Call** (`Decline`, `Accept & Join Audio`). |

---

## 6. Complete Modal & Bottom Sheet Inventory (`M01`–`M11`)

| ID | Modal / Sheet Name | Trigger Source(s) | Interactive Controls Included |
|---|---|---|---|
| **`M01`** | **Quick Join Convoy Sheet** | `S04` Join Ride card | 6-char Room Code input, `Paste Code`, `Join Nearby Offline Mesh` (`S10`), `Join Convoy` (`S08`), `Close`. |
| **`M02`** | **Invite / Add Riders In-Ride Sheet** | `S08`, `S09` `+ Invite` | Search squad, multi-select riders, `Copy Room Code`, `Share Deep Link`, `Send Invites`, `Close`. |
| **`M03`** | **Audio Route & Helmet Picker Sheet** | `S04`, `S08`, `S09`, `S11` | `Bluetooth Helmet (Cardo Packtalk · SCO Active)`, `Speakerphone`, `Wired / USB-C Headset`, `Earpiece`, `Run Mic & Speaker Check`, `Done`. |
| **`M04`** | **Live Connection & Multi-Hop Topology Sheet** | `S09`, `S11` Transport Pill | Cloud RTT / Jitter / Packet Loss metrics, 6-node visual daisy-hop relay graph (`Max 5 Hops`, `TTL = 5`, port `45454`), `Copy Diagnostic Report`, `Close`. |
| **`M05`** | **Leave vs End Convoy Confirmation Sheet** | `S08`, `S09`, `S11` Leave/End | `Leave Audio (Keep Convoy Active)`, `End Convoy for All Riders (Host)` → `S12`, `Cancel`. |
| **`M06`** | **Offline Mesh Live Self-Test Sheet** | `S10`, `S11` Self-Test pill | Animated 5-step readiness check (UDP `45454` Socket, PBKDF2 120k Key Derivation, Loopback Frame, Audio HAL Ownership, Neighbor Beacon), `Re-run Self-Test`, `Open Wi-Fi Settings`, `Close`. |
| **`M07`** | **Mapbox Token Configuration Modal** | `S13` Map Gear button | Custom Mapbox public token input (`pk.eyJ1...`), `Save & Reload Map`, `Reset to Default`, `Cancel`. |
| **`M08`** | **Add Squad Rider Modal** | `S16` `+ Add Rider` | `@handle` lookup input, live rider preview card, `Send Squad Invite`, `Cancel`. |
| **`M09`** | **Remove Rider / Sign-Out Confirmation Sheet** | `S16` Remove, `S18` Sign Out | Contextual destructive confirmation (`Remove from Trusted Squad` or `Sign Out & Wipe Session + Mesh Keys`), `Confirm`, `Cancel`. |
| **`M10`** | **Edit Rider Dossier & Bike Sheet** | `S18` `Edit Profile` | `Display Name`, `@handle`, `Motorcycle Model`, `Bio / Emergency Note`, `Save Changes`, `Cancel`. |
| **`M11`** | **Privacy Disclosure & 8-State OTA Firmware Updater** | `S18` Diagnostics / Updates | Sub-tab 1: `Diagnostics Telemetry Consent` (`Accept & Enable`, `Decline`). Sub-tab 2: Interactive 8-state OTA Updater (`Checking`, `Up to Date`, `Update Available`, `Downloading 68%`, `Verifying SHA-256`, `Ready to Install`, `Install Permission Required`, `Error / Retry`). |

