# RiderVoice Backend Services

Backend API and real-time coordination service for RiderVoice, built with Node.js, Express, Prisma ORM, PostgreSQL, and LiveKit Cloud.

---

## 1. Room Lifecycle

```
  [ /api/lobby/create ]
           │
           ▼
     ┌───────────┐
     │   LOBBY   │◄── Riders join via joinCode, shareLink, or invite
     └─────┬─────┘
           │
           │ Host calls [ /api/lobby/:roomRef/start ]
           ▼
     ┌───────────┐
     │  ACTIVE   │◄── Live audio transmission (LiveKit WebRTC + VOX)
     └─────┬─────┘
           │
           │ Host calls [ /api/lobby/:roomRef/end ], sweeper sweeps abandoned room,
           │ or LiveKit webhook triggers room_finished
           ▼
     ┌───────────┐
     │   ENDED   │ (Terminal state: tokens revoked, joinCode cleared, room closed)
     └───────────┘
```

- **LOBBY**: Convoy room created. Host configures details, generates join codes/links, and invites riders. Expired after 6 hours if no riders accept.
- **ACTIVE**: Host has started the ride. LiveKit WebRTC room is active for real-time voice and telemetry. Expired after 12 hours or after 15 minutes of being completely empty.
- **ENDED**: Terminal state. The LiveKit room is deleted after broadcasting `CONVOY_ENDED`. Tokens are revoked. Name-based lookups never return ENDED rooms.

---

## 2. Membership State Machine

```
               ┌─────────────┐
               │   PENDING   │ (Created via invite)
               └──────┬──────┘
                      │
         ┌────────────┴────────────┐
         ▼                         ▼
   ┌───────────┐             ┌───────────┐
   │ ACCEPTED  │             │ DECLINED  │
   └─────┬─────┘             └─────┬─────┘
         │                         │ (Re-joined via explicit link or code)
   ┌─────┴─────┐                   │
   ▼           ▼                   ▼
┌──────┐ ┌───────────┐       ┌───────────┐
│ LEFT │ │  REMOVED  │       │ ACCEPTED  │
└──────┘ └───────────┘       └───────────┘
          (Terminal)
```

- **PENDING**: Rider has been invited by the host or a convoy member.
- **ACCEPTED**: Rider accepted the invite or self-joined via join code or valid link token.
- **DECLINED**: Rider declined the invite. Can only rejoin through an explicit invite link or join code.
- **LEFT**: Rider voluntarily left the lobby or ride.
- **REMOVED**: Host removed the rider. **Terminal state**: REMOVED riders cannot rejoin via any join path.

---

## 3. Token Flow

1. **Token Generation (`tokenService.js`)**:
   - `AccessToken` issued with `roomCreate: false`.
   - `video.room` contains `roomId` (when `ROOM_ID_MODE=true`) or sanitized `roomName` (legacy).
   - TTL: 2 hours (7200 seconds).
2. **Token Refresh (`POST /api/rooms/token/refresh`)**:
   - Available when token has less than 5 minutes remaining.
   - Refreshes credentials without re-entering the lobby.
3. **Revocation**:
   - On ride end or rider removal, join tokens are marked `revokedAt = now()`.

---

## 4. Join Paths

All join paths converge on `joinService.js`:
- **Join Code**: 8-character alphanumeric code generated for the room (`POST /api/lobby/join-code`).
- **Share Link**: One-time or multi-use link (`POST /api/lobby/join-via-token` or web landing `GET /j/:token`). Capped at 5 active links per room.
- **Member Token**: Existing accepted member requesting their token (`POST /api/rooms/room/token`).
- **Unified Endpoint**: `POST /api/lobby/join` with `{ kind: "TOKEN" | "CODE" | "MEMBER", value: "..." }`.

---

## 5. LiveKit Webhook Defense (`/api/livekit/webhook`)

Mounted before `express.json` with a 256KB raw body parser.
- **Event `participant_joined`**: Verifies that the room is not ENDED, metadata `roomId` matches DB, and the participant is the host or an ACCEPTED member. Unauthorized participants are kicked immediately via `RoomServiceClient.removeParticipant`.
- **Event `room_finished`**: Marks the DB room as ENDED, revokes active tokens, clears join code, and triggers diagnostics close.

To configure in LiveKit Cloud:
- Webhook URL: `https://<YOUR-BACKEND-DOMAIN>/api/livekit/webhook`
- Secret: Match `LIVEKIT_API_SECRET` and `LIVEKIT_API_KEY`.

---

## 6. Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | *Required* | PostgreSQL connection string |
| `LIVEKIT_URL` | *Required* | LiveKit Cloud WebSocket/HTTP URL |
| `LIVEKIT_API_KEY` | *Required* | LiveKit Cloud API Key |
| `LIVEKIT_API_SECRET` | *Required* | LiveKit Cloud API Secret |
| `LIVEKIT_WEBHOOK_ENABLED` | `false` | Enable LiveKit webhook ingress verification |
| `ROOM_ID_MODE` | `false` | When `true`, LiveKit rooms are named by UUID instead of display name |
| `LEGACY_CLIENTS_ALLOWED`| `true` | When `false`, enforces `MIN_APP_VERSION` via HTTP 426 |
| `MIN_APP_VERSION` | `0.0.3.14` | Minimum client version allowed |
| `APP_BASE_URL` | `RENDER_EXTERNAL_URL` | Public base URL for HTTPS landing links |
| `APK_DOWNLOAD_URL` | `/download` | Direct APK download link for web visitors |
| `RATE_LIMIT_IP_PER_MIN` | `120` | Max unauthenticated requests per IP per minute |
| `RATE_LIMIT_USER_PER_MIN`| `60` | Max authenticated requests per user per minute |
| `SMTP_HOST` | `smtp.gmail.com` | SMTP relay server for post-ride reports |
| `SMTP_USER` | `""` | SMTP relay user account |
| `SMTP_PASS` | `""` | SMTP relay app password |

---

## 7. Deployment Order on Render

Render web service configuration (`render.yaml`):
1. **Build Command**:
   ```bash
   npm ci && npx prisma generate
   ```
2. **Start Command**:
   ```bash
   npx prisma migrate deploy && node scripts/preflight.js && node src/server.js
   ```
3. **Health Check Path**: `/api/health`
