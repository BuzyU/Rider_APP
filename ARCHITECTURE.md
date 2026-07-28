# Architecture – Rider Voice

This document describes the current technical architecture of the Rider Voice project. It covers the high-level system design, the internal structure of each major component, and the key data flows.

---

## 1. System Overview

```mermaid
graph TB
    subgraph Mobile["📱 Android App"]
        App["Rider Voice App\n(Kotlin / Compose)"]
    end

    subgraph Backend["☁️ Backend (Render.com)"]
        API["Express REST API\n(Node.js)"]
        Prisma["Prisma ORM"]
    end

    subgraph Data["🗄️ Data Layer"]
        DB[("PostgreSQL\n(Supabase)")]
    end

    subgraph Auth["🔐 Firebase"]
        FirebaseAuth["Firebase Auth\n(Google Sign-In)"]
        FCM["Firebase Cloud\nMessaging (FCM)"]
    end

    subgraph Voice["🎙️ Voice Layer"]
        LK["LiveKit Server\n(WebRTC)"]
    end

    subgraph Web["🌐 Web (Vercel)"]
        NextJS["Marketing Site\n(Next.js 14)"]
    end

    App -- "REST + Firebase ID Token" --> API
    App -- "WebRTC / WS" --> LK
    App -- "Google Sign-In" --> FirebaseAuth
    App -- "Push Notifications" --> FCM

    API -- "Prisma queries" --> Prisma
    Prisma --> DB
    API -- "Admin SDK" --> FirebaseAuth
    API -- "FCM Admin SDK" --> FCM
    API -- "Issues JWT tokens" --> LK
```

---

## 2. Android App Architecture

The app follows **MVVM + Clean Architecture** with Hilt for dependency injection.

```mermaid
graph TB
    subgraph UI["UI Layer (Compose)"]
        Screens["Screens\n(20 composables)"]
        VMs["ViewModels\n(Hilt-injected)"]
    end

    subgraph Domain["Domain / Data Layer"]
        Repos["Repositories\n(SquadRepo, RideRepo, AuthRepo)"]
        ApiSvc["ApiService\n(Retrofit interface)"]
        LKMgr["LiveKitManager\n(WebRTC session)"]
        RoomDB["Room Database\n(local ride storage)"]
        SecPrefs["SecurePreferences\n(EncryptedSharedPrefs)"]
    end

    subgraph Services["Android Services"]
        VoiceSvc["VoiceForegroundService\n(persistent audio)"]
        LocSvc["LocationService\n(GPS tracking)"]
        RideRec["RideRecorder\n(session + events)"]
        MsgSvc["TacticalMessagingService\n(FCM handler)"]
        Watchdog["ServiceWatchdog\n(crash recovery)"]
    end

    subgraph Audio["Audio Engine"]
        AudioRouter["AudioDeviceRouter\n(BT / wired / speaker)"]
        VoxEngine["VoxEngine\n(VAD + PTT logic)"]
        PTT["HardwarePTTManager\n(headset button)"]
        AudioFocus["AudioFocusManager"]
        Mesh["LocalMeshVoiceEngine\n(Wi-Fi Direct fallback)"]
        Handoff["NetworkHandoffManager\n(cellular ↔ Wi-Fi)"]
    end

    Screens -- "observe state" --> VMs
    VMs -- "call" --> Repos
    Repos -- "HTTP" --> ApiSvc
    Repos -- "query" --> RoomDB
    Repos -- "read/write" --> SecPrefs
    VMs -- "connect/disconnect" --> LKMgr
    LKMgr -- "audio events" --> VoiceSvc
    VoiceSvc -- "controls" --> AudioRouter
    AudioRouter --> VoxEngine
    AudioRouter --> PTT
    AudioRouter --> AudioFocus
    AudioRouter --> Mesh
    AudioRouter --> Handoff
    LocSvc -- "coordinates" --> RideRec
    RideRec -- "persist" --> RoomDB
    MsgSvc -- "invite push" --> Screens
    Watchdog -- "monitors" --> VoiceSvc
    Watchdog -- "monitors" --> LocSvc
```

---

## 3. Navigation Flow

```mermaid
flowchart TD
    Splash([SplashScreen])

    Splash -->|"not logged in"| Login([LoginScreen])
    Splash -->|"logged in"| Home([HomeScreen])

    Login -->|"sign-in success"| Home

    Home --> HostSetup([HostSetupScreen])
    Home --> InvitesInbox([InvitesInboxScreen])
    Home --> Squad([SquadScreen])
    Home --> Settings([SettingsScreen])
    Home --> RoutePlanner([RoutePlannerScreen])
    Home --> RideStats([RideStatsScreen])
    Home --> DeviceSetup([DeviceSetupScreen])

    subgraph "HOST PATH"
        HostSetup -->|"convoy created"| InviteFriends([InviteFriendsScreen])
        InviteFriends -->|"invites sent"| Lobby([LobbyScreen])
        Lobby -->|"start ride"| DeviceSetup
    end

    subgraph "JOIN PATH"
        InvitesInbox -->|"invite accepted"| DeviceSetup
    end

    DeviceSetup -->|"ready"| RoomScreen([RoomScreen\nActive Ride HUD])
    RoomScreen -->|"leave ride"| Home
    RoomScreen --> SOS([SosScreen])
    RoomScreen --> PostRide([PostRideSummaryScreen])
```

---

## 4. Convoy Creation & Join Flow (Sequence)

```mermaid
sequenceDiagram
    actor Host
    actor Joiner
    participant App as Android App
    participant API as Backend API
    participant DB as PostgreSQL
    participant FCM as Firebase FCM
    participant LK as LiveKit

    Host->>App: Names convoy, taps "Create"
    App->>API: POST /api/lobby/create
    API->>DB: INSERT Room
    DB-->>API: roomId
    API-->>App: { roomId, convoyName }

    Host->>App: Selects friends, taps "Invite"
    App->>API: POST /api/invites/invite (x N)
    API->>DB: INSERT RideInvite (PENDING)
    API->>FCM: Send push notification to each invitee
    FCM-->>Joiner: Push notification

    Joiner->>App: Opens invite, taps "Accept"
    App->>API: POST /api/invites/respond { status: ACCEPTED }
    API->>DB: UPDATE RideInvite status=ACCEPTED

    Host->>App: Polls LobbyScreen
    App->>API: GET /api/lobby/:roomName/status
    API->>DB: Query invites with status
    API-->>App: { acceptedCount, invites[] }

    Host->>App: Taps "Start Ride"
    App->>API: POST /api/lobby/:roomName/start
    API->>LK: Validate & issue JWT token (host)
    API-->>App: { token, livekitUrl }
    App->>LK: Connect (WebRTC)

    Joiner->>App: Taps "Join" after accepting
    App->>API: POST /api/lobby/join-token
    API->>DB: Verify ACCEPTED invite
    API->>LK: Issue JWT token (joiner)
    API-->>App: { token, livekitUrl }
    App->>LK: Connect (WebRTC)

    Note over Host,Joiner: Both are now in the LiveKit room — PTT voice is live
```

---

## 5. Audio Device Routing

```mermaid
flowchart LR
    subgraph Inputs["Audio Input Sources"]
        BT["Bluetooth\n(A2DP / HFP)"]
        Wired["Wired Headset\n(3.5mm / USB-C)"]
        PTTBtn["Hardware PTT\nButton"]
        Speaker["Device Speaker\n(fallback)"]
    end

    AudioRouter["AudioDeviceRouter"]
    VoxEngine["VoxEngine\n(VAD / PTT)"]
    AudioFocus["AudioFocusManager"]

    BT --> AudioRouter
    Wired --> AudioRouter
    PTTBtn --> AudioRouter
    Speaker --> AudioRouter

    AudioRouter --> VoxEngine
    AudioRouter --> AudioFocus

    VoxEngine -->|"audio stream"| LKManager["LiveKitManager\n(WebRTC track)"]

    subgraph Fallback["Connectivity Fallback"]
        Handoff["NetworkHandoffManager\n(cellular ↔ Wi-Fi)"]
        Mesh["LocalMeshVoiceEngine\n(Wi-Fi Direct P2P)"]
    end

    LKManager -->|"no internet"| Handoff
    Handoff -->|"no cellular"| Mesh
```

---

## 6. Backend Structure

```mermaid
graph TB
    Client["Android App\n(Retrofit)"]

    subgraph Express["Express Server"]
        MW_Rate["Rate Limiter"]
        MW_Auth["Auth Middleware\n(verify Firebase ID token)"]
        MW_Err["Error Middleware"]

        subgraph Routes["Route Handlers"]
            Health["/api/health"]
            Rooms["/api/rooms\n(legacy token)"]
            Users["/api/users\n(FCM token, profile)"]
            Friends["/api/friends\n(list, request)"]
            Invites["/api/invites\n(send, respond, list)"]
            Lobby["/api/lobby\n(create, status, start, join-token)"]
            Rides["/api/rides\n(history)"]
        end

        subgraph Services["Services"]
            TokenSvc["tokenService\n(LiveKit JWT)"]
            NotifSvc["notificationService\n(FCM push)"]
        end
    end

    Client --> MW_Rate
    MW_Rate --> MW_Auth
    MW_Auth --> Routes

    Lobby --> TokenSvc
    Rooms --> TokenSvc
    Invites --> NotifSvc

    Routes --> Prisma["Prisma ORM"]
    Prisma --> DB[("PostgreSQL\n(Supabase)")]
```

---

## 7. Data Model (Entity Relationship)

```mermaid
erDiagram
    User {
        string id PK "Firebase UID"
        string email
        string phone
        string handle "e.g. @NightRider99"
        string displayName
        string bikeModel
        string bio
        datetime createdAt
    }

    DeviceToken {
        string id PK
        string userId FK
        string token
        string platform "android / ios"
        datetime updatedAt
    }

    Friendship {
        string id PK
        string requesterId FK
        string addresseeId FK
        enum status "PENDING/ACCEPTED/BLOCKED"
        datetime createdAt
    }

    Room {
        string id PK
        string name "convoy name (unique)"
        string ownerId FK
        datetime createdAt
    }

    RideInvite {
        string id PK
        string roomId FK
        string inviterId FK
        string inviteeId FK
        enum status "PENDING/ACCEPTED/DECLINED"
        datetime createdAt
    }

    RideSession {
        string id PK
        string riderId FK
        datetime startTime
        datetime endTime
        float distanceKm
        string privacyState "PRIVATE/SQUAD/PUBLIC"
        string routeJson "GeoJSON LineString"
    }

    ConvoyEvent {
        string id PK
        string rideId FK
        string type "STOP/RECONNECT/SPLIT/HIGH_SPEED"
        float lat
        float lng
        datetime timestamp
    }

    User ||--o{ DeviceToken : "has"
    User ||--o{ Friendship : "sends"
    User ||--o{ Friendship : "receives"
    User ||--o{ RideInvite : "invites"
    User ||--o{ RideInvite : "receives"
    User ||--o{ RideSession : "records"
    Room ||--o{ RideInvite : "has"
    RideSession ||--o{ ConvoyEvent : "logs"
```

---

## 8. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **LiveKit for voice** | WebRTC SFU purpose-built for low-latency group audio; provides client SDK for Android |
| **Firebase Auth as identity layer** | Handles Google OAuth and phone OTP; issues ID tokens the backend can verify with Admin SDK |
| **Prisma + PostgreSQL (Supabase)** | Type-safe ORM; Supabase gives managed Postgres with row-level security as a future option |
| **Token issued server-side only** | LiveKit tokens are never generated on-device; server validates invite status before issuing |
| **Lobby before LiveKit connect** | Separates the social invite flow from the audio session — saves LiveKit capacity and avoids orphaned sessions |
| **Room database for ride recording** | Rides are recorded locally first (works offline); can be synced to backend later |
| **Audio device abstraction** | `AudioDeviceRouter` decouples audio source selection from the PTT/VAD engine, making it easier to add new device types |
| **Foreground service for voice** | Keeps the audio session alive when the screen is off or the app is in the background |
| **Wi-Fi Direct fallback** | `LocalMeshVoiceEngine` allows local P2P audio if internet connectivity drops during a ride |
