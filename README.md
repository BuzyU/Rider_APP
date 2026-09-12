# RiderVoice

**Tactical group voice communication, convoy coordination, and in-app OTA firmware updates for motorcycle riders.**

RiderVoice is a Jetpack Compose Android application designed for motorcycle convoys. It enables hands-free push-to-talk (PTT) communication over WebRTC, squad coordination, active ride telemetry, and over-the-air (OTA) application updates. It is backed by a Node.js Express REST API, Prisma PostgreSQL (Supabase), and LiveKit for real-time audio.

> **Latest Release:** [**v0.0.3 (Latest APK)**](https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3)  
> For system architecture and protocol details, see [ARCHITECTURE.md](./ARCHITECTURE.md). For file-level code navigation, see [CODE_DEPTH_STRUCTURE.md](./CODE_DEPTH_STRUCTURE.md).

---

## 🚀 Key Features

- **Operator Dossier (Account Page)** – Tactical profile management displaying call-sign `@handle`, high-resolution avatar with dynamic initial fallback, phone number, assigned bike model, bio, and a live "Calibrate Dossier" editor.
- **In-App OTA Updates** – Native firmware update system with semantic version checking, chunked streaming download with live transfer speed metrics, SHA-256 cryptographic verification, dual downgrade protection, and `FileProvider` package installer integration.
- **Convoy Moderation & Share Links** – Generate 24-hour cryptographic invite links (`ridervoice://join/<token>`), recruit riders from Squad contacts, eject riders, or transfer host duties mid-ride.
- **Push-to-Talk (PTT) Voice Engine** – Low-latency group voice over LiveKit WebRTC. Engineered for helmet intercoms, Bluetooth SCO/A2DP, wired headsets, and handlebar buttons.
- **Tactical Active Ride HUD** – Dark graphite / retro VHF instrument dashboard, live participant audio states, and Mapbox convoy navigation.
- **One-Tap SOS Alerting** – Broadcasts emergency distress alerts with coordinates to convoy participants and registered emergency contacts.
- **Automated CI/CD Releases** – Tag-driven GitHub Actions pipeline with monotonic `versionCode` calculation, hard bucket bounds validation, and automated APK signing.

---

## 📦 Download & Installation

You can download the latest signed APK directly from GitHub Releases:

- 📱 [**Download RiderVoice v0.0.3 APK**](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3/Rider_APP-v0.0.3.apk)
- 🔐 [**SHA-256 Checksum**](https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3/Rider_APP-v0.0.3.apk.sha256)

### Sideloading Instructions:

1. Download `Rider_APP-v0.0.3.apk` onto your Android phone (or transfer via USB/Quick Share).
2. Open your device's **Files** app and tap the APK.
3. If prompted by Android, toggle **"Allow from this source"**.
4. Tap **Install**. Subsequent updates can be downloaded directly from within the app under **Settings → Check for OTA Updates**.

---

## 🛠️ Tech Stack

| Layer                     | Technology                                                               |
| ------------------------- | ------------------------------------------------------------------------ |
| **Mobile Architecture**   | Kotlin 1.9, Jetpack Compose, Material 3, Clean Architecture              |
| **Dependency Injection**  | Dagger Hilt 2.48, KSP                                                    |
| **Networking & HTTP**     | Retrofit 2, OkHttp 4, Gson                                               |
| **Voice & Signaling**     | LiveKit Android SDK 2.25, WebRTC                                         |
| **Mapping & Geospatial**  | Mapbox Maps SDK 11.2 + Compose Extension                                 |
| **Authentication & Push** | Firebase Authentication (Google Sign-In), Firebase Cloud Messaging (FCM) |
| **Local Persistence**     | Room Database 2.6, EncryptedSharedPreferences                            |
| **Backend REST API**      | Node.js, Express 4, Prisma ORM 5                                         |
| **Database**              | PostgreSQL via Supabase                                                  |
| **CI/CD Pipeline**        | GitHub Actions (automated tag-driven release builds)                     |

---

## 📂 Repository Layout

```
Rider_APP/
├── .github/workflows/   # CI/CD workflows: release.yml (APK release) & build.yml (test verification)
├── mobile-app/          # Android app (Kotlin + Jetpack Compose + Material 3)
│   ├── app/
│   │   ├── src/main/    # Android source code, manifests, and resources
│   │   └── signing/     # Fallback debug keystore for consistent release signatures
│   └── gradle.properties
├── backend/             # REST API (Node.js, Express, Prisma ORM)
│   ├── prisma/          # Prisma schema and database migrations
│   └── src/             # Express routes, controllers, and middleware
├── livekit-server/      # Docker Compose setup for local LiveKit server
└── web-app/             # Next.js marketing and waitlist landing page
```

---

## 📱 Mobile App Architecture

### Screens

| Screen                | Route                    | Description                                                                    |
| --------------------- | ------------------------ | ------------------------------------------------------------------------------ |
| `SplashScreen`        | `Routes.SPLASH`          | Authenticates session and routes to Home or Login                              |
| `LoginScreen`         | `Routes.LOGIN`           | Firebase Google Sign-In & credential authentication                            |
| `HomeScreen`          | `Routes.HOME`            | Transceiver dashboard – active convoys, call-sign plate, drawer                |
| `AccountScreen`       | `Routes.ACCOUNT`         | **Operator Dossier** – Call-sign, bio, bike model, high-res avatar, OTA status |
| `HostSetupScreen`     | `Routes.HOST_SETUP`      | Convoy name, origin, destination, and meetup configuration                     |
| `LobbyScreen`         | `Routes.LOBBY`           | Real-time convoy staging lobby with Add Riders bottom sheet & ejection         |
| `InviteFriendsScreen` | `Routes.INVITE_FRIENDS`  | Search and recruit riders by `@handle` from squad list                         |
| `JoinRoomScreen`      | `Routes.JOIN_ROOM`       | Join convoy via 6-digit code or cryptographic deep link token                  |
| `RoomScreen`          | `Routes.ACTIVE_RIDE_HUD` | Active ride telemetry, Mapbox HUD, LiveKit PTT voice, and SOS                  |
| `DeviceSetupScreen`   | `Routes.DEVICE_SETUP`    | Bluetooth headset and audio output routing setup                               |
| `SettingsScreen`      | `Routes.SETTINGS`        | Audio thresholds, HUD night modes, and **Firmware OTA updates**                |
| `SquadScreen`         | `Routes.SQUAD`           | Friends list, pending invitations, and call-sign search                        |
| `RideStatsScreen`     | `Routes.RIDE_STATS`      | Ride logbook telemetry and historical ride replay                              |
| `SosScreen`           | `Routes.SOS`             | Emergency alert broadcast interface                                            |

---

## 🌐 Backend API Reference

Base URL is configured via environment variables. All authenticated routes require a Firebase JWT token in the `Authorization: Bearer <token>` header.

### User & Dossier

- `GET /api/users/me` – Retrieves authenticated operator profile (handle, phone, email, bio, bike model).
- `POST /api/users/profile` – Creates or updates rider dossier details.
- `GET /api/users/search?handle=@callsign` – Public search for riders by handle.
- `POST /api/users/fcm-token` – Registers Firebase Cloud Messaging push token.

### Convoy & Lobby Management

- `POST /api/lobby/create` – Creates a named convoy room.
- `GET /api/lobby/:roomName/status` – Polls live rider statuses (supports solo departure).
- `POST /api/lobby/:roomName/start` – Initiates convoy voice session and issues LiveKit JWT.
- `POST /api/lobby/:roomName/share-link` – Generates a 24-hour cryptographic token and deep link URL.
- `POST /api/lobby/join-via-token` – Validates deep link token and auto-joins rider into the convoy.
- `DELETE /api/lobby/:roomName/riders/:userId` – Host removes and ejects a participant from the convoy.
- `POST /api/lobby/:roomName/transfer-host` – Transfers convoy host privileges to another rider.

---

## 🔄 In-App OTA Update System

RiderVoice features an integrated over-the-air (OTA) update system adhering strictly to Android platform security:

1. **Semantic Version Comparator (`AppVersion.kt`)**:
   Parses arbitrary dot-separated version strings (`major.minor.patch.build`). Accurately resolves version ordering (`1.10.0 > 1.9.0`).
2. **Dual Downgrade Protection**:
   Verifies both semantic `versionName` AND numeric `versionCode` against the installed package to reject downgrade attacks.
3. **Chunked Streaming & Verification**:
   Streams APK updates into app-private cache reporting real-time progress and speed (KB/s). Verifies SHA-256 hashes against release checksums before initiating installation.
4. **Android Package Installer Flow**:
   Exposes the validated APK via Android `FileProvider` (`content://com.ridervoice.fileprovider/...`) and launches `Intent.ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION` and unknown sources permission handling.

---

## 🚢 CI/CD & Publishing Releases

RiderVoice uses tag-triggered GitHub Actions workflows to build, sign, verify, and publish releases.

### Version Code Formula

The build system derives a strictly monotonic 32-bit integer `versionCode` from the semantic tag:
$$\text{versionCode} = (\text{major} \times 10{,}000{,}000) + (\text{minor} \times 100{,}000) + (\text{patch} \times 1{,}000) + \text{build}$$

- `0.0.1` → `1,000`
- `0.0.2` → `2,000`
- `0.0.3` → `3,000`
- `1.10.0` → `11,000,000`

### Hard Bucket Bounds Guard

To prevent silent ordering collisions, both the GitHub Actions workflow and Gradle enforce hard bounds that **fail the build** if limits are exceeded:

- `major`: `0..200`
- `minor`: `0..99`
- `patch`: `0..99`
- `build`: `0..999`

### How to Publish a Release:

```bash
# 1. Commit changes
git add -A
git commit -m "feat: Release v0.0.3"

# 2. Tag semantic version
git tag v0.0.3

# 3. Push to GitHub
git push origin main
git push origin v0.0.3
```

GitHub Actions will automatically build `Rider_APP-v0.0.3.apk`, compute its `.sha256` checksum, and publish a new release to GitHub.

---

## 💻 Local Development Setup

### Prerequisites

- **Android Studio** (Hedgehog 2023.1.1 or newer)
- **JDK 17** (Temurin / Adoptium recommended)
- **Node.js 18+** & **npm**
- **Docker** (optional, for local LiveKit server)

### 1. Run Backend

```bash
cd backend
npm install
npx prisma db push
npm run dev
```

### 2. Run Android App

1. Place your `google-services.json` in `mobile-app/app/`.
2. Connect an Android device or launch an emulator.
3. Build and install:

```powershell
cd mobile-app
.\gradlew clean assembleDebug installDebug
```

---

## 📄 License

All rights reserved © RiderVoice Authors.
