# RiderVoice UX Copy & Terminology Dictionary

This document serves as the single source of truth for all user-facing copy, terminology, strings, notifications, emails, and dialogs across RiderVoice (`mobile-app/` and `backend/`).

---

## 1. Core Principles

1. **Clear, Human, and Rider-Focused**: Riders use this app while gearing up or riding. Military, RF, and radio jargon (transceiver, frequency, dispatch, commission, operator, tune, transmit, manifest, dossier, firmware, telemetry) is strictly prohibited.
2. **Sentence Case**: Use standard sentence case ("Start a ride", "Invite friends", "Waiting for the host to start") instead of ALL-CAPS screaming.
3. **Action-Oriented Verbs**: Buttons should be direct and clear ("Create ride", "Start ride", "Send invite").
4. **Human Identity**: Riders are always identified by their chosen name/callsign (e.g. "Ravi", "Alex"), never by raw UID prefixes.
5. **No Mysterious Errors**: Every error maps to a contextual, helpful explanation with an actionable next step ("Try again", "Go home"). Never show raw server exceptions, HTTP status codes, or JSON blobs.

---

## 2. Complete Terminology Mapping

| Old Military / Radio Jargon | New Rider-Friendly Term | Context / Notes |
|---|---|---|
| TRANSCEIVER DASHBOARD / RIDERVOICE | Home | Main home screen |
| CHANNEL FREQUENCY MONITOR | Connection | Audio & server connection indicator |
| TACTICAL CHANNELS & CONTROLS | Quick actions | Home screen action buttons |
| HOST YOUR OWN CONVOY | Start a ride | Primary home action |
| JOIN CONVOY / TUNE INTO CONVOY | Join a ride | Secondary home action |
| CONVOY DISPATCH BENCH / NAME YOUR CONVOY | New ride | Create ride screen title |
| CONVOY CALLSIGN / NAME * | Ride name | Name of the group ride |
| CREATE CONVOY & INVITE SQUAD -> | Create ride | Primary action on setup screen |
| CONVOY MANIFEST | Ride lobby | Staging area before ride starts |
| SYNCHRONIZING MANIFEST... | Loading ride... | Lobby loading state |
| TUNED IN (n) | Joined (n) | Riders currently in the lobby/ride |
| STANDBY / PENDING (n) | Invited (n) | Riders invited but not yet accepted |
| DECLINED (n) | Declined (n) | Riders who declined the invite |
| TUNED IN . WAITING FOR HOST TO START RIDE | Waiting for the host to start | Rider status banner in lobby |
| FAILED TO START: <err> | (Contextual error messages) | Inline error handling |
| ADD CONVOY RIDERS / DISPATCH REINFORCEMENTS | Invite friends | Add riders to ride |
| COMMISSION SQUAD RIDER / ADD RIDER BY CALLSIGN | Add a friend | Friends list screen action |
| YOUR SQUAD / TRUSTED SQUAD | Friends | Friends tab & screens |
| COMMUNICATIONS DIRECTORY / DISPATCH INBOX | Friends / Invites | Navigation tabs |
| LEAVE CONVOY LOBBY? / LEAVE CONVOY VOICE? | Leave this ride? | Confirmation dialogs |
| REMOVE RIDER FROM CONVOY? | Remove <rider name>? | Host rider management |
| LEADER DEPARTURE | The host left | Notification when host leaves |
| DISENGAGE TRANSCEIVER? / SIGN OUT | Log out? | Profile / Settings sign out |
| REGISTER CALLSIGN / OPERATOR REGISTRATION | Create account | Account registration |
| COMMUNICATIONS CONSOLE LOGIN | Log in | Authentication screen |
| OPERATOR DOSSIER / CALIBRATE PROFILE | My profile / Edit profile | User profile |
| RIDER ACCOUNT / APP SETTINGS | Account / Settings | Settings navigation |
| STAY CONNECTED. RIDE UNITED. | Talk to your riding group, hands-free. | App tagline |
| VHF / CB VOICE NETWORK | (Removed) | Obsolete radio references |
| VOX / VOX ACTIVE / VOX Sensitivity | Hands-free talk / Hands-free is on / Hands-free sensitivity | Audio voice activation |
| PTT / Hardware PTT | Push to talk / Headset button | Push to talk mode / button |
| HUD / RIDE HUD / TACTICAL RADAR | Ride screen / Ride screen / Map | Live ride screen and map view |
| CHANNELS & FREQUENCY LOCK / RESET KEY | Ride code / New ride code | 8-character ride join code |
| EMERGENCY SOS TRANSMIT / DISTRESS ALERT | Emergency SOS / Send SOS now | Safety & distress features |
| INCOMING FREQUENCY DISPATCH / CONVOY INVITATION | Ride invite | Push & in-app ride invitation |
| TELEMETRY LOGBOOK / TRANSCEIVER FLIGHT LOG | My rides / Ride history | Past ride logs |
| TELEMETRY REPLAY / GPS REPLAY ACTIVE | Ride replay / Replaying ride | Route playback |
| CONVOY TELEMETRY & DIAGNOSTICS | Network test (beta) | Diagnostic metrics |
| TELEMETRY PRIVACY LEVEL / SQUAD VISIBLE | Who can see my ride / Friends only | Location privacy setting |
| HARDWARE TRANSCEIVER SETUP / HELMET AUDIO | Audio setup / Helmet audio | Audio device router settings |
| DEVICE HARDWARE HEALTH / INITIALIZING RF... | Phone check / Getting ready... | Diagnostic / startup check |
| FIRMWARE UPGRADE / OTA TRANSMISSION | App update / Check for updates | OTA app updater |
| WAYPOINT PLOTTER / ROUTING CHARACTERISTIC | Plan a route / Route type | Route planning preview |
| AUTOMATIC BROADCAST / CONNECTED ON SQUAD NET | Auto share / Connected | Status indicators |
| "Tactical Notes / Bio" | About me | User profile bio |


---

## 3. Offline Mesh Intercom Terminology & Copy

| Context / Element | User-Facing Copy | Description / Intent |
|---|---|---|
| Settings Toggle | Offline mesh intercom (beta) | Primary feature gate toggle in Settings |
| Settings Sub-toggle | Auto-switch when cellular drops | Enables automatic failover to local mesh |
| Mesh Lobby Title | Offline Group | Lobby screen title for offline pairing |
| Mesh Lobby Action | Create offline group | Initiates local Wi-Fi Hotspot mesh group |
| Mesh Lobby Join | Join offline group | Prompts for 6-digit group code to connect |
| Active HUD Header | Offline Mesh Intercom | Title on active HUD when running on mesh |
| Active HUD Signal | Nearby riders (n) | Number of active peer riders in mesh range |
| Hop Count Badge | Direct / (n) hops away | Multi-hop distance indicator |
| Status Pill: Online | LiveKit Online | Connected to central cellular WebRTC SFU |
| Status Pill: Offline | Offline Mesh | Connected via ad-hoc local Wi-Fi / UDP |
| Status Pill: Switching | Switching... | Debounced transition between transports |
| Error: No Network | Wi-Fi or Hotspot required | Instructs user to enable local Wi-Fi/Hotspot |
| Warning: Mic Collision | Mic in use by another app | Indicates Audio HAL hardware conflict |
| Settings Fallback (No Code) | Pair a backup code before the ride (HI: राइड से पहले बैकअप कोड पेयर करें, MR: राइडपूर्वी बेकअप कोड पेअर करा) | Prompts rider to configure pairing code before ride |
| Settings Fallback Action | Pair Backup Code (HI: बैकअप कोड पेयर करें, MR: बेकअप कोड पेअर करा) | Button navigating to offline mesh lobby |
| Room Screen Chip | Offline backup not ready (HI: ऑफलाइन बैकअप तैयार नहीं है, MR: ऑफलाइन बॅकअप तयार नाही) | Non-intrusive status chip during online ride |
| Banner Reason: No Code | Offline mesh unavailable: no backup code (HI: ऑफलाइन मेश अनुपलब्ध: बैकअप कोड नहीं है, MR: ऑफलाइन मेश अनुपलब्ध: बॅकअप कोड नाही) | Displayed on fallback unavailable banner |
| Diagnostics Buffer Drop | Dropped at Buffer (HI: बफ़र पर छोड़ा गया, MR: बफरवर सोडले) | Mesh diagnostics row counting frames dropped at incoming link buffer |
| Banner Reason: No Riders | Offline mesh unavailable: no nearby riders (HI: ऑफलाइन मेश अनुपलब्ध: कोई आस-पास राइडर नहीं, MR: ऑफलाइन मेश अनुपलब्ध: जवळ कोणीही रायडर नाही) | Displayed when total loss occurs with no mesh peers |
| Banner Reason: Handoff Failed | Offline mesh unavailable: handoff failed (HI: ऑफलाइन मेश अनुपलब्ध: हैंडऑफ विफल, MR: ऑफलाइन मेश अनुपलब्ध: हँडऑफ अयशस्वी) | Displayed when mesh transition execution fails |
| Overlay SOS Action | SOS (HI: SOS, MR: SOS) | Emergency SOS trigger on fallback overlay |
