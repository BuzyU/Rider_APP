# RiderVoice Implementation Prompt (copy-paste for the coding AI)

You are the implementation engineer for **RiderVoice** (repo: https://github.com/BuzyU/Rider_APP, baseline commit `66f3137`, version V0.0.3.18). You will edit real code, run builds/tests, and report only what you verified. Earlier audit findings below were obtained by reading source; nothing was run. Re-verify each before changing it.

## 1. Product and architecture

Android group-ride voice app: Kotlin, Jetpack Compose, Hilt, MVVM, LiveKit (WebRTC) for audio, Firebase auth. Backend: Node.js/Express, Prisma, PostgreSQL (possibly behind a transaction pooler), LiveKit server SDK. Web app in `web-app/` (Next.js) consumes some APIs.

Key paths:
- Backend: `backend/src/services/{schemaMigration,roomResolver,tokenService,roomSweeper,membershipService,joinService}.js`, `backend/src/routes/{lobbyRoutes,livekitWebhook,inviteRoutes,roomRoutes}.js`, `backend/prisma/schema.prisma`, `backend/test/*.test.js`.
- Android: `mobile-app/app/src/main/java/com/ridervoice/{network/LiveKitManager.kt, services/VoiceForegroundService.kt, services/FcmService.kt, ui/viewmodels/LobbyViewModel.kt, state/{RoomViewModel,ActiveRideRepository}.kt, utils/LocaleHelper.kt, navigation/NavGraph.kt, audio/*}`, `mobile-app/app/build.gradle`, tests under `mobile-app/app/src/test/java/com/ridervoice/`.
- LiveKit config: `livekit-server/livekit.yaml`. CI: `.github/workflows/{build,release}.yml`.

Identity model: DB `Room.id` UUID is canonical; `Room.name` is display only; `Room.lkRoomName = rv_<id>` is the LiveKit room; participant identity = Firebase UID.

## 2. Rules

1. `git status`, `git log -5`, `git diff` first. Preserve existing changes. No unrelated rewrites, no new dependencies without justification.
2. Fix root causes; coordinate backend and Android contract changes; keep compatibility with older clients.
3. Never print secrets or keystores. Never run destructive DB operations on production. Use isolated test DB/LiveKit.
4. Add a regression test with each fix. Run the real commands (section 6). Investigate every regression you cause.
5. Never call something fixed unless verified; separate automated, emulator, device, and production evidence.
6. Review your own final diff. Update `docs/DEEP_MOBILE_AUDIT_REPORT.md` with actual results.

## 3. Findings to implement (ordered)

### P0-A (RV-001) Prevent LiveKit from re-creating ended rooms
Problem: `livekit-server/livekit.yaml` lacks `room.auto_create: false`; tokens (2h TTL) are not revoked on end, so a stale token may re-create an ended room.
Do:
- Add `room: { auto_create: false }` to `livekit.yaml` (and document the same setting for any hosted LiveKit; if the production server is LiveKit Cloud, document where to set it and mark production as Blocked until the owner confirms).
- Keep `ensureLiveKitRoom` as the only creator; confirm it is never called for ENDED rooms (`issueRoomToken` already rejects ENDED; add a test).
- On ride end, in addition to `deleteRoom`, call `removeParticipant` for known members or rely on webhook; consider shortening token TTL (for example 30 minutes) with the existing refresh endpoint `/api/rooms/token/refresh`; verify the client refreshes before expiry.
Tests: backend: ended room + valid old token => `issueRoomToken` throws 410, `createRoom` not called. Integration (LiveKit test server): join with old token after room delete fails and room list stays empty.
Failure predictions: if `auto_create:false` and the backend `createRoom` fails (LiveKit down), joins fail; keep the current "warn and continue" behaviour only if the client handles join failure with bounded retry. Rooms with `emptyTimeout` expiry will vanish; token requests recreate them via `ensureLiveKitRoom`, which is intended.

### P0-B (RV-002) Fail release builds without real signing
In `mobile-app/app/build.gradle`: when building a release variant without `KEYSTORE_FILE` and without explicit `ALLOW_INSECURE_RELEASE_BUILD=true`, throw `GradleException` (use `gradle.taskGraph.whenReady` to fail only when a release task is requested so debug and test builds still work). Remove `initWith(signingConfigs.debug)` fallback for release. Add a CI step running `apksigner verify --print-certs` and comparing the SHA-256 to a secret/variable. Keep OTA signer verification intact.
Tests: CI job without secrets: `assembleRelease` must fail; with secrets: fingerprint matches.

### P0-C (RV-003) Make schema repair honest and concurrency-safe
In `backend/src/services/schemaMigration.js`:
- Replace session-level `pg_try_advisory_lock/unlock` with a transaction-scoped `pg_try_advisory_xact_lock` inside one `prisma.$transaction`, or run repair on a direct connection (`DIRECT_URL`) that is not pooled. If the lock cannot be established, do not run DDL and report `valid:false`.
- Do not swallow errors in `checkUniqueViolations`; return them as violations or fail.
- Treat failure of any required statement as `valid:false`; keep `failedStatements`. In `server.js`, when the schema is invalid in production, make write routes return 503 (use the existing `setSchemaHealth`) rather than serving with a half schema.
- Keep additive-only DDL; never reintroduce `DELETE`. Duplicates must be reported for manual reconciliation. Write a recovery note in docs (how to dedupe `RideInvite`, `Room` names, join codes).
Tests (extend `test/p0_migration_safety.test.js`, `test/migrations.test.js`): failed statement => invalid; lock not acquired => zero DDL executed; rerun idempotent; violations reported not deleted.

### P1-A (RV-004, RV-005) Strengthen client room-identity verification
In `LiveKitManager.kt`:
- Before `newRoom.connect`, decode the JWT room claim (`JwtRoomClaimDecoder`) and compare to `expectedLkRoomName`; if different, abort without connecting (`RoomFault.ConfirmedRoomMismatch`).
- Remove the JWT-only fallback for rides whose `lkRoomName` is known. If `expectedLkRoomName` is missing, fetch it from the backend (`getLobbyStatus` returns `lkRoomName`) before connecting; if still unavailable (legacy room), allow connection only when backend flags the room as legacy, log `legacy_identity_unverified`, and keep the post-connect check.
- Keep the post-connect check as a second gate. Do not enable remote audio or publish the mic until verified.
- `abortConnection` must remain scoped: disconnect this client's session, stop publishing, clear this rider's active-ride state, show a recoverable message, block auto-rejoin to that room until a new validated token arrives. It must not call end-ride, sign-out, or clear unrelated preferences. Make it idempotent.
Tests (unit, with fake Room): mismatch before connect => `connect` not invoked; null expected + mismatching JWT => abort; canonical => connect; duplicate abort calls produce one set of effects.

### P1-B (RV-006) Webhook `lastEmptyAt`
In `livekitWebhook.js` pass `select: { lastEmptyAt: true }` to both `resolveRoom` calls (or add `lastEmptyAt` to `buildBaseSelect`). Test: participant_joined for a room with `lastEmptyAt` set updates it to null.

### P1-C (RV-014, RV-015) Race and misclassification checks
- Add a test covering accept-invite vs end-ride concurrency (extend `test/end_race.test.js`).
- In `LiveKitManager` (the "server kicked while still member" branch near the `DisconnectReason` handling), do not treat an unresolved kick as a permanent mismatch: re-validate once with the backend; permanent block only if the backend returns a different canonical room.
- Transient network failures must never be classified as mismatch; keep bounded backoff with a terminal state and a test.

### P2-A (RV-007) Wake lock renewal
`VoiceForegroundService.updateWakeLock`: re-acquire before the 3h timeout while CONNECTED/RECONNECTING (use the service's existing loop), or acquire without timeout and release on state exit and `onDestroy`. Test with fake clock.

### P2-B (RV-011) Remove crash-prone `!!`
Replace `!!` in `AudioRouteDecisionEngine.kt`, `LobbyViewModel.kt`, `LiveKitManager.kt`, `AccountViewModel.kt`, `ActiveRideRepository.kt` with safe handling and tests for null/absent device or body.

### P2-C Audio, VOX, Bluetooth (not yet reviewed)
Read `VoxEngine.kt`, `VoxDetector.kt`, `AudioDeviceRouter.kt`, `AudioRouteDecisionEngine.kt` before changing anything. Check: single microphone capture owner (no duplicate `AudioRecord`), noise-floor/threshold/attack/hold/release values and their basis, stuck-open/closed VOX, route state machine (requested vs available vs active input/output), Bluetooth SCO and `setCommunicationDevice` on API 31+, timeouts, headset loss, UI showing the actual active route. Add synthetic-PCM unit tests (steady wind-like noise, speech onset, clipping). Mark Bluetooth/helmet checks as device-only with the matrix: Android 11, 12, 13, 14 devices; wired headset, BT headset (SCO), helmet intercom; connect/disconnect mid-ride, route flapping, reconnect.

### P3 Diagnostics and battery (not yet reviewed)
Compare `docs/diagnostics-payload.md` with `diagnosticService.js`, `diagnosticValidator.js`, `NetworkDiagnosticsCollector.kt`: units, delta calculations, zero vs missing, duplicates, out-of-order sequences, idempotent upload, offline queue, retention. Battery: define workloads (idle, lobby, muted ride, VOX, PTT, BT, reconnect, background, end, sign-out); measure with Android Studio profiler/Perfetto/`dumpsys batterystats` on a physical device; record device, OS, build, duration, network, screen. Do not reduce sampling or voice quality only to improve numbers. Do not invent measurements.

### P4-A (RV-008) Localization
- Move user-facing literals in `ui/` to string resources and add hi/mr translations (about 111 literals; start with `UpdateDialog`, `RoutePlannerScreen`, `PostRideSummaryScreen`, `AccountScreen`, `DiagnosticsDialog`, `SettingsScreen`, `RegisterScreen`, `JoinRoomScreen`, `HostSetupScreen`). Do not externalize logs, IDs, or protocol values.
- Use `AppCompatDelegate.setApplicationLocales` (or the platform per-app locale API) as the single mechanism; add `res/xml/locales_config.xml` and `android:localeConfig` in the manifest; stop mutating `Locale.setDefault` globally; ensure `EmergencyAlertActivity`, `VoiceForegroundService` notifications and `FcmService` notifications use localized resources (send data keys from the backend or map types client-side).
- Preference key `app_language` (`system|en|hi|mr`) persists across force-stop, logout, theme change, update. A language change must not recreate the ride session or disconnect LiveKit.
Tests: `StringResourceParityTest` (placeholder parity and key parity), a test or lint rule that fails on new hardcoded Composable literals, instrumented test switching en->hi->mr checks sidebar, Settings, dialogs, and notification text.

### P4-B Room/Lobby layout repair (not yet inspected)
Render `RoomScreen` and `LobbyScreen` at 360x640, 411x891, 600x960 dp, font scale 1.0/1.3/2.0, light/dark, long ride names/callsigns, 1 and 20 participants, empty/loading/error/reconnecting states, keyboard open. Find the exact constraint cause (nested scroll, `fillMaxSize`/`weight`, fixed heights, missing insets). Required order: header (ride name, host badge, connection status), participant list (scrolling, stable keys), voice controls (PTT, mute, route), primary action, SOS always reachable. No overlap, clipping, or unreachable controls; touch targets at least 48dp (existing PTT 72dp, SOS 52dp). Status shown must match actual LiveKit/backend state. Add Compose UI tests or screenshot tests for the matrix. Do not add new features.

### P4-C Interactive-control inventory
Produce a table (screen, label, control, handler, source of truth, persists, consumed by, status) for every actionable element. Each must be functional, disabled with an explanation, removed, or implemented when required. Check for empty lambdas, local state that resets, success toasts without results, and hardcoded values overriding preferences. Verify each Settings entry against `docs/SETTINGS_CONTRACT.md`: a consumer must read the saved value (`AudioSettingsApplier`, `NetworkDiagnosticsCollector`, etc.).

### P5 Smaller items
- RV-009: raise `compileSdk`/`targetSdk` to the currently required level, re-test foreground service start on Android 14/15 with permissions granted/denied (start only the foreground-service types whose permissions are held); justify or remove `ACCESS_BACKGROUND_LOCATION`.
- RV-010: register `HeadsetSettingsScreen` in `NavGraph.kt` and link from Settings, or delete it.
- RV-012: stop sending display name as the identifier in `LobbyViewModel.startPolling` -> `getJoinToken` once the backend accepts `roomId` alone; keep backward compatibility for old clients.
- RV-013: cache one `RoomServiceClient` in `tokenService.js`/`roomSweeper.js`.
- Also verify (not yet read): `EmergencyNumbers.kt` country detection and honest delivery status; OTA signer verification in `UpdateManager.kt`; `web-app/` contract compatibility.

## 4. Safety constraints
Active ride UI stays calm: no dense diagnostics or forms in the ride screen. SOS must never claim delivery without a backend result. Do not weaken auth, token, or membership checks. Do not remove compatibility with older app versions without a version gate.

## 5. Dependency-ordered plan
P0-A, P0-B, P0-C -> P1-A, P1-B, P1-C -> P2 -> P3 -> P4 -> P5. Reason: data integrity and release safety first, then session identity, then voice reliability, then measurement, then UI.

## 6. Verification commands (from repo scripts)
Backend: `cd backend && npm ci && npm test` (runs `node --test --test-concurrency=1 test/*.test.js`).
Android: `cd mobile-app && ./gradlew testDebugUnitTest assembleDebug` (check the repo's `build.yml` for the exact tasks and any lint step; use the same ones). Instrumented/device tests only where an emulator or device exists.
CI: read `.github/workflows/build.yml` and `release.yml` and run equivalent steps locally where possible.

## 7. Required regression/negative tests (all must exist and pass)
1. Rider A resolves room B instead of A: A aborted, cannot publish to B, A's state cleared; other riders in A and B stay connected; neither room ended.
2. A's failure does not sign out the account or clear unrelated preferences (theme, language).
3. Transient network outage during a valid join is not a mismatch; bounded reconnect, then terminal failure state.
4. Expired token: refresh via authorized path only, never a different room.
5. Stale cached room id cannot override the backend mapping.
6. After a mismatch, retry stays blocked until a newly validated mapping arrives.
7. Duplicate mismatch events and duplicate cleanup are idempotent.
8. Authoritative room-ended event ends only that ride.
9. Two rides with several clients active; a join failure in one leaves the other undisturbed.
10. Ended-room old token cannot create or join any LiveKit room.
11. Reused display name for a new ride gets a new `rv_<uuid>` and is not confused with the old session.
12. Concurrent create with the same `clientRequestId` returns one room; concurrent invite accepts; accept vs end race.
13. Schema repair: failure reporting, lock behaviour, idempotent rerun.

## 8. Physical-device checks (mark clearly in your report)
Bluetooth/helmet routing matrix, VOX in wind/motor noise, battery benchmarks, a 3h+ ride with the screen off, language switch mid-ride, Android 14/15 foreground service start, Room/Lobby on a small phone and a tablet.

## 9. Rollback and recovery
Schema: additive only; keep a pre-change DB snapshot before running repair on any shared environment; document how to reverse each new index/constraint. LiveKit config: keep the previous `livekit.yaml` value documented for rollback. Release: keep the previous signed APK fingerprint on record.

## 10. Final review and report
Inspect your full diff, rerun all tests, list unresolved items honestly, separate automated/emulator/device/production evidence, update the audit report with real results, and state the final repository state. Do not declare production-ready while any P0/P1 item is unverified.

## 11. Final step: retire superseded documents (only after everything above is verified)

Do this last, and only when every P0 and P1 item passes its tests and the audit report has been updated with real results. Never delete documents as part of earlier work.

1. Build a candidate list first. Do not delete yet. Likely candidates to review: `docs/AUDIT_FINDINGS.md` (refers to an older commit), `docs/RIDERVOICE_AI_NAVIGATION_PROMPT.md`, anything under `docs/implementation/` that is a finished plan, and root-level scratch files such as `memory.txt`. For each, record: path, why it is superseded, and what replaces it.
2. Before listing a file as deletable, grep the repo (README, CI workflows, code, other docs) for references to it. If anything links to it, update that link or keep the file.
3. Never delete: `docs/DEEP_MOBILE_AUDIT_REPORT.md` (the updated report), `docs/SETTINGS_CONTRACT.md`, `docs/UX_COPY.md`, `docs/diagnostics-payload.md`, `README.md`, `ARCHITECTURE.md`, `RELEASE_NOTES.md`, or the `.docx` files (`Rider_Voice_Complete_Documentation.docx`, `RiderVoice_Results_Figures_and_Paragraphs.docx`), since the latter feed the owner's academic paper. If a kept document contains outdated claims (for example README statements about a live Mapbox map or mesh fallback), correct it instead of deleting it.
4. Show the owner the candidate list and wait for explicit confirmation of each file. Do not assume approval.
5. Delete only confirmed files with `git rm` in a separate commit titled `docs: remove superseded documents`, so the removal is easy to revert. Do not rewrite history.
6. Decide with the owner whether `docs/AI_IMPLEMENTATION_PROMPT.md` itself should be removed once the work is done. Default: keep it until the owner says otherwise.
7. Re-run the doc link check (grep for broken relative links) and the build/test commands after the commit.
