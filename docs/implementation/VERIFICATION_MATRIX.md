# RiderVoice Verification Matrix

This matrix maps every requirement, finding, and invariant across the mobile app, backend, and database to its verified current status and evidence level.

**Evidence Status Key:**
- `VERIFIED IN CODE`: Source path inspected and defect mechanism confirmed in active checkout.
- `REPRODUCED`: Automated test or runtime experiment demonstrated the defect or behavior.
- `FIXED`: Fix implemented and verified with passing automated tests.
- `NOT REPRODUCIBLE`: Claimed behavior contradicted or proven non-existent by current code/tests.
- `BLOCKED`: Verification blocked by environmental constraint (e.g. production access prohibited).
- `NEEDS DEVICE`: Requires physical Android device, RF/audio hardware, or OEM battery killer environment.
- `NEEDS DEPLOYMENT VERIFICATION`: Requires multi-replica staging or production LiveKit environment.

---

## 1. Core Architectural & Safety Invariants

| ID | Category | Requirement / Finding Summary | Impacted Source Paths | Baseline Evidence | Target Verification / Test | Current State | Blockers / Notes |
|:---|:---|:---|:---|:---|:---|:---|:---|
| **RM-1** | LiveKit Identity | LiveKit room names derived from display name or `ROOM_ID_MODE`; identity logic duplicated. Direct UUID switch unsafe. | `backend/src/services/tokenService.js`, `roomSweeper.js`, `roomResolver.js` | `VERIFIED IN CODE` | Persisted `Room.lkRoomName`, single resolver, staged migration tests | `FIXED` | Addressed in WS-2. Verified by `room_identity.test.js` and `migrations.test.js`. |
| **RM-2** | Create Retries | Ambiguous timeout retry auto-suffixes `Name-2`, `Name-3` causing duplicate convoy creation. | `backend/src/routes/lobbyRoutes.js` (lines 160–169) | `VERIFIED IN CODE` | Concurrency test with identical request ID returns single room without suffix | `FIXED` | Addressed in WS-1. Verified by `backend/test/create_idempotency.test.js`. |
| **RM-3** | Client Idempotency | `clientRequestId` stored in 60s in-memory Map; not durable across restarts; not scoped to user; Android uses volatile `remember`. | `backend/src/routes/lobbyRoutes.js`, `HostSetupScreen.kt`, `HostSetupViewModel.kt` | `VERIFIED IN CODE` | Database-backed `RideCreateRequest`, Android ViewModel state machine | `FIXED` | Addressed in WS-1. Verified by `create_idempotency.test.js` and `HostSetupViewModelTest.kt`. |
| **RM-4** | End / Token Race | Stale active row used by in-flight token issuance can recreate LiveKit room after DB ride marked ENDED. | `backend/src/services/tokenService.js`, `lobbyRoutes.js` | `VERIFIED IN CODE` | Post-call DB status check & compensating delete; deterministic interleaving test | `FIXED` | Addressed in WS-3. Verified by `end_race.test.js` (7/7 pass). |
| **RM-5** | Webhook Defense | Webhook processing defaults to disabled; kick operations must verify exact room ID. | `backend/src/routes/roomRoutes.js`, `server.js` | `VERIFIED IN CODE` | Separate signature verification from transient errors; unit tests | `FIXED` | Addressed in WS-4. Verified in `lobby_diagnostics.test.js` & `p0_fixes.test.js`. |
| **RM-6** | Cleanup Resilience | LiveKit cleanup errors swallowed without durable pending state or retry. | `backend/src/routes/lobbyRoutes.js`, `roomSweeper.js` | `VERIFIED IN CODE` | 404 treated as success; transient failures persisted as pending cleanup jobs | `FIXED` | Addressed in WS-3. Sweeper queue & exponential retry verified. |
| **DB-1** | Schema Safety | Unreviewed destructive `UPDATE` statements running on application startup. | `backend/src/services/schemaMigration.js` (lines 45–68) | `VERIFIED IN CODE` | Read-only startup assertions; isolated migration scripts | `FIXED` | Addressed in WS-5. Safe assertion verified. |
| **DB-2** | Schema Drift | Startup synchronizer attempts ALTER on live database rather than controlled Prisma migrations. | `backend/src/services/schemaMigration.js` | `VERIFIED IN CODE` | Read-only check; separate dry-run reconciliation script | `FIXED` | Addressed in WS-5. `scripts/reconcile_data.js` added. |
| **DB-3** | Active Room Uniqueness | Partial unique index on active room names drops and recreates on every startup. | `backend/src/services/schemaMigration.js` (lines 69–80) | `VERIFIED IN CODE` | Idempotent index definition via standard migration | `FIXED` | Addressed in WS-5. Removed runtime drop/recreate. |
| **SEC-1** | Release Signing | Release build falls back to committed `debug-fallback.keystore` when secrets missing. | `mobile-app/app/build.gradle` (lines 62–70) | `VERIFIED IN CODE` | Build failure on missing signing config for release variant | `FIXED` | Addressed in WS-6. Explicit gate and warnings verified. |
| **SEC-2** | OTA Integrity | OTA download updates must verify SHA-256 and signing certificate before prompt. | `mobile-app/app/src/main/java/com/ridervoice/updater/UpdateManager.kt` | `VERIFIED IN CODE` | Cryptographic signature & hash validation test | `FIXED` | Addressed in WS-6. Cert check in `UpdateManager.kt` verified. |
| **SEC-3** | Request ID Scoping | In-memory create cache lacks user scoping and payload hashing; cross-user cache leakage risk. | `backend/src/routes/lobbyRoutes.js` (line 118) | `VERIFIED IN CODE` | Composite unique `(ownerId, clientRequestId)` and `payloadHash` validation | `FIXED` | Addressed in WS-1. Verified by `create_idempotency.test.js`. |
| **SEC-4** | Denylist State | `prisma.roomDenylist` is referenced in code but missing from `schema.prisma`. | `backend/src/services/joinService.js` (lines 80, 154), `schema.prisma` | `VERIFIED IN CODE` | Persistent denylist schema or formal membership status check | `FIXED / VERIFIED` | Addressed in WS-7. Membership status gating verified. |
| **SEC-5** | Stale Token Reconnection | Removed rider can reconnect using valid unexpired token if LiveKit doesn't receive/enforce kick. | `backend/src/services/membershipService.js`, `tokenService.js` | `VERIFIED IN CODE` | Token refresh verification, immediate participant eviction | `FIXED / VERIFIED` | Addressed in WS-7. Status verification enforced. |
| **INV-1** | Invite State Race | `acceptInvite` lacks optimistic predicate; can overwrite `REMOVED` with `ACCEPTED`. | `backend/src/services/membershipService.js` (lines 196–199) | `VERIFIED IN CODE` | Atomic state transition `where: { id, status: 'PENDING' }` | `FIXED` | Addressed in WS-7. `acceptInvite` optimistic lock verified. |
| **INV-2** | Share Link Cap Race | `selfJoin` creates membership before `roomJoinToken.updateMany` increments use count; no rollback on cap exhaustion. | `backend/src/services/joinService.js` (lines 90–114) | `VERIFIED IN CODE` | Pre-increment seat reservation with compensation on failure | `FIXED` | Addressed in WS-7. Atomic pre-increment verified. |
| **RM-8** | End Route Concurrency | Concurrent `/end` calls execute LiveKit cleanup and broadcasts multiple times without state predicate. | `backend/src/routes/lobbyRoutes.js` | `VERIFIED IN CODE` | Atomic transition `where: { id, status: { not: 'ENDED' } }` | `FIXED` | Addressed in WS-3. Verified in `end_race.test.js`. |
| **WEB-1** | Webhook Status Codes | Transient webhook errors returned as HTTP 401 instead of 5xx, preventing provider retry. | `backend/src/routes/roomRoutes.js` | `VERIFIED IN CODE` | Return 401 only for signature failure; 500 for transient database failure | `FIXED` | Addressed in WS-4. 401 vs 500 separation verified. |
| **CFG-1** | Room ID Mode Endpoint | `roomIdMode` exposed via `/api/app/config`, not `/health`. | `backend/src/server.js` (line 80) | `VERIFIED IN CODE` | Verify `/api/app/config` response schema | `FIXED / VERIFIED` | Endpoint verified in source. |
| **AND-4** | VOX Audio Capture | AudioRecord contention between WebRTC ADM and custom VOX recorder. | `mobile-app/.../VoxEngine.kt` | `VERIFIED IN CODE` | ADM audio callback tap, sample rate awareness | `FIXED / VERIFIED` | Unit tests passing. Physical audio testing `NEEDS DEVICE`. |
| **AND-6** | Audio Device Routing | Bluetooth SCO communication device routing state machine. | `mobile-app/.../AudioDeviceRouter.kt` | `VERIFIED IN CODE` | State machine unit tests, audio focus tests | `FIXED / VERIFIED` | Unit tests passing. Hardware routing `NEEDS DEVICE`. |
| **AND-3** | Battery & Wake Locks | Unconditional wake locks and peer data-channel pings draining battery. | `mobile-app/.../VoiceForegroundService.kt` | `VERIFIED IN CODE` | Gated wake locks, adaptive telemetry throttling | `FIXED / VERIFIED` | Unit tests passing. Runtime consumption `NEEDS DEVICE`. |
| **SOS-1** | SOS Delivery Semantics | Emergency alert marked `SENT` before FCM delivery is confirmed. | `backend/src/routes/emergencyRoutes.js` (line 154) | `VERIFIED IN CODE` | Alert state machine: `INITIATED` -> `DISPATCHED` -> `DELIVERED` | `FIXED` | Addressed in WS-12. Delivery truth verified. |
| **SOS-3** | SOS Partial Delivery | Response reports `sent: true` even if delivered count is 0. | `backend/src/services/notificationService.js` (line 151) | `VERIFIED IN CODE` | Return `sent: false` when delivered === 0, reflect recipient counts in UI | `FIXED` | Addressed in WS-12. Delivery status verified. |
| **DIAG** | Diagnostics Metric Truth | Null vs zero handling for WebRTC stats, duplicate timestamps, export accuracy. | `mobile-app/.../NetworkDiagnosticsCollector.kt`, `backend/src/services/diagnosticService.js` | `VERIFIED IN CODE` | Contract definition in `docs/DIAGNOSTICS_CONTRACT.md` and tests | `FIXED` | Addressed in WS-8. Gate 3 workbook tests passing. |
| **UX-1** | Copy Consistency | Military jargon in remaining UI screens; hardcoded text. | `mobile-app/.../screens/` | `VERIFIED IN CODE` | Alignment with `docs/UX_COPY.md` | `FIXED / VERIFIED` | Copy reconciled across screens. |
| **UX-2** | Unreachable Screens | Headset settings and certain navigation destinations unreachable in graph. | `mobile-app/.../NavGraph.kt` | `VERIFIED IN CODE` | Navigation route audits and UI inventory in `docs/UI_INVENTORY.md` | `FIXED / VERIFIED` | Graph reachability verified. |
| **WS-14** | Cross-Cutting Tests | Cross-cutting contract tests & regression verification | Entire codebase | `VERIFIED IN CODE` | All backend and mobile unit tests pass with zero failures | `FIXED` | 88 backend tests and 122 mobile tests passing. Version bumped to V0.0.3.18. |

---

## 2. Test & Build Execution Matrix

| Test Suite / Command | Execution Target | Outcome | Duration / Details |
|:---|:---|:---|:---|
| `node --test test/*.test.js` | Backend Unit & Integration Tests | **PASS (88/88 passed, 0 failed)** | 34.6s, 25 suites, 100% success rate |
| `./gradlew.bat testDebugUnitTest` | Android Mobile Unit Tests | **PASS (122/122 passed, 0 failed)** | 1m 1s, 37 tasks executed/up-to-date |
| `npm run build` (web-app) | Next.js Web App Production Build | **PASS (0 errors, 14 static pages)** | 15.2s, TypeScript & page compilation clean |
| `git status --short` | Git Working Tree | **CLEAN ON BRANCH** | Branch: `fix/room-identity-and-audit`, untracked inputs preserved |
