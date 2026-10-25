# RiderVoice Implementation Prompt — AI 3 (Coding Agent)

**Repository:** https://github.com/BuzyU/Rider_APP
**Reported audit baseline:** `main` at `c7ba75a` / `v0.0.3.17`
**Primary report:** user may supply `DEEP_MOBILE_AUDIT_REPORT.md` at repository root; the canonical working copy is `docs/DEEP_MOBILE_AUDIT_REPORT.md` after safe preflight reconciliation.
**Mode:** Implement in a working branch, in bounded workstreams, with regression tests and durable checkpoints.
**Critical rule:** This prompt is not authorization to deploy, publish, merge, change production data, or run destructive SQL.

You are acting as a senior Android/Kotlin/Compose, Node/Express/Prisma/Postgres, LiveKit, mobile audio, and application-security engineer. The goal is to fix verified root causes in RiderVoice without breaking existing riders or inventing successful test results. You are expected to inspect the current repository, test assumptions, implement in small independent stages, and report honest evidence.

This prompt is intentionally staged to avoid a long single-session attempt timing out. **Do not try to audit, implement, and validate the entire repository in one uninterrupted pass.** Finish each workstream, save its evidence and next-step checkpoint, then continue. If your session is about to end, update the checkpoint before stopping; do not restart from scratch.

---

## 0. Non-negotiable safety rules

1. At the start, run and record:
   ```bash
   git status --short
   git branch --show-current
   git log -5 --oneline
   git diff --stat
   ```
   Preserve all existing user changes and untracked files. Do not reset, clean, stash-pop, overwrite, or delete user work.
2. Create a working branch named `fix/room-identity-and-audit` only if it does not already exist. If it exists, inspect it before using it. Never push to `main`, tag, release, merge, deploy, or publish artifacts.
3. Do not access or modify production systems/data unless the user explicitly authorized a supported operation. This prompt does not grant that authorization. Do not print `.env`, secrets, keystore values, access tokens, Firebase service accounts, join codes, raw rider diagnostics, emails, or personal data.
4. Never run destructive SQL against production. Run migration/reconciliation tests only on isolated local/test Postgres. The reconciliation utility must default to dry-run and require an explicit apply flag; do not use that flag against production.
5. Re-verify all baseline findings in the actual current checkout before editing. Paths and line numbers may have changed. If the repository commit differs from the stated baseline, record it and re-evaluate the finding.
6. Evidence categories must be kept distinct:
   - `VERIFIED IN CODE`: source path inspected; not necessarily reproduced.
   - `REPRODUCED`: deterministic automated test or controlled runtime reproduction actually succeeded.
   - `SUSPECTED`: plausible but unproven.
   - `NOT EXAMINED`: not investigated enough.
   - `BLOCKED`: cannot test; exact reason included.
   - `NEEDS DEVICE` / `NEEDS DEPLOYMENT VERIFICATION`: requires hardware or deployed configuration.
7. A finding marked suspected must not automatically become a code change. Build a focused failing test or controlled reproduction first where feasible. If it cannot be reproduced, document that and do not make an arbitrary behavior change unless there is a separate, evidence-backed safety reason.
8. Never claim a fix is complete unless the relevant command/test was run and passed. Separate unit, integration, build/lint, emulator, physical-device, staging, and production-configuration checks.
9. Do not add dependencies without a written, specific need. Do not reformat/rewrite unrelated code. Preserve existing business behavior and public API contracts unless a required change is documented, tested, and backward-compatible.
10. Do not create mock production data or place sample values in real user-facing ride paths to make a screen look complete.
11. Before each commit or checkpoint, inspect `git diff --check`, the targeted diff, and the current Git status. Do not include secrets or generated artifacts accidentally.
12. If an environmental prerequisite is unavailable (JDK, Android SDK, Docker/Postgres, LiveKit credentials, device), record the exact blocker and continue with independent static work. Never pretend a blocked check passed.

---

## 1. Product context and stated compatibility target

RiderVoice is described as an Android app using Kotlin, Jetpack Compose, Hilt, and MVVM under `mobile-app/`. It supports motorcycle group rides, voice through LiveKit (PTT and VOX), lobby/invite/join-code/share-link flows, SOS, diagnostics, ride history, and GitHub-release OTA updates. The backend uses Express, Prisma, Postgres/Supabase, Firebase Admin, and the LiveKit server SDK, with a Render deployment definition. There are also `web-app/` and `livekit-server/` areas.

The supplied audit prompt claims supported Android versions v0.0.3.14 through v0.0.3.17 send `roomName` to `/api/rooms/room/token` and use `clientRequestId` on create. Verify that claim by reading tags/source and route consumers. Do not assume the current API caller inventory is complete. The user may place this prompt itself at repository root; do not assume `docs/AI_IMPLEMENTATION_PROMPT.md` exists until you locate and reconcile it in preflight.

Core identity concepts:

- `Room.id`: canonical database ride ID.
- `Room.name`: display label; it is not a safe long-term external room identity.
- LiveKit room identity: must be stable and persisted per ride.
- Firebase UID: participant identity, per the source report.
- Join codes/share tokens: entry mechanisms, not canonical ride identity.

**Core invariant:** every authorized participant, token refresh, webhook, end action, and sweeper operation for one ride must resolve to the exact same persisted LiveKit room identity. Display-name edits, repeated create calls, a later ride with the same name, deployment, environment variables, and reconnects must not split the participants of one ride across two rooms or merge different rides into one room.

---

## 2. Required durable checkpoint protocol

Create or update the following audit/implementation artifacts on the working branch. Preserve existing files and add content without hiding prior findings.

### `docs/implementation/PROGRESS.md`

At the start of every workstream, record:

- branch and exact commit hash;
- source files inspected;
- workstreams `NOT STARTED / IN PROGRESS / COMPLETE / BLOCKED`;
- findings verified, not reproduced, or rejected with evidence;
- commands actually run and outcomes;
- files changed;
- known risks and compatibility notes;
- exact next task (one task, not “continue audit”);
- environment/device limitations.

Update this before ending a session and after each meaningful milestone. If near a context/time limit, stop after updating the checkpoint. Do not rerun completed work without a reason.

### `docs/implementation/VERIFICATION_MATRIX.md`

Map every requirement/finding to source path, implementation, automated test, build/lint check, device procedure, staging/deployment check, state, and remaining blocker. Use `N/A` with a reason rather than leaving ambiguity.

### Other required artifacts

- `docs/DIAGNOSTICS_CONTRACT.md`: complete metric semantics after the full collector-to-export path is read.
- `docs/UI_INVENTORY.md`: every actual screen/navigation destination and state, accessibility/localization findings, and UI acceptance status.
- `docs/implementation/RECONCILIATION_RUNBOOK.md`: dry-run/apply safety, backup/restore, evidence requirements, and explicit production prohibition during this task.
- `docs/implementation/ROOM_IDENTITY_ROLLOUT_RUNBOOK_TEMPLATE.md`: use the supplied template if present; adapt it to actual repository/deployment evidence, and never pre-fill post-deployment checks as passed.

Do not claim these artifacts are complete until all relevant source paths were inspected.

---

## 3. Preflight — do this first, checkpoint, then proceed if safe

1. Run the Git commands in section 0 and capture concise safe output.
2. Verify the actual `HEAD`, Android app version, current branch, and clean/dirty status.
3. Read `docs/DEEP_MOBILE_AUDIT_REPORT.md`, `docs/AUDIT_FINDINGS.md`, `docs/UX_COPY.md`, `docs/diagnostics-payload.md`, this prompt if present in the repository, relevant Prisma migrations/schema, and workflows.
4. Check whether the repository already contains progress/verification documents. Reuse compatible ones rather than overwriting.
5. Inventory tests and toolchain availability (`node --version`, package manager version, Java/Gradle/Android SDK availability without dumping secrets). Do not install large toolchains or alter the host machine without authorization.
6. Search for all LiveKit room-name construction/use in token issuance, creation, sweeper, end route, lobby routes, webhook, and tests. Search for all create-route clients and all `clientRequestId` implementations.
7. Create `docs/implementation/PROGRESS.md` and `VERIFICATION_MATRIX.md` with the baseline and a precise next task.
8. Report any source-report discrepancy that changes the implementation plan.

Do not begin an unrestricted repository-wide refactor in this phase. After the preflight checkpoint is saved, continue to the first bounded workstream in Section 21 during the same session if capacity and prerequisites allow. Do not wait for a new user instruction merely because preflight is complete. If context/time is nearly exhausted, stop after saving the next exact task and evidence in `PROGRESS.md`; resume from that checkpoint in the next session. If a genuine safety, authorization, data-integrity, or repository-state ambiguity blocks further work, report it and stop only the affected workstream.

---

## 4. P0 workstream 1 — durable create idempotency (RM-2, RM-3, RM-7, SEC-3)

### 4.1 First verify the current behavior

Inspect the current `backend/src/routes/lobbyRoutes.js` create handler, in-memory request cache, name collision logic, Prisma schema/migrations, `errorMiddleware.js`, Android `HostSetupScreen.kt`, `HostSetupViewModel`, create request model/API client, and web consumers. Verify the reported `Name-2` path, cache scoping/population, unique index, and Android request-ID lifetime. Add a minimal regression test before changing behavior where possible.

### 4.2 Target contract

A single logical user creation attempt has one stable key and one normalized payload. Identical retries must resolve to the same stored room; key reuse with a different payload is a conflict; different users must never see one another's cached result. No retry may silently suffix the display name and create a second ride.

### 4.3 Durable storage

Implement a table compatible with existing Prisma naming conventions, for example `RideCreateRequest`, with at least:

- authenticated `ownerId`;
- `clientRequestId`;
- canonical `payloadHash`;
- `roomId` reference;
- timestamps and explicit retention/tombstone semantics;
- unique constraint on `(ownerId, clientRequestId)`.

The `Room` and idempotency result must be written atomically. On a concurrency/unique-key error, re-query the winner, verify owner and payload hash, and return the winner only if the key and payload match.

**Do not create a 24-hour cleanup policy that reopens an old key as a new create.** Define precisely what happens after the replay window. Prefer retaining a compact key/hash/result tombstone for a defined retention period sufficient to prevent stale retries. If a key is expired, return a clear expired/conflict response instead of treating it as unseen. Document storage trade-offs and test them.

Canonical payload hashing must be stable across equivalent JSON key order, defaults, casing rules that the API already treats as equivalent, and omitted optional values. Do not hash secrets that should not be persisted. Revalidate authorization on every replay; do not blindly return a cached join code if membership/ownership rules no longer permit it.

### 4.4 Conflict behavior

- Same key + same payload: return the original room result for the authenticated owner.
- Same key + different payload: `409 IDEMPOTENCY_KEY_REUSED` or an existing consistent equivalent.
- Active name collision owned by another user: `409 ROOM_NAME_TAKEN` without join code or private room details.
- Concurrent same-owner/name request with a different key: do not automatically create `Name-2`. Return the documented conflict or an explicitly safe outcome-resolution response.
- Missing key from a legacy client: do not return an existing room based solely on owner/name/time and do not expose join code. If the outcome is ambiguous, return a safe conflict/status that lets the client resolve it without creating a second ride.
- Map Prisma `P2002` and related expected conflicts to appropriate HTTP statuses. Map `P2025` only where the operation truly means not found. Continue returning generic errors externally for unexpected failures while logging a request/correlation ID and the sanitized underlying error server-side.

### 4.5 Android durability

`rememberSaveable` alone is insufficient as the complete guarantee for process death, force-stop, or ambiguous timeout. Persist the request ID and canonical payload before sending the request using a suitable local persistence mechanism or equivalent durable state pattern already supported by the app.

Implement ViewModel-level single-flight guard independent of button disabling. Use an explicit create state machine: `DRAFT`, `SUBMITTING`, `UNKNOWN_OUTCOME`, `SUCCEEDED`, `CONFLICT`. For timeout/IOException/cancellation after the request may have reached the server, retry/query with the same key and payload. Do not auto-generate a fresh ID until the previous outcome is resolved or the user explicitly abandons it with a safe recovery flow. Navigate exactly once after success.

If form fields change after an unknown outcome, do not silently discard it; resolve or explicitly abandon the old attempt according to the UI flow. A deliberate new ride gets a new key.

### 4.6 Tests

- 20 concurrent identical `(owner, key, payload)` requests return one room ID and create one row.
- Same key + different payload returns 409 and leaves the original result unchanged.
- Cross-owner replay cannot read another owner's room or join code.
- Commit succeeded but response timed out; retry returns the same room.
- Server restart between attempts does not cause a duplicate.
- Concurrent same-name create does not generate `Name-2`.
- Different owner with the same name receives safe 409.
- No-key legacy flow is explicitly tested and safe.
- Expired/reused key semantics are tested.
- Android two rapid taps issue one logical request; process recreation retains the pending key/payload; error/retry does not generate a new key; navigation is consumed once.

Checkpoint after this workstream. Do not move into LiveKit identity migration until tests and API compatibility impact are recorded.

---

## 5. P0 workstream 2 — LiveKit room identity with a safe staged migration (RM-1)

### 5.1 Do not perform a direct global switch

The supplied source prompt proposed making `lkRoomName(room)` return `room.id` always and ignoring all non-UUID webhook names. Do not implement that as a one-step production change. Existing active rides may use display-name room identities, old tokens may still target those names, old replicas may still derive names from display labels, and the sweeper may delete the wrong/nonexistent identity after a direct switch.

### 5.2 Per-ride persisted identity

After inspecting existing schema/migrations, introduce a nullable persisted identity such as `Room.lkRoomName` using an additive Prisma migration. Use the exact field name only after checking naming conventions and migration history.

Requirements:

1. Once set, the identity is immutable during normal API operations.
2. All token issue/refresh, create/ensure, start, end, sweeper, webhook, participant kick, and cleanup paths use the one common resolver.
3. New rooms may use their UUID as the LiveKit identity only after compatibility deployment is complete and the rollout feature gate is enabled.
4. Existing active rides retain their actual legacy identity until ended.
5. If the identity is unknown/ambiguous, never guess for a destructive action. Report unresolved mapping and block that migration/cleanup action.
6. The code must not infer a destructive target by display name alone when multiple DB rows may refer to historical rides with the same name.

### 5.3 Safe rollout sequence

**Step A — Inventory, read-only:** determine current effective naming mode across every serving replica, whether deployments overlap old/new code, deployed LiveKit room inventory, active DB rides, room metadata, webhook state, and actual implicit room-create policy. This check is an operator/staging action. Do not access production credentials or production resources without explicit authorization.

**Step B — Compatibility release:** deploy code that can read/write persisted identities but keeps the previous identity behavior for rides lacking a verified persisted mapping. Keep UUID assignment for new rides disabled at first. All serving replicas must be compatible before new UUID identities are created. Because legacy name reuse remains possible in this short bridge period, pause new ride creation during the mixed-version rollout if operationally possible while allowing existing rides to continue. If creation cannot be paused, provide a tested protocol that proves old and new replicas cannot issue conflicting identities; do not simply accept the risk.

**Step C — Mapping dry run:** provide a dry-run tool that reports each active/non-ended DB room, candidate external identity, evidence used, confidence, and ambiguity. Use actual authoritative evidence, not just the current environment variable. Do not print secrets or personal data. Persist only verified mappings via conditional compare-and-set updates. If mappings conflict or evidence is incomplete, leave them unchanged and require human resolution.

**Step D — UUID for new rides:** after every serving replica understands the persisted field and active legacy mappings are confirmed, enable UUID assignment for new rides. Set the UUID identity atomically when the new Room is created. Resume new ride creation only after the compatibility rollout and feature gate are verified. Existing active rides keep their legacy mapping. Verify host start, invited riders, join code, share token, reconnect and web-app routes all use the same stored identity.

**Step E — Retirement:** remove the legacy resolver only in a later change, once no supported active legacy ride, client, or token path depends on it and the compatibility window has elapsed.

### 5.4 Mixed-version and rollback rules

- During compatibility rollout, old replicas and new replicas must not issue tokens for the same ride using different naming rules.
- Do not enable UUID assignment while a replica that derives room names from `Room.name` can still handle the new ride.
- After UUID rooms exist, rollback only to a compatibility release that honors persisted `lkRoomName`. Do not roll back to a binary that recomputes every identity from display name.
- The runbook must explain how to disable UUID assignment for new rides without changing already-persisted identities.
- Do not drop the new column or rewrite identities during rollback.

### 5.5 Webhook mapping

- Map events by persisted exact LiveKit identity where possible.
- Handle participant-level and room-level events separately; do not assume participant metadata is available in every event.
- Support verified legacy mappings while legacy rides exist. UUID-only mapping can be the final state, but do not ignore legacy events prematurely.
- If event-to-ride mapping is ambiguous, do not kick, delete, end, or mutate unrelated rides. Emit a safe structured log and a metric for operator review.
- Preserve signature verification.

### 5.6 Cleanup and sweeper

- Use the persisted identity for all `deleteRoom`, `listParticipants`, `sendData`, and related operations.
- Ensure all duplicate inline naming expressions are removed only after every call site uses the shared service.
- Treat `deleteRoom` 404/not-found as successful absence for the exact target.
- Do not delete an external room if multiple non-ended DB rides claim the same identity; alert and block the deletion.
- Do not interpret `listParticipants([])` as proof that a room exists and is empty unless the deployed LiveKit API's behavior has been verified. Distinguish “room absent” from “room exists with zero participants.”

### 5.7 Acceptance tests

- Same ride always resolves to same persisted identity from every route/service.
- Two rides with the same display name get distinct new UUID identities after rollout gate is enabled.
- Existing mapped legacy ride remains on its legacy identity.
- Unresolved legacy mapping is not silently changed.
- Mixed old/new server simulation cannot issue different room grants for one ride.
- Webhook, sweeper, end route, start route, and token refresh all target the persisted identity.
- Same-name historical rooms cannot cause deletion or kick of the wrong ride.
- Compatibility-release rollback preserves identity.
- All new migration tests run only against isolated test data.

Checkpoint and update the rollout/rollback runbook after this workstream.

---

## 6. P0 workstream 3 — End/token/create races and cleanup (RM-4, RM-6)

### 6.1 Required state protocol

A database transaction cannot atomically commit a Postgres status update and a LiveKit HTTP call. A status check immediately before `createRoom` reduces the race but does not eliminate it. Implement a state protocol with checks before and after external operations and durable compensation.

1. Validate current ownership/membership using authoritative database state.
2. Resolve the persisted LiveKit identity.
3. Read current lifecycle status; reject ENDED/terminal rides with structured `410 ROOM_ENDED` or the established equivalent.
4. Only perform create/ensure for a legitimate nonterminal state.
5. Re-read authoritative lifecycle state after any external room operation and before signing/returning a token.
6. If the room ended during the external operation, do not return a usable token; perform cleanup of the exact room identity. If cleanup fails, persist durable pending cleanup.
7. The end endpoint uses a conditional state transition to ENDED and is idempotent under concurrent calls. A second end request returns a documented already-ended result.
8. Persist cleanup intent so a process crash between DB end and LiveKit delete does not lose the job. A durable outbox, pending flag, or existing job mechanism may be used after reviewing project architecture.
9. Bounded retries use backoff. A 404/not-found for the intended room is success; transient network/5xx errors stay pending and visible.
10. The sweeper reuses the same lifecycle and identity service; it must not reimplement identity logic.
11. Never hold a Postgres lock/transaction open across a potentially slow LiveKit HTTP call without strong documented justification.

### 6.2 Stale tokens and implicit room creation

The prior Claude session reportedly tested a local LiveKit server and found a configuration where an old valid token could recreate a deleted room despite `roomCreate:false`, while `room.auto_create:false` blocked implicit recreation. This has not been independently verified by this prompt. Confirm it against the repository's tested LiveKit version and the actual supported deployment mode. Do not assume Cloud supports the self-hosted configuration option.

Distinguish:

- A later ride accidentally reusing the old room (mitigate with immutable per-ride identities).
- An ended ride's old room being recreated by a stale token (requires a verified token/room-creation control).
- A create call already in flight when the ride ends (requires post-call recheck and compensation).

UUID identities prevent cross-ride display-name reuse but alone do not prove that an ended room can never reappear. If the deployed platform allows implicit room creation and no verified control prevents stale-token recreation, mark production readiness **BLOCKED** or identify and validate a safe alternative. Do not label this solved merely because `roomCreate:false` is present.

### 6.3 Required tests

- Deterministic interleaving: token request reads active room; end marks it ENDED and calls delete; original create call then completes. No usable token is returned; compensating delete succeeds or is persisted pending.
- Post-end refresh returns `ROOM_ENDED`.
- Removed participant cannot reconnect with an old cached token through the app's reconnect flow.
- 20 concurrent end calls yield a single logical terminal transition and one durable cleanup intent.
- Delete failure creates pending cleanup; later sweep retries it.
- Delete 404 is treated as successful absence.
- Ambiguous room mapping prevents destructive cleanup.
- Tests explicitly distinguish behavior when implicit auto-create is off/on in the supported test server configuration.

---

## 7. P0 workstream 4 — Webhook correctness and readiness (RM-5)

1. Inspect `livekitWebhook.js`, `server.js`, configuration defaults, provider retry behavior, all event types, and current test coverage.
2. Keep signature validation intact; do not add a “skip verification” path.
3. Distinguish signature failures from transient downstream errors. Use retryable 5xx for transient processing problems where the provider's delivery contract supports retries; retain correct authentication status for invalid signatures.
4. Resolve participant events and room-level events through the persisted LiveKit identity mapping. Do not assume room-level events contain participant metadata.
5. Ambiguous mappings must not mutate or kick unrelated rooms.
6. Expose safe readiness fields such as `webhookEnabled`, schema readiness, and the last successful event timestamp / last processing failure summary. Do not expose keys or raw sensitive payloads.
7. In production, readiness must fail if webhook protection is disabled unless an explicit documented override such as `ALLOW_WEBHOOK_DISABLED=true` is set. A warning alone is not enforcement. Keep liveness distinct from readiness if the app's health design supports both.
8. Document the required webhook URL/key setup without recording actual secrets.

Tests: valid signed participant event; valid signed room event; invalid signature; transient DB error; ambiguous legacy event; enabled/disabled readiness branches; override behavior; no sensitive health output.

---

## 8. P0 workstream 5 — Replace risky runtime schema repair (DB-1, DB-2, DB-3)

### 8.1 Verify first

Inspect `backend/src/services/schemaMigration.js`, `server.js`, `backend/prisma/schema.prisma`, every migration, deployment scripts, and DB URL/pooler setup. Verify which migrations already include duplicate reconciliation and which statements can run at startup. Do not assume the supplied report's paths or approximate line numbers are unchanged.

### 8.2 Required target

- Prisma migrations are the source of truth for schema changes.
- Runtime startup executes a read-only `assertSchema(prisma)` check that validates every required column, enum value, FK/constraint, unique/partial index and known hand-authored index that application logic requires.
- A partial check of three columns is not sufficient.
- A missing required schema element makes readiness fail with a precise list; it must not trigger silent destructive repair.
- Remove success-like logs if statements failed. Any retained additive-only repair must be disabled by default, use a verified lock mechanism for the real DB topology, report `{applied, failed}` accurately, and never perform data deletion/status reconciliation.
- Move duplicate/data reconciliation to a separately invoked script that is dry-run by default, produces counts and safe IDs, archives affected rows first, requires `--apply`, runs in a transaction where possible, and is idempotent.
- Never execute production `--apply` during this task.

### 8.3 Reconciliation policy

Do not carry forward “keep newest by `createdAt`” as a universal room rule. An older ACTIVE ride must not be ended merely because a newer same-name LOBBY exists. Use status, `startedAt`, accepted-member count, persisted LiveKit identity, and actual evidence; ambiguous rows require manual resolution. Preserve invite history and archive before changes. Do not print emails, tokens, join codes, or other personal data.

### 8.4 Pooler caveat

Verify whether the configured Postgres/Supabase pooler supports the advisory-lock and migration statements required by the chosen mechanism. Use an already-supported direct migration connection if needed. Do not infer PgBouncer behavior without a test. If this cannot be verified, disable optional repair and document the blocker.

### 8.5 Tests

- Fresh isolated DB after all migrations passes `assertSchema`.
- Dropped required index/constraint/column/enum produces non-ready status and precise problem list.
- Dry run makes no changes.
- Forced failure is accurately reported and never described as completed.
- Concurrent repair tests are run only when repair remains implemented and its locking mechanism is verified.
- Reconciliation archives changes, protects ACTIVE rides, and second successful run is a no-op.
- No destructive script is invoked by normal app startup.

---

## 9. P0 workstream 6 — Release signing cannot silently fall back to a debug key (SEC-1)

Inspect `mobile-app/app/build.gradle`, the debug keystore references, all GitHub workflows, and local build scripts before editing.

Requirements:

- Release task fails clearly if required release keystore/certificate inputs are absent.
- An explicit local-only escape hatch such as `-PallowDebugSignedRelease=true` may be supported only if there is a legitimate development need; CI release jobs must never set it.
- Confirm all usages before removing/changing the committed debug keystore.
- CI checks that release signing secrets exist before build/publish.
- After a local test build, use `apksigner verify --print-certs` or supported equivalent to show the output certificate fingerprint only. Never print passwords or secret values.
- Do not publish, upload, tag, or release anything.

Tests: normal `assembleRelease` without secrets fails with a clear message; explicit debug override behaves only as documented; release workflow cannot use the override; valid release signing configuration verifies the expected certificate.

---

## 10. P1 workstream — Structured reconnect outcomes (AND-6)

Inspect `LiveKitManager.scheduleReconnect`, token provider APIs, response DTOs, and backend status/code contract. Replace exception-text substring matching with a typed result based on HTTP status and structured response code.

Suggested categories (adapt to actual API contracts):

- `Success(token)`.
- `Ended` — stable `410` / `ROOM_ENDED`.
- `Removed` — stable `403` / `REMOVED_BY_HOST` or the actual equivalent.
- `Unauthenticated` — `401`; attempt auth refresh once if supported, then fail safely.
- `Transient` — bounded backoff with jitter and cancellation.
- `NotMember` — stop and expose safe recovery.

Never reconnect indefinitely, reuse a cached token after a terminal response, or infer a state from a server prose message. Preserve stable machine-readable codes for diagnostics but do not show raw stack traces to users.

Tests: each code/status combination, unknown code, transient retry, cancellation, repeated 401, no reconnect after ended/removed, no duplicate reconnect job.

---

## 11. P1 workstream — Invite, membership, join-code, and share-link consistency

Do not assume the flow is correct because the happy path works. Trace invite accept, self-join, join code, share link, host start, remove, leave, re-invite, use-count caps, and membership state transitions in both backend and Android clients.

Requirements:

- Every supported join path converges on the same canonical `roomId` and persisted LiveKit identity.
- Accepting the same invitation repeatedly is idempotent.
- State transitions use conditional writes or transactions to prevent a removed rider from being reaccepted by a stale read/update race.
- Share-link `maxUses` is reserved/consumed atomically before the membership transition can succeed; the successful-accept count can never exceed the cap under concurrency.
- Removed users cannot reconnect via an unexpired token where webhook defense is disabled or delayed; token issuance must check current membership/room status. Document residual LiveKit stale-token risk separately.
- Do not rely on `prisma.roomDenylist` unless the actual Prisma schema has the model/table. Verify schema/client generation; dead/nonexistent policy checks must not be treated as an effective defence.

Tests: 10+ concurrent accepts; two simultaneous requests at `maxUses - 1`; remove racing accept; repeated accept; re-invite after removal; token refresh after removal; wrong room ID; cross-owner share link; legacy client contract.

---

## 12. P2 workstream — VOX and single microphone capture (AND-4)

### 12.1 Trace before tuning

Inspect `VoxEngine.kt`, `VoxDetector`, `VoxCaptureEvaluator`, LiveKit/ADM integration, all audio profile values, and state transitions when the mic gate opens/closes. The supplied report says `VoxEngine` creates a 16 kHz mono `AudioRecord` while WebRTC/ADM also captures; that the own path idles while `_isMicOpen`; and that both `ATTACK_FRAMES` and `attackMs=60` exist. Verify these facts in the current checkout.

### 12.2 Implementation rules

- Instrument debug builds to identify the active capture source, callback/frame rates, capture errors, and active recording configurations where supported. Do not record/upload speech for this telemetry.
- Verify whether two active `AudioRecord` instances occur in normal use and whether both are actually consuming microphone resources.
- If both pipelines are active, prefer one primary frame source and keep a fallback only if tests show that the primary callback path can be absent. Do not remove a source until gate-close behavior works reliably.
- Resolve attack time into one source of truth and document how frames convert to milliseconds.
- Do not change open/close thresholds (`OPEN 3.0`, `CLOSE 1.8`, `HOLD 900 ms`, floor 120–4000) without synthetic test results and controlled headset/wind measurements.

### 12.3 Automated tests

Synthetic PCM tests must cover steady wind-like noise, speech-like bursts, opening latency, silence close/hold time, noise-floor adaptation under continuous speech, initial-syllable envelope, gate closure while open, capture callback loss, route change, and cancellation.

### 12.4 Device gate

Two real phones are required for claims about speech clarity, wind rejection, or initial-syllable loss. Test phone mic/speaker and the supported headset types. Record input/output route, requested vs actual device, VOX gate logs, WebRTC stats where available, device/OS/headset/build, and conditions. Never perform phone handling or log inspection while the rider is moving; use a safe stationary/fan simulation and a safe controlled field procedure. Do not claim a physical-device result if no device test ran.

---

## 13. P2 workstream — Accurate Bluetooth route status

Inspect `AudioDeviceRouter`, its state machine, Compose consumers, Android permissions/API levels, and actual `AudioRecord.routedDevice` / communication-device paths used by the app.

The UI must distinguish `Requested`, `Pending`, `Active`, `Fallback`, `Failed`, and `Unavailable/PermissionDenied` states. A paired/selected Bluetooth device is not proof that microphone input and output are currently routed through it. Display the actual route using supported platform reports; do not mislabel a phone-mic/speaker fallback as Bluetooth active.

Handle disconnect, rapid flap (for example 5 changes in 2 seconds), timeout, permission denial, unsupported device/API, and headset re-pairing without a stale “active” state. Keep user messages concise and actionable.

Tests: pure state-machine tests for request/activate/failure/disconnect/fallback/flap/permission denied. Device matrix: wired if supported, Bluetooth Classic, Bluetooth LE Audio if available, and representative Android 11/12/13/14 devices as applicable to the product's actual minimum/target support. Physical correctness remains a device-only check.

---

## 14. P3 workstream — Battery and foreground/background lifecycle (AND-2, AND-3, AND-5)

### 14.1 Measure first

The supplied report does not contain Battery Historian/Perfetto results. Battery issues are hypotheses until measured. Inspect:

- `VoiceForegroundService.updateWakeLock` (reported as acquiring a 3-hour timeout once and not renewing while connected);
- `LiveKitManager.startPingScheduler()` (reported as starting on each `RoomEvent.Connected`, sending to remote participants every 10 seconds regardless of diagnostics gate);
- `RoomViewModel` periodic ghost pruning and lobby polling;
- location policy and GPS intervals;
- audio capture path count.

### 14.2 Changes

- Decide whether the wake lock is necessary using supported Android lifecycle behavior and target-device measurements. If retained, release explicitly and use a justified renew/watchdog state machine; do not keep an indefinite lock without reason.
- Gate ping/latency telemetry according to the diagnostics contract or adopt a lower-cost design only after preserving needed measurements.
- Prefer lifecycle-aware and cancellable polling. Use event-driven signals where reliable, but retain safe fallback/reconciliation for missed events.
- Do not reduce audio fidelity, VOX sampling, or critical ride-state reliability without a documented product trade-off and test.

### 14.3 Before/after test plan

On the same two devices/builds, run consistent-duration scenarios: idle home; lobby; active muted; active VOX; active PTT; Bluetooth; background active/screen off. Capture battery stats, Perfetto CPU/wakelock/network tracks, thermal state, screen state, network, device/OS/build, and exact commands. Report comparable actual measurements only. If the test was not run, say so.

---

## 15. P3 workstream — Diagnostics contract before metric fixes

The supplied report says the collector, backend diagnostic service, and validator were not examined in depth. Start by reading:

- `docs/diagnostics-payload.md`;
- `NetworkDiagnosticsCollector.kt` and related Android queue/storage code;
- `diagnosticService.js`;
- `diagnosticValidator.js`;
- Prisma diagnostic models/migrations;
- API routes, export jobs, frontend consumers, and run close/grace-period logic.

Create `docs/DIAGNOSTICS_CONTRACT.md` with, for every metric: field/type, source, unit, time base, instantaneous/delta/rate/cumulative classification, formula/denominator, interval, null-vs-zero rules, sequence/order/dedupe semantics, offline queue/retry behavior, backend validation/storage, export interpretation, privacy constraints, test evidence, and platform limitations.

Treat these prior Claude notes as **unverified leads** until confirmed: missing sample written as 0; event dedupe by type only; duplicate batch overwrites stored payload; late batches accepted but not exported after close; unused validator enums; seconds/milliseconds mismatch; Boolean loss value displayed as percent; cumulative counters summed across samples.

Add targeted tests as confirmed:

- duplicate `(runId, riderId, sequenceNumber)` is idempotent;
- out-of-order batches follow a specified policy;
- missing data is null/absent, not invented zero;
- loss is calculated from appropriate deltas/denominators;
- units/time base are consistent across producer/API/store/export;
- cumulative totals are not summed as interval deltas;
- dedupe keys distinguish peer/session when appropriate;
- conflicting payload for same sequence is rejected or handled by a documented safe canonical policy;
- late data follows `DIAG_CLOSE_GRACE_SECONDS` and remains exportable or is explicitly rejected with evidence;
- offline queue survives process death and retries with bounded backoff;
- validator constraints match actual producer contract;
- timestamps/exports preserve unit, ordering and null semantics.

Keep advanced diagnostic metrics off the critical in-ride surface; show complex details on a separate diagnostic screen.

---

## 16. P4 workstream — Full application UI/UX and localization

### 16.1 Inventory first

Do not start by redesigning only Home and Room. Build `docs/UI_INVENTORY.md` after reading every actual Compose screen, activity, dialog, sheet, navigation route, notification entry point, and deep link. The supplied report names Splash, Login, Register, Home, Account, Settings, HostSetup, JoinRoom, InvitesInbox, InviteFriends, Squad, DeviceSetup, Lobby, Room, SOS, EmergencyAlertActivity, RideInviteActivity, RoutePlanner, RideStats, RideReplay, and PostRideSummary, but this list is explicitly partial and must be verified.

For every surface record:

- file path, navigation route, callers, back-stack behavior;
- current purpose and key action;
- loading/empty/error/offline/permission-denied/validation/success states;
- data source and freshness/caching semantics;
- hardcoded user-facing strings and resource keys;
- touch target sizes, `contentDescription`, semantics, focus/TalkBack, contrast;
- insets/IME/keyboard and small-screen behavior;
- light/dark theme, disabled/pressed/focus states, font scaling;
- localization coverage and Hindi/Marathi expansion risk;
- existing tests and missing coverage;
- business logic/API flows that must not change in the redesign.

### 16.2 Design rules

- Make UI consistent, professional, rider-friendly, and accessible without changing business logic unnecessarily.
- Reuse existing theme tokens/components when suitable; introduce a new shared component only when multiple screens genuinely need it.
- Keep the active ride surface calm and glanceable: mic/PTT state, actual connection state, SOS, and essential routing state visible.
- Separate advanced diagnostic charts/data from primary ride controls.
- Never fabricate data, hide an important permission failure, or claim a device is active when it is only requested.
- Every error message should say what happened and the next action; do not display stack traces or raw backend errors.
- Preserve the project's established terminology and `docs/UX_COPY.md` principles if still current.
- Test large text (at least 1.3×), light/dark, Hindi/Marathi longer strings, TalkBack/focus order, keyboard/insets, offline and permission-denied states.

### 16.3 Localization and unreachable screens

The supplied report estimates 78 strings in each of `values`, `values-hi`, and `values-mr` but about 111 hardcoded `Text("...")` hits. Recount in the current checkout; regex counts are only a lead, not the final inventory.

Move user-facing literals to resources for all supported locales, preserve placeholder names/types, and verify translation quality. Do not blindly machine-copy English into Hindi/Marathi. Revisit `HeadsetSettingsScreen`: register and test it if it is a required feature, or remove it only after proving no other entry point or product dependency needs it.

### 16.4 Acceptance

- Inventory includes every discovered destination and its important states.
- High-priority workflows have meaningful loading/error/offline states.
- No false connection, routing, SOS delivery, or verification status.
- Relevant lint checks pass for touched code.
- Screenshot/manual matrix documents light/dark, large font, Hindi/Marathi, accessibility, permission, offline and error states.
- Existing business flows and API contracts remain regression-tested.

---

## 17. P5 workstream — SOS delivery semantics

Inspect `emergencyRoutes.js`, the alert model/schema, FCM send/retry path, Android SOS screens/activities, and user-visible copy end to end. The supplied report says an alert may be created with `status: 'SENT'` before actual delivery evidence, but it did not fully audit the final path. Follow-up notes also report (unverified) that `sent=true` may be set merely when recipients exist even if zero FCM sends succeed, and that the FCM TTL may be 60 seconds. Trace and test both behaviors before changing them; do not treat those notes as established facts.

Requirements:

- Distinguish queued, server accepted, provider accepted, failed, and recipient acknowledged only where the system can actually observe that state. FCM acceptance is not proof that a device displayed a notification.
- Support partial send failures and report successful/failed recipient counts truthfully.
- The UI must not say “delivered” without a reliable acknowledgement path.
- Do not send a real SOS in automated tests; use mocks and isolated test hooks.
- Do not reintroduce `TelephonyManager` without a clearly justified and reviewed safety reason. The supplied report says it was removed in v0.0.3.17 due to a Play Protect false-positive concern; reverify before touching it.
- If adding an emergency-country option, default it deliberately, show the number before dialing, preserve the established fallback, and verify country-number data through current authoritative sources during implementation.

Tests: no recipients, provider failure, partial failure, retry, duplicate button taps, offline mode, permission denied, cancellation/process lifecycle, and status copy. No real calls/SOS messages.

---

## 18. P5 workstream — OTA integrity and documentation

Inspect `UpdateManager`, release metadata parsing, checksum source, APK download/install flow, `PackageManager` APIs, release workflows, README, release notes, and `.env.example`.

Requirements:

- Checksum absent, invalid, or mismatched => fail closed with clear copy.
- Compare APK signer certificate SHA-256 with the installed app's expected signing identity using APIs supported by the app's SDK range; account for legitimate signing history/certificate rotation where relevant.
- Treat app-level certificate checking as defense in depth. Android's installer still enforces package-update signer rules.
- Validate the release origin and unambiguous target asset. Do not install arbitrary URLs.
- Test network cancellation, interrupted download, invalid metadata, missing/mismatched checksum, changed signer, valid same-signer update, and install permission failure.
- Update README/release docs only to claims proven by current code and tests. Never report OTA as device-tested unless actually installed on a device with a valid test release signature.

---

## 19. API compatibility and external consumers

Before changing a route/DTO, search all call sites in Android, backend, web app, tests, scripts, and documentation. Record before/after request/response behavior and stable error codes. Add API contract tests for the currently supported older client range. Prefer additive response changes, and use an explicit migration period for required fields or semantics changes.

Do not achieve compatibility by returning another user's room/join code, suffixing a display name on retry, making an ambiguous guess about a legacy room, or silently routing riders into an existing room.

If the `web-app/` is officially out of UI scope, keep it out of visual redesign but still test any API behavior change that affects it.

---

## 20. Required commands and environment verification

Verify package scripts and actual directories first. The supplied baseline lists these commands:

Backend from `backend/`:
```bash
npm ci
npx prisma generate
npm test
```
The reported test script is `node --test --test-concurrency=1 test/*.test.js`; verify `package.json` before relying on it. Named existing test files from the supplied prompt include `room_lifecycle.test.js`, `room_tokens.test.js`, `invite_and_convoys.test.js`, `migrations.test.js`, `p0_fixes.test.js`, and `e2e_convoy.test.js`.

Android from `mobile-app/`:
```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Also verify the release signing path locally without secrets, using the intended clear failure behavior, and verify the successful release path only with safe test/release credentials available through proper secret management. Do not print secret values.

CI: inspect `.github/workflows/build.yml` and `release.yml`, ensure new tests run, ensure release jobs cannot use a debug override, and ensure branch CI cannot deploy/publish production artifacts.

If a command cannot run, paste only sanitized error summary into the checkpoint and mark it blocked. Do not invent pass results.

---

## 21. Workstream schedule and stop conditions

Complete the work in this order unless you document a specific dependency-driven reason to change it:

0. Preflight, current-source reconciliation, durable checkpoint.
1. Backend create idempotency + Android persistent request identity.
2. Additive persisted LiveKit identity field and compatibility resolver; staged rollout tooling, not production rollout.
3. End/token/create concurrency guard, durable cleanup and stale-token policy.
4. Webhook mapping and production readiness enforcement.
5. Read-only schema assertion and dry-run migration/reconciliation tooling.
6. Release signing and OTA integrity.
7. Structured reconnect and invite/share-link concurrency consistency.
8. Diagnostics contract and metric fixes.
9. VOX/microphone-source tests and safe routing behavior.
10. Battery/lifecycle baseline and measured changes.
11. SOS status/delivery honesty.
12. Full UI inventory, UI/UX corrections, localization/accessibility.
13. Cross-component integration and independent final review.

For each workstream:

1. Read the relevant source paths and tests.
2. Update finding evidence and record any discrepancy.
3. Add or update focused regression tests.
4. Implement the smallest coherent fix.
5. Run the targeted test(s), then broader relevant checks.
6. Inspect the full targeted diff and `git diff --check`.
7. Update `PROGRESS.md`, `VERIFICATION_MATRIX.md`, and related audit/contract documents.
8. Create a small local commit if local commit workflow is appropriate; do not push or merge.
9. State the next bounded task.

Stop a workstream and mark it blocked if it requires production access, a physical device, a proprietary LiveKit Cloud configuration, or other authorization not available. Continue with independent tasks rather than making assumptions. Do not enable UUID mode in production as part of this task; provide a staged rollout runbook and tests instead.

---

## 22. Final independent review

Before reporting completion:

1. Run `git diff --check` and review the entire diff, including migration files and lockfile changes.
2. Run all applicable backend tests, Android unit tests, lint, and debug build.
3. Run new targeted concurrency/idempotency/lifecycle/migration tests.
4. Search again for duplicated LiveKit name derivation, destructive runtime SQL, catch-and-ignore cleanup, status updates without conditions, token logging, hardcoded strings in touched UI, and stale user-facing claims such as “delivered” without evidence.
5. Inspect the final branch against the recorded baseline, for example `git diff main...HEAD --stat`, only if the branch relationship makes that comparison valid. Also report `git status --short`.
6. Confirm no secrets, test outputs containing personal data, production artifacts, or accidental file deletions were introduced.
7. Update `docs/DEEP_MOBILE_AUDIT_REPORT.md` with an “Implementation Results” section. For each finding/workstream, state `FIXED`, `NOT REPRODUCIBLE`, `BLOCKED`, `NEEDS DEVICE`, or `NEEDS DEPLOYMENT VERIFICATION`; include the evidence command/result summary and unresolved work.
8. Update docs with actual outcomes, not desired outcomes. No production deployment, database reconciliation, release, tag, merge, or publish.

---

## 23. Definition of done

The coding task is not production-ready merely because it compiles. Completion requires all items below or explicit, honest blockers:

- Durable request idempotency with payload binding, owner scoping, safe retention, timeout recovery, and concurrency tests.
- Per-ride persisted LiveKit identity, staged compatibility rollout, safe legacy mapping, and mixed-version tests.
- Deterministic end/token/create race tests and documented stale-token/implicit-room-create control verified for the actual deployment type.
- Durable, idempotent cleanup with retries and correct 404 behavior.
- Correct webhook mapping, signature-vs-transient error handling, and enforced production readiness policy.
- Read-only schema assertion; destructive reconciliation separate, dry-run by default, archive-first, idempotent, and not run against production.
- Release cannot silently use a committed debug signing key.
- Reconnect logic uses structured status/code outcomes, not exception message substring matching.
- Invite/share-link concurrency is safe and caps cannot be exceeded under contention.
- Diagnostics contract is evidence-based; confirmed metric bugs have tests.
- VOX/audio source and routing are tested; no unmeasured threshold change is represented as improved voice quality.
- Battery changes have before/after measurements or are explicitly marked unmeasured.
- Every Android screen/destination is inventoried; key UI, localization, accessibility and error-state defects are fixed or tracked with evidence.
- SOS and OTA status claims match the evidence available.
- Applicable CI tests pass and all blocked device/deployment checks have exact procedures.
- Final diff, branch, status, tests, limitations, and follow-up actions are reported.

**No production deployment, production data mutation, release, merge, or publish is authorized by this prompt.**
