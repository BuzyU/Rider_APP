# Rider Voice

**Group voice communication and ride coordination for motorcycle convoys.**

Rider Voice is an Android app that lets a group of riders create a named convoy, invite friends, and communicate hands-free over push-to-talk (PTT) voice during a ride. It is backed by a Node.js REST API and LiveKit for real-time audio.

> For a deep-dive into the system design, see [ARCHITECTURE.md](./ARCHITECTURE.md).

---

## What It Does

- **Host a convoy** – give the convoy a name, invite friends by handle, wait in a lobby until they accept, then start the ride.
- **Join a convoy** – receive a push notification invite, accept it, go through device setup, and enter the active-ride HUD.
- **Push-to-Talk voice** – low-latency group audio over LiveKit WebRTC. Supports Bluetooth, wired headsets, and hardware PTT buttons.
- **Live map** – Mapbox-powered map showing your position during a ride (location is only shared within the convoy room).
- **SOS** – one-tap emergency alert.
- **Ride history** – local ride sessions stored in Room DB; distance, duration, and convoy events are saved.
- **Squad management** – add friends by handle, manage your contact list.

---

## Repository Layout

```
Rider_APP/
├── mobile-app/          # Android app (Kotlin + Jetpack Compose)
├── backend/             # REST API (Node.js / Express + Prisma)
├── livekit-server/      # Self-hosted LiveKit config (Docker)
├── web-app/             # Marketing / waitlist site (Next.js)
└── render.yaml          # Render.com deployment config for the backend
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| Android | Kotlin 1.9, Jetpack Compose, Material 3 |
| DI | Hilt 2.48 |
| Navigation | Navigation Compose |
| Networking | Retrofit 2 + OkHttp |
| Voice (WebRTC) | LiveKit Android SDK 2.25 |
| Maps | Mapbox Maps 11.2 + Compose extension |
| Auth | Firebase Auth (Google Sign-In + FCM) |
| Local DB | Room 2.6 |
| Crash reporting | Firebase Crashlytics |
| Backend | Node.js, Express 4 |
| ORM / DB | Prisma 5 + PostgreSQL (Supabase) |
| LiveKit signalling | livekit-server-sdk 2.8 |
| Web (marketing) | Next.js 14, TypeScript |
| Deployment | Render.com (backend), Vercel (web-app) |


### Mobile Development
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Latest-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![Material 3](https://img.shields.io/badge/Material%203-Google-757575?style=for-the-badge&logo=materialdesign&logoColor=white)
![Hilt](https://img.shields.io/badge/Hilt-2.48-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Navigation Compose](https://img.shields.io/badge/Navigation%20Compose-Jetpack-4285F4?style=for-the-badge&logo=android&logoColor=white)

### Networking
![Retrofit](https://img.shields.io/badge/Retrofit-2-48B983?style=for-the-badge)
![OkHttp](https://img.shields.io/badge/OkHttp-Latest-009688?style=for-the-badge)

### Real-Time Communication
![LiveKit](https://img.shields.io/badge/LiveKit%20Android%20SDK-2.25-1E293B?style=for-the-badge)
![LiveKit Server SDK](https://img.shields.io/badge/livekit--server--sdk-2.8-1E293B?style=for-the-badge)

### Maps
![Mapbox](https://img.shields.io/badge/Mapbox%20Maps-11.2-000000?style=for-the-badge&logo=mapbox&logoColor=white)
![Compose Extension](https://img.shields.io/badge/Compose%20Extension-Mapbox-4285F4?style=for-the-badge)

### Firebase
![Firebase Auth](https://img.shields.io/badge/Firebase%20Auth-Google%20Sign--In%20%2B%20FCM-FFCA28?style=for-the-badge&logo=firebase&logoColor=black)
![Firebase Crashlytics](https://img.shields.io/badge/Firebase%20Crashlytics-Latest-FFCA28?style=for-the-badge&logo=firebase&logoColor=black)

### Local Database
![Room](https://img.shields.io/badge/Room-2.6-3DDC84?style=for-the-badge&logo=android&logoColor=white)

### Backend
![Node.js](https://img.shields.io/badge/Node.js-Latest-339933?style=for-the-badge&logo=node.js&logoColor=white)
![Express.js](https://img.shields.io/badge/Express-4-000000?style=for-the-badge&logo=express&logoColor=white)
![Prisma](https://img.shields.io/badge/Prisma-5-2D3748?style=for-the-badge&logo=prisma&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Supabase-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![Supabase](https://img.shields.io/badge/Supabase-Database-3ECF8E?style=for-the-badge&logo=supabase&logoColor=white)

### Web
![Next.js](https://img.shields.io/badge/Next.js-14-000000?style=for-the-badge&logo=next.js&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-Latest-3178C6?style=for-the-badge&logo=typescript&logoColor=white)

### Deployment
![Render](https://img.shields.io/badge/Render-Backend-46E3B7?style=for-the-badge&logo=render&logoColor=white)
![Vercel](https://img.shields.io/badge/Vercel-Web%20App-000000?style=for-the-badge&logo=vercel&logoColor=white)

---

## Mobile App – Package Structure

```
com.ridervoice/
├── audio/               # AudioDeviceRouter, VoxEngine, PTT, Bluetooth, Wi-Fi Direct
├── data/                # Repositories: Squad, Ride
├── di/                  # Hilt modules
├── errors/              # Typed error handling
├── models/              # Shared data models / DTOs
├── monitoring/          # Performance / thermal monitoring
├── navigation/          # NavGraph, Routes
├── network/             # ApiService (Retrofit), LiveKitManager, NetworkResilienceManager
├── participants/        # Participant state management
├── permissions/         # Runtime permission helpers
├── recovery/            # Reconnect / service watchdog
├── security/            # SecurePreferences, AuthRepository
├── services/            # Foreground services: Voice, Location, RideRecorder, TacticalMessaging
├── state/               # Shared UI state holders
├── sync/                # Offline sync helpers
├── ui/
│   ├── screens/         # All Compose screens (20 screens)
│   ├── components/      # Reusable Compose components
│   ├── viewmodels/      # ViewModels (AuthViewModel, RideStatsViewModel, …)
│   ├── theme/           # Material 3 theme, colours, typography
│   └── animations/      # Custom animations
└── utils/               # OEM battery warning, misc helpers
```

### Screens

| Screen | Purpose |
|--------|---------|
| `SplashScreen` | Checks Firebase auth state and routes to Login or Home |
| `LoginScreen` | Firebase Google Sign-In |
| `HomeScreen` | Dashboard – start/join ride, squad, settings, history |
| `HostSetupScreen` | Host names a convoy and sets trip details |
| `InviteFriendsScreen` | Host searches friends by handle and sends invites |
| `LobbyScreen` | Host sees live accept/decline status before starting |
| `InvitesInboxScreen` | Joiner sees pending invites; accepts/declines |
| `DeviceSetupScreen` | Audio device selection before entering the ride |
| `RoomScreen` | Active ride HUD – PTT, live map, participants, SOS |
| `SosScreen` | Emergency alert screen |
| `SquadScreen` | Friends list management |
| `SettingsScreen` | App settings, sign-out |
| `RoutePlannerScreen` | Plan a route on the map before riding |
| `RideStatsScreen` | Ride history and stats |
| `PostRideSummaryScreen` | Summary shown after leaving a room |
| `HeadsetSettingsScreen` | Bluetooth / wired headset configuration |

---

## Backend – API Routes

Base URL is set in `mobile-app` via the `BASE_URL` environment variable. The backend is deployed to Render.com.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/health` | Health check |
| POST | `/api/users/fcm-token` | Register FCM device token |
| GET | `/api/friends/list/:userId` | Get friend list |
| POST | `/api/friends/request` | Send friend request |
| GET | `/api/invites/invites/:userId` | Get pending ride invites |
| POST | `/api/invites/invite` | Host sends a ride invite |
| POST | `/api/invites/respond` | Joiner accepts / declines an invite |
| POST | `/api/lobby/create` | Host creates a named convoy room |
| GET | `/api/lobby/:roomName/status` | Host polls invite statuses (lobby) |
| POST | `/api/lobby/:roomName/start` | Host starts the ride → returns LiveKit token |
| POST | `/api/lobby/join-token` | Joiner gets a LiveKit token after accepting |
| POST | `/api/rooms/room/token` | Legacy quick-join token endpoint |
| GET | `/api/rides/history` | Authenticated user's ride history |

All routes except `/api/health` require a Firebase ID token in the `Authorization: Bearer <token>` header.

---

## Database Schema (Prisma / PostgreSQL via Supabase)

| Model | Key Fields |
|-------|-----------|
| `User` | `id` (Firebase UID), `handle`, `displayName`, `bikeModel` |
| `DeviceToken` | `userId`, `token`, `platform` |
| `Friendship` | `requesterId`, `addresseeId`, `status` (PENDING / ACCEPTED / BLOCKED) |
| `Room` | `name` (convoy name, unique), `ownerId` |
| `RideInvite` | `roomId`, `inviterId`, `inviteeId`, `status` (PENDING / ACCEPTED / DECLINED) |
| `RideSession` | `riderId`, `distanceKm`, `routeJson` (GeoJSON), `privacyState` |
| `ConvoyEvent` | `rideId`, `type` (STOP / RECONNECT / SPLIT / HIGH_SPEED), `lat`, `lng` |

---

## LiveKit

The `livekit-server/` directory contains a Docker Compose + config for a self-hosted LiveKit instance (used during local development). In production, a hosted LiveKit cloud instance is used.

The backend generates short-lived JWT access tokens using `livekit-server-sdk`. Tokens are issued **only** after a valid invite flow – joiners must have an `ACCEPTED` invite before receiving a token.

---

## Web App

A Next.js 14 marketing and waitlist site located in `web-app/`. It includes a hero section, feature overview, roadmap, and a waitlist form. It is separate from the backend API and has its own deployment.

---

## Getting Started

### Prerequisites

- **Android Studio** (Hedgehog or newer)
- **JDK 17**
- **Node.js 18+**
- **PostgreSQL** instance (or a Supabase project)
- A **Firebase project** with Google Sign-In and FCM enabled
- A **LiveKit** server or cloud account
- A **Mapbox** account and public token

### 1. Backend

```bash
cd backend
cp .env.example .env
# Fill in DATABASE_URL, LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET, FIREBASE credentials
npm install
npx prisma migrate deploy
npm start
```

### 2. Android App

1. Place your `google-services.json` in `mobile-app/app/`.
2. Open `mobile-app/` in Android Studio.
3. In `mobile-app/app/src/main/res/values/strings.xml` (or `local.properties`), set:
   - `BACKEND_BASE_URL` – URL of the running backend
   - `MAPBOX_ACCESS_TOKEN` – your Mapbox public token
4. Run on a device or emulator:
   ```powershell
   cd mobile-app
   .\gradlew clean assembleDebug installDebug
   ```

### 3. LiveKit (local dev only)

```bash
cd livekit-server
docker compose up
```

### 4. Web App

```bash
cd web-app
npm install
npm run dev
```

---

## Environment Variables

### Backend (`.env`)

| Variable | Description |
|----------|-------------|
| `DATABASE_URL` | PostgreSQL connection string |
| `LIVEKIT_URL` | LiveKit server WebSocket URL |
| `LIVEKIT_API_KEY` | LiveKit API key |
| `LIVEKIT_API_SECRET` | LiveKit API secret |
| `FIREBASE_PROJECT_ID` | Firebase project ID |

### Android

| Variable | Where |
|----------|-------|
| `google-services.json` | `mobile-app/app/` |
| Backend URL | hardcoded in `di/AppModule.kt` or `local.properties` |
| Mapbox token | `local.properties` as `MAPBOX_ACCESS_TOKEN` |

---

## Contributing

1. Fork the repository.
2. Create a feature branch: `git checkout -b feature/my-feature`.
3. Commit your changes.
4. Open a pull request against `main`.

Please keep `google-services.json`, `.env`, and `local.properties` out of version control (they are already in `.gitignore`).

---

## License

This project is currently unlicensed. All rights reserved by the authors.
