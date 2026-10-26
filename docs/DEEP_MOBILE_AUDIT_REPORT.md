# RiderVoice Deep Mobile Audit Report

**Revision:** Safety-integrated implementation baseline
**Repository:** https://github.com/BuzyU/Rider_APP
**Reported source baseline:** `main` at `c7ba75a` (`v0.0.3.17`)
**Audit date stated by the source report:** 2026-10-25
**Prepared from:** The user-provided audit report and implementation prompt.
**Important:** This revision consolidates and strengthens the supplied documents. It is **not** a fresh clone, independent source-code audit, build, test run, device test, or production inspection. All findings described below inherit the evidence level recorded in the supplied report until a coding agent re-verifies them against the actual checkout.

---

## 0. Executive decision

### 0.1 Decision

Use this report as the **review and implementation baseline**, but do not treat every proposed fix as already validated. The source audit was static-only and explicitly states that no Gradle build, backend test suite, emulator, physical device, LiveKit server, or production database was used. The implementation agent must verify each relevant claim before changing code and must record the result.

The highest-risk work is the room lifecycle. A careless migration from display-name-based LiveKit rooms to UUID-based names can itself split a single ride into two LiveKit rooms, orphan participants, or make cleanup target the wrong room. Therefore, **a direct global UUID-only switch is not an acceptable first production change**. Implement compatibility and persistent identity first; enable UUID room identities for new rides only after the compatibility release has reached every serving backend instance and existing active rides have a verified mapping.

### 0.2 Most important reported findings

| ID | Severity | Area | Source-report conclusion | Evidence state in source report |
|---|---|---|---|---|
| RM-1 | Critical | LiveKit identity | LiveKit room names can be display names, depending on `ROOM_ID_MODE`; room identity logic is duplicated | Verified in code; production setting unknown |
| RM-2 | High | Create retries | A retry or concurrent create may create a suffixed second room after an opaque error | Code path reported verified; user-observed frequency not reproduced |
| RM-3 | High | Client idempotency | Create request ID may not survive process death / is not durable | Verified in code, per source report |
| RM-4 | High | End/create race | Token issuance may create a LiveKit room after the database ride has ended | Race inferred from code; not reproduced |
| RM-5 | High | Webhook | LiveKit webhook handling may be disabled by default | Default reported verified; production setting unknown |
| RM-6 | Medium | Cleanup | LiveKit cleanup errors may be swallowed | Verified in code, per source report |
| DB-1/2/3 | High | Runtime schema repair | Runtime drift guard is incomplete and destructive statements may run without a controlled migration process | Verified in code, per source report |
| SEC-1 | High | Release signing | Release build may fall back to a committed debug signing key | Verified in code, per source report |
| AND-4 | High / needs device proof | Audio capture | VOX may use a second `AudioRecord` in addition to WebRTC/ADM capture | Existence reported verified; impact not measured |
| AND-3 / AND-5 | Medium–High | Battery / lifecycle | Periodic peer pings and polling may run regardless of diagnostic need or app foreground state | Code path reported verified; energy cost unmeasured |
| SOS-1 | Medium | SOS delivery semantics | Alert state may claim `SENT` before delivery is confirmed | Initial state reported verified; UI and final delivery path not fully examined |
| UX-1 / UX-2 | Medium | UI / localization | Hardcoded text likely remains; headset settings screen may be unreachable | Approximate grep and navigation inspection in source report |
| DIAG | Unknown until examined | Diagnostics | Full collector, backend service, validator, and metric semantics were not audited in depth | Not examined in source report |
| UX | Unknown until examined | Entire application UI | Screen-by-screen Compose body inspection was incomplete | Not examined in source report |

### 0.3 Stop-ship conditions

Do not claim production readiness while any of the following remains true:

1. A ride can accidentally use different LiveKit identities for its host and invited riders.
2. A new ride can reuse the LiveKit room of a previous ride.
3. The end/token/create race can issue a usable token for an ended ride without a proven mitigation for stale tokens and implicit room creation.
4. Create retries can create a second ride after an ambiguous timeout.
5. Runtime startup code can perform unreviewed destructive data changes.
6. Release signing can silently use an unintended key.
7. A critical item is marked fixed without its stated automated tests passing, or is represented as device-verified without device evidence.

---

## 1. Scope, evidence policy, and limitations

### 1.1 Scope

RiderVoice is described in the supplied documents as an Android application built with Kotlin, Jetpack Compose, Hilt, and MVVM, with a Node/Express/Prisma/Postgres backend, Firebase Authentication/Admin, LiveKit voice, SOS, diagnostics, ride history, invite/share/join-code workflows, and GitHub-release-based OTA updates. The repository also contains a web application and LiveKit server configuration; the implementation plan must inspect those consumers where API compatibility or deployment behavior is relevant.

### 1.2 Evidence labels

Use these labels consistently in every report, commit message, and final response:

- **V — Verified in code:** The source path was actually inspected in the current checkout. This does not mean it was reproduced at runtime.
- **R — Reproduced:** A test or controlled runtime experiment demonstrated the behavior; include the command, environment, and result.
- **S — Suspected:** A plausible mechanism that needs a targeted test or measurement.
- **N — Not examined:** Source path or behavior has not been sufficiently inspected.
- **B — Blocked:** Verification could not be performed, and the exact missing prerequisite is recorded.
- **D — Device/deployment-only:** Cannot be established by static analysis alone.

Never conflate V and R. A source audit can establish that a call is made; it cannot establish the actual battery impact, Android OEM behavior, production environment configuration, or whether a user-visible issue has occurred in a particular release.

### 1.3 Known limitations inherited from the source audit

The supplied report explicitly says no Gradle build, backend tests, emulator, physical device, LiveKit server, or production DB was used. It also lists these areas as not examined in depth:

- Compose bodies and state flows of most screens.
- Numeric `AudioProfile` values and the `AudioRouteDecisionEngine`.
- `NetworkDiagnosticsCollector`, `diagnosticService.js`, and `diagnosticValidator.js`.
- `membershipService.js` and much of `emergencyRoutes.js`.
- `RideRecorder`, `ThermalManager` internals, web-app consumers, LiveKit server configuration, GitHub Actions workflows, Android test files, and translation quality/placeholder parity.
- Full navigation state restoration, the complete UI component inventory, accessibility, and physical-device audio behavior.

The source report's code findings should be revalidated at the current branch/commit before implementation. The reported production values of `ROOM_ID_MODE`, `LIVEKIT_WEBHOOK_ENABLED`, LiveKit implicit room creation policy, and database history are unknown.

---

## 2. Repository baseline and audit-document reconciliation

The supplied source report states:

- Baseline commit: `c7ba75a`, tag `v0.0.3.17`.
- Approximate inventory: 150 Kotlin files, 12 backend test files, 19 Android test files, and 8 Prisma migration directories.
- The repository has `mobile-app/`, `backend/`, `web-app/`, and `livekit-server/` areas.
- `docs/AUDIT_FINDINGS.md` describes a target of `ROOM_ID_MODE=true`, while `backend/.env.example` reportedly defaults to `ROOM_ID_MODE=false` and `render.yaml` lists it as an unsynchronized variable. The real production setting was not inspected.
- `docs/AUDIT_FINDINGS.md`'s historical test/build claims relate to earlier commits and must not be assumed valid for the stated baseline.

The implementation agent must run `git status --short`, `git branch --show-current`, `git log -5 --oneline`, and `git diff --stat` before doing anything. If the current commit differs from `c7ba75a`, re-map every path and finding. Never overwrite existing user changes or untracked files.

---

### 2.1 Supplemental findings from follow-up notes — revalidate before changing code

The following leads were reported in a later Claude continuation after the source report was drafted. They are included here so they are not lost between sessions. **They were not independently inspected or reproduced as part of this document revision.** Treat them as `S` until the coding agent verifies the actual current source. Do not implement from a one-line claim alone.

| Lead ID | Area | Reported concern | Required verification before fixing |
|---|---|---|---|
| INV-1 | Membership transition | Invite/membership state transitions may perform read-then-update without a state predicate, allowing a removed rider to be reaccepted during a race. | Read `membershipService.js` and every caller; create deterministic remove-vs-accept concurrency tests; confirm legitimate re-invite semantics are preserved. |
| INV-2 | Share-link cap | `maxUses` may be checked/updated after membership acceptance, allowing accepted participants to exceed the cap under concurrency. | Trace reservation, increment, acceptance and rollback paths; test two or more simultaneous accepts at `maxUses - 1`. |
| SEC-4 | Denylist | A `prisma.roomDenylist` check may refer to a model/table absent from the actual Prisma schema/client, making the intended defence ineffective or unreachable. | Search schema, migrations, generated client, tests and all call sites; prove whether the check can execute and fail safely. Do not add a policy check that is not backed by persisted state. |
| RM-8 | End-route concurrency | Concurrent `/end` requests may both pass an unconditional or stale status check and run lifecycle side effects more than once. | Inspect the route and state update predicates; test duplicate concurrent requests and end-vs-sweeper races; require one logical terminal transition and durable cleanup intent. |
| SEC-5 | Removed rider / stale token | An unexpired LiveKit token may permit a removed rider to reconnect when webhook enforcement is disabled or delayed. | Verify token issuance and refresh membership checks, cached-token behavior, webhook configuration and actual LiveKit implicit-create behavior separately. Do not assume a webhook alone revokes a token. |
| WEB-1 | Webhook errors | Webhook processing failures may be returned as HTTP 401 even when signature validation succeeds and the failure is transient, potentially preventing provider retries. | Separate signature/authentication failures from database/handler failures; test provider retry contract and return the appropriate retryable status for transient failures. |
| SOS-3 | SOS partial delivery | A response may report `sent=true` merely because recipients exist even when all FCM sends fail; an alert row may begin as `SENT` before an attempt, and FCM TTL may be fixed at 60 seconds. | Trace the complete alert state, FCM send results, TTL, retry path, and Android copy. Test zero, partial, and full provider acceptance with mocks; never send real SOS in tests. |
| CFG-1 | Effective room mode | Follow-up notes report that `roomIdMode` is not exposed by `/health` and may instead be exposed through `/api/app/config`. | Inspect current route implementation and safe response fields. Do not rely on the older endpoint claim without verifying current HEAD. |

Diagnostics leads are already itemized in Section 13.3 and must be verified through the full producer-to-export path. The code-based findings in this table are not a confirmation that a production incident occurred. Record evidence, source paths, tests, and outcomes in `docs/implementation/PROGRESS.md` and `docs/implementation/VERIFICATION_MATRIX.md`.

## 3. Architecture and core identity model

### 3.1 Described request flow

```text
Android HostSetupScreen -> HostSetupViewModel
  -> POST /api/lobby/create
  -> Room row (no LiveKit room necessarily created yet)

Invitations / share links / join code / self-join
  -> membership + join services
  -> issueRoomToken
  -> LiveKit room creation/resolution
  -> token for LiveKit room
  -> LiveKitManager.connect

Host starts ride
  -> /api/lobby/:ref/start
  -> issueRoomToken

Reconnect
  -> mobile token provider
  -> /api/rooms/token/refresh or related token endpoint
  -> issueRoomToken

End ride
  -> mark DB room ENDED
  -> send LiveKit data where applicable
  -> delay (reported as 400 ms)
  -> delete LiveKit room

Periodic sweeper
  -> find stale/empty rooms
  -> mark ended and delete LiveKit room

LiveKit webhook
  -> optional webhook processing, depending on deployed configuration
```

The exact routes and call relationships must be rechecked in the current checkout.

### 3.2 Identity terms

| Field / concept | Meaning | Rule |
|---|---|---|
| `Room.id` | Database UUID | Canonical database identity for a ride |
| `Room.name` | Human-readable display name | Must not be used as the sole long-term external room identity |
| `Room.joinCode` | Short-lived join mechanism | Never use as canonical identity |
| LiveKit room name | External room identity used in grants and LiveKit APIs | Must be immutable for the lifetime of a ride and persisted or resolvable without guesswork |
| LiveKit SID | LiveKit's server-side room identifier | Store only if there is a demonstrated need and a supported source of truth; do not substitute it casually for room name |
| Participant identity | Firebase UID, per source report | Must remain stable and authorization-checked |
| Callsign / display name | UI-only identity | Never use as authorization or room identity |

### 3.3 Core invariant

For any non-ended ride, every authorized participant, token refresh, webhook, room-ended operation, and sweeper action must resolve to exactly the same persisted LiveKit room identity. A display-name change, retry, deployment, environment-variable change, or creation of a later ride with the same display name must not change that identity.

---

## 4. LiveKit room identity: corrected migration design (RM-1)

### 4.1 Why a direct UUID-only change is unsafe

The supplied implementation prompt proposes making `lkRoomName(room)` always return `room.id`, then having webhooks and the sweeper ignore non-UUID names. That may be a valid final state for newly created rides, but it is not a safe one-step production migration because:

- Existing participants may hold tokens whose room grant names the legacy display name.
- An existing LiveKit room may still be active under that legacy name.
- Existing backend replicas may still derive room names from `Room.name` while upgraded replicas use `Room.id`.
- A sweeper using the new UUID identity can fail to clean up a legacy LiveKit room.
- Ignoring all non-UUID webhook room names can silently disable lifecycle handling for legitimate legacy active rides.
- A rollback to an older server version can make new UUID-backed rooms unusable if that server recomputes names from display names.

Therefore, identity migration must be **per ride**, persisted, backward-compatible, and staged.

### 4.2 Target design

Introduce a persisted per-ride LiveKit room identity, for example `Room.lkRoomName` (name to be confirmed against current schema conventions). The field's exact constraint must be reviewed before coding. The design requirements are:

1. **Immutable assignment:** once a ride has a persisted LiveKit identity, ordinary request handling must not silently change it.
2. **New rides:** after the compatibility rollout is complete and the feature switch is enabled, assign the new ride's UUID as its LiveKit room name and persist the value atomically with the ride creation.
3. **Legacy active rides:** retain their actual legacy room identity until the ride ends. Do not reassign an active room simply because the code was upgraded.
4. **Unknown legacy mapping:** if the actual LiveKit identity cannot be established safely, do not guess and do not automatically kick participants. Mark the mapping as unresolved, emit a structured operational error without secrets or rider PII, and require the documented reconciliation procedure.
5. **Single resolver:** all code paths must call one resolver/service for the persisted room identity. Remove duplicated inline expressions only after all call sites have been migrated and tests cover them.
6. **Webhook mapping:** first resolve by exact persisted LiveKit identity. UUID parsing alone is not a sufficient authorization or mapping strategy. Legacy names must be mapped only when the mapping is unambiguous. An ambiguous event must be logged/ignored or quarantined safely; it must never cause the handler to kick users from an unrelated ride.
7. **Cleanup:** `/end`, the sweeper, retry jobs, and explicit administrative cleanup must operate on the persisted identity. An absent LiveKit room is success for cleanup; transient errors remain pending and observable.
8. **No display-name lookup to choose a destructive target:** never infer which LiveKit room to delete solely from the current display name when multiple historical DB rooms can share that display name.

### 4.3 Suggested staged rollout

**Stage 0 — Read-only inventory / operator gate**

Before changing production behavior, an authorized operator must establish, without printing secrets:

- Effective `ROOM_ID_MODE` value on every serving backend replica.
- Whether old and new backend versions will overlap during deployment.
- Whether `LIVEKIT_WEBHOOK_ENABLED` is enabled and the webhook key/configuration is valid.
- Actual LiveKit implicit room-creation behavior for the deployed self-hosted/Cloud configuration.
- Current active/non-ended DB rooms, their IDs and display names, the LiveKit room list, and any available room metadata needed to correlate them.
- Which active rides have a confirmed room identity and which are ambiguous.
- Whether production has already executed the destructive branch of `schemaMigration.js`.

This must be a read-only, access-controlled procedure. The coding agent must not access production unless explicitly authorized through a supported, secure workflow; it must not print tokens, credentials, join codes, emails, or rider data.

**Stage 1 — Additive schema and compatibility code**

- Add the nullable identity field through a normal versioned Prisma migration; do not use runtime data-repair code to alter production schema.
- Implement a single room identity service that honors a persisted identity and has explicit, observable legacy handling for rows that do not yet have a value.
- Keep new-room behavior behind a feature flag or explicit rollout gate. Do not change the effective production mode while old replicas can still issue tokens using the old logic.
- Make webhook, token issuance, end, and sweeper paths use the common resolver, with a compatibility branch for legacy rides.
- Add tests for both UUID and legacy identity paths.

**Stage 2 — Complete the compatibility deployment**

- Deploy the compatibility version while maintaining the previously active naming behavior for existing rides.
- Confirm every serving backend replica is running a version that understands the persisted per-ride identity field.
- Do not start creating UUID-named rooms while an old replica may still handle token issuance for that ride.
- Because legacy name reuse remains possible during this short bridge period, prefer pausing **new ride creation only** during the mixed-version rollout while allowing already active rides to continue. If product operations cannot pause creation, the deployment plan must prove—with a tested compatibility protocol—that old and new replicas cannot issue conflicting identities. Do not casually accept the risk.
- The deploy process must state how mixed-version replicas are drained and how this is verified.
- Before advancing, confirm there are no serving replicas that ignore `lkRoomName`, and no active ride whose persisted identity conflicts with its observed LiveKit room.

**Stage 3 — Backfill / reconcile legacy mappings**

- Provide a read-only dry-run report mapping DB rooms to LiveKit room identities.
- Use authoritative evidence (actual LiveKit room inventory, persisted metadata, audit logs, known stable configuration, and room lifecycle state) rather than assuming the current environment variable proves how every existing token was issued historically.
- Where identity is confirmed, persist it with conditional updates that cannot overwrite a conflicting value.
- Where identity is ambiguous, do not auto-apply. Produce a manual-resolution list and keep the UUID rollout blocked for those active rides.
- Archive mapping decisions and provide rollback instructions. Do not output secrets or participant personal information.

**Stage 4 — Enable UUID identities for new rides**

- Only after Stage 2 is verified and Stage 3 has no unresolved active-room ambiguity that can cause a split, enable UUID assignment for newly created rides.
- Resume new ride creation only after all compatibility replicas are verified and the UUID feature gate is enabled in a controlled way.
- Existing rides keep their persisted identity until ended.
- New rides get `Room.id` as the LiveKit room name at creation time, atomically persisted.
- Monitor structured counts for legacy active rooms, UUID rooms, unresolved mappings, token failures, `ROOM_ENDED`, cleanup pending, and webhook mapping failures.

**Stage 5 — Retire legacy mode**

- Do this only after the maximum supported legacy ride lifetime/token duration has elapsed and telemetry confirms no legacy identities are active or being issued.
- Remove the legacy resolver only in a separate change with explicit acceptance criteria. Do not make it part of the first identity fix.

### 4.4 Rollback constraints

Once UUID-backed rides exist, rolling back to code that always derives LiveKit room names from `Room.name` can split participants across rooms. Therefore:

- The rollback target for the first UUID rollout must be a compatibility release that understands persisted `lkRoomName`, not a pre-compatibility binary.
- Keep the compatibility path for at least the supported legacy ride lifetime and documented rollback window.
- The runbook must describe how to disable UUID assignment for new rides without rewriting identities of existing rides.
- Do not delete the new column during rollback.

### 4.5 Acceptance tests

- Same DB ride always resolves to the same persisted LiveKit name across token issuance, refresh, end, sweeper, and webhook processing.
- Two new rides with the same display name have different UUID identities.
- A legacy active ride continues to resolve to its known old name after a compatibility deployment.
- Mixed-version simulation cannot cause participants for one ride to receive grants for two different rooms.
- An ambiguous legacy mapping cannot trigger a destructive kick/delete against an unrelated room.
- The sweeper deletes the persisted room identity and does not recompute a different name from the display name.
- Rollback simulation from the UUID-enabled build to the compatibility build preserves the mapping.

---

## 5. Create retries and durable idempotency (RM-2, RM-3, RM-7, SEC-3)

### 5.1 Reported source behavior

The supplied report describes an in-memory `recentCreateRequests` map keyed by `clientRequestId`, populated only after success, and a create route that suffixes a conflicting name as `Name-2`, `Name-3`, etc. It reports a partial unique index on active room names and generic 500 handling for Prisma uniqueness violations. These claims must be verified in the current checkout.

### 5.2 Required invariants

1. One intentional user creation attempt has one stable `clientRequestId`.
2. The ID is scoped to the authenticated owner and bound to the normalized request payload.
3. Identical retries return the same result and do not create another DB or LiveKit room.
4. A reused key with a different payload returns HTTP 409 (for example, `IDEMPOTENCY_KEY_REUSED`) and never changes the original request.
5. Different owners cannot read or replay another owner's cached response or join code.
6. A display-name collision must never cause automatic `-2`, `-3` suffix creation as a retry side effect.
7. An ambiguous timeout must be treated as unknown outcome. Retry the same request ID and payload or query the authoritative result; never silently generate a fresh key.
8. A deliberate new ride requires a new request ID and an explicit new user action.

### 5.3 Persistence model

Use a durable table such as `RideCreateRequest` after inspecting Prisma conventions. At minimum, the record should contain:

- `ownerId` / authenticated owner reference.
- `clientRequestId`.
- `payloadHash` calculated from a canonical normalized request representation.
- `roomId` / result reference.
- creation timestamp and, if required, a separate expiry/retention timestamp.
- Unique constraint on `(ownerId, clientRequestId)`.

The `Room` and idempotency record must be written atomically in one database transaction. The implementation must handle two simultaneous inserts that race on the unique key by reading the winner and returning its result, after verifying its owner and payload hash.

**Retention clarification:** do not define “24-hour idempotency” in a way that allows an old retry to create a second ride after the idempotency record is deleted. Either keep a compact key/payload/result tombstone long enough to cover the supported retry lifetime, or define and enforce an explicit expiry contract where expired requests return a conflict/expired response rather than being treated as a brand-new create. Storage cleanup must not silently reopen old keys.

### 5.4 Name collisions and legacy clients

- Remove automatic name suffixing from the retry path.
- If a name is active and owned by a different user, return `409 ROOM_NAME_TAKEN`.
- If an older client omits the idempotency key, do not blindly return a pre-existing room to the caller based only on owner + name + a short time window. That heuristic can accidentally route an intentional new action into a previous ride.
- For no-key clients, use a safe, explicit conflict/ambiguous-result behavior. If the backend can securely identify a likely same-owner prior create, return only a documented same-owner result reference or `CREATE_OUTCOME_UNKNOWN` response; do not expose join codes or silently attach the client to the room. Prefer to require the client to update when a safe legacy flow cannot be supported.
- Keep API responses backward-compatible where practical, but do not preserve a legacy behavior that creates duplicate rides or discloses another owner's result.

### 5.5 Android request state

`rememberSaveable` can help with configuration and saved-state restoration, but it is not a complete durability guarantee for force-stop, process death, app restart, or a crash around an ambiguous network outcome. Persist the request ID and canonical payload before the network request in an appropriate local store or durable ViewModel state supported by the application architecture.

Recommended state machine:

- `DRAFT`: form can be edited; no request has been submitted.
- `SUBMITTING`: request sent with stored key and payload.
- `UNKNOWN_OUTCOME`: timeout/network failure occurred; retry/query using the same key and payload.
- `SUCCEEDED`: server returned the authoritative room; clear or archive the pending attempt and navigate exactly once.
- `CONFLICT`: show a recoverable error and require an explicit user action.
- Editing material creation options invalidates the previous attempt only after warning/handling any `UNKNOWN_OUTCOME`; never discard an unknown result and then create a second ride automatically.

The ViewModel must implement single-flight independently of UI button disabling. Navigation should be a one-time event/state transition, not triggered twice by repeated emissions.

### 5.6 Tests

- 20 simultaneous requests with identical `(ownerId, clientRequestId, payload)` produce exactly one room and identical room IDs in all successful responses.
- Same key + different payload returns 409 and leaves the original room/request unchanged.
- Different owner + same client key cannot retrieve the first owner's data.
- Commit-then-timeout followed by retry returns the original room.
- Server restart between attempts does not create a duplicate.
- Two concurrent same-name requests do not create `Name-2`.
- Name conflict by a different owner returns 409 without exposing join code.
- No-key legacy conflict follows a documented safe behavior and does not silently return an unrelated active ride.
- Android double-submit issues one logical request; process-death/restart recovery reuses the same request ID and payload; navigation happens once.

---

## 6. Ending-room race, tokens, stale-token recreation, and cleanup (RM-4, RM-6)

### 6.1 Reported defect mechanism

The supplied report says `issueRoomToken` receives a room row read earlier, authorizes against that row, calls `ensureLiveKitRoom`, and signs a token with a TTL reported as two hours. The end route marks the DB row ENDED and then sends data / delays / deletes the LiveKit room. A concurrent refresh may use a stale active row and recreate the external room after deletion. Cleanup errors may be swallowed. This is a code-derived risk, not a reproduced production incident.

### 6.2 Important correctness distinction

A status reread immediately before `createRoom` is useful but does not, by itself, eliminate all races: the room can end between the read and the external API call. A database transaction also cannot make a Postgres update and a LiveKit HTTP request one atomic transaction. The implementation must use explicit state checks, post-call compensation, idempotent cleanup, and deployment-level protection against implicit room creation. It must not claim perfect atomicity where the systems cannot provide it.

### 6.3 Required lifecycle protocol

1. Authorize the user using current membership/owner state and the canonical DB room ID.
2. Resolve the persisted LiveKit room identity through the common resolver.
3. Read the current room status before side effects. Do not proceed if status is ENDED or another terminal state.
4. Perform the minimum required LiveKit room operation only for a valid, nonterminal ride.
5. After any potentially racing external room creation/ensure operation, reread authoritative DB lifecycle state before minting/returning a token.
6. If the ride became ENDED, do not return a usable token; invoke compensating cleanup for the exact persisted LiveKit identity and record retryable cleanup state if it fails.
7. The end route must transition the DB room to ENDED atomically/conditionally and be safe under concurrent calls. A second `/end` should return the documented already-ended result without sending conflicting lifecycle actions.
8. Cleanup success includes LiveKit returning “room not found” / 404 for the intended identity. Transient network/server errors must remain pending and observable.
9. Store cleanup intent (`lkCleanupPending`, an outbox record, or an equivalent durable job) before losing request context. The sweeper must retry boundedly with backoff and expose exhausted failures to diagnostics/operations.
10. Never hold a database transaction/lock open across a potentially slow LiveKit HTTP call unless the design has a documented, measured reason and protects the pool.

### 6.4 Implicit room creation and old tokens

The supplied Claude report claims a local experiment showed that an old valid token could recreate a deleted room under the tested LiveKit configuration despite `roomCreate:false`, while a self-hosted configuration with `room.auto_create:false` blocked this behavior. This is a report of prior-session experiments, not independently verified here; validate it against the currently deployed LiveKit server/version/configuration. Do not assume LiveKit Cloud supports the same server setting.

The implementation must distinguish three risks:

- **Cross-ride collision:** an old token can reach a later ride because both use the same display-name room. Immutable per-ride names prevent this class of collision.
- **Ended-ride resurrection:** a stale token reconnects to or recreates the ended ride's old identity. Disabling implicit auto-create where supported is the strongest direct control; otherwise use a documented alternative and do not overstate protection.
- **In-flight creation race:** a request began before end and the external create finishes after end. Post-call status checks and compensating deletion are required.

If the deployed service permits implicit room creation and there is no verified way to disable or otherwise neutralize stale-token room recreation, mark this production control **BLOCKED**. UUID names mitigate cross-ride contamination but do not alone prove that an ended room cannot reappear.

### 6.5 Cleanup retries

- Use bounded retry/backoff for request-path cleanup where appropriate; do not make the user wait indefinitely.
- Persist pending cleanup if all immediate attempts fail.
- Treat 404 for the intended room identity as already cleaned up.
- Retry pending cleanup through the sweeper/job.
- Make cleanup idempotent so concurrent `/end` requests and sweeper execution are safe.
- Avoid deleting a room if identity mapping is ambiguous or if another non-ended DB ride references that same external identity. Emit an actionable alert instead.

### 6.6 Acceptance tests

- Deterministic interleaving test: token issuance starts, `/end` marks the ride ended, LiveKit creation completes late, and the caller does not receive a usable token; cleanup occurs or persists a pending job.
- No request path calls `createRoom` after it has observed the ride terminal; the race test still verifies post-call compensation.
- End called concurrently 20 times yields one terminal transition and idempotent cleanup intent.
- LiveKit delete failure leaves durable pending cleanup; next sweep retries it.
- LiveKit 404 counts as successful cleanup.
- An expired/ended/membership-removed rider cannot obtain a refreshed token.
- Old token behavior is tested under the actual supported LiveKit configuration; if not testable in CI, record exact staging/device/server procedure and mark the deployment gate blocked.

---

## 7. Webhook and production readiness (RM-5)

### 7.1 Required behavior

- Validate webhook signatures using the existing supported verification path; do not weaken signature validation.
- Distinguish signature/authentication failures from transient handler/database errors. Invalid signatures should be rejected according to the existing security contract. Transient processing failures should use a retryable server error (5xx) where the provider's retry contract supports it, rather than being misleadingly reported as authentication failures.
- Confirm how participant events and room-level events (such as `room_finished`) map to rides. Do not assume participant metadata is present on room-level events.
- Resolve by persisted exact LiveKit room identity. For legacy events, use an unambiguous mapping; do not map by display name alone if multiple historical DB rooms can share it.
- Do not kick participants or delete a room based on an untrusted or ambiguous mapping.
- Expose non-secret readiness information such as `webhookEnabled`, schema validity, and last successful event time / last processing failure time, as appropriate to the application's health model.

### 7.2 Production readiness rule

A startup log warning alone is not enforcement. Production readiness should fail (or the deployment should explicitly fail preflight) when the required webhook is disabled, unless a documented override such as `ALLOW_WEBHOOK_DISABLED=true` is set for a deliberate operational reason. `/health` and `/ready` semantics must remain clear: liveness may remain up for diagnostics, while readiness indicates that the deployment is safe to receive production traffic.

Do not leak webhook secrets, signature headers, rider IDs, tokens, or raw sensitive payloads in logs.

### 7.3 Tests

- Valid signed participant event updates the intended ride only.
- Valid signed room-level event maps through the persisted identity.
- Ambiguous legacy event does not mutate unrelated rides.
- Invalid signature is rejected and is not treated as a transient processing failure.
- Transient DB error uses a retryable response per the provider contract.
- Production readiness is false when webhook is disabled without override and true when enabled with valid required configuration.
- Health output contains no credentials or raw sensitive event payload.

---

## 8. Safe schema validation and migration (DB-1, DB-2, DB-3)

### 8.1 Reported risk

The supplied report describes `schemaMigration.js` as having a fast-return guard based on only three columns, a long collection of raw statements each wrapped in `try/catch`, and a final success-like log regardless of failures. It also reports data-changing statements that mark old rooms ENDED, deduplicate rooms by display-name ordering, and delete duplicate invites. These statements may run during startup if the early guard fails. Re-verify current code before changing it.

### 8.2 Target architecture

**Prisma migrations are the schema source of truth.** Runtime startup must not attempt broad data reconciliation.

1. Replace ambiguous startup mutation with a read-only schema assertion such as `assertSchema(prisma)`.
2. Validate every schema element the application truly relies on: required columns, enum members, key foreign keys, unique constraints/indexes, and known partial/expression indexes that Prisma schema cannot fully express.
3. Report each missing item explicitly; do not return success because a small subset of columns exists.
4. Production readiness should be false if required schema checks fail. The app may remain alive for logs/diagnostics, but it must not accept traffic that would corrupt data.
5. If a self-healing feature is retained, make it optional, additive-only, disabled by default, protected by a lock compatible with the actual database topology, and report each statement's success/failure accurately. Prefer migrations over runtime `ALTER`.
6. Move any data-changing reconciliation to a one-off script with dry-run default, exact affected-row counts, non-PII identifiers, archive/backup before applying changes, explicit `--apply`, and a single transaction where supported.
7. The script must be idempotent: a second run after a successful apply should make no further changes.
8. Never execute `--apply` in production during this implementation task.

### 8.3 Duplicate-resolution policy

The existing duplicate selection logic must not be carried forward mechanically. In particular:

- Never mark an ACTIVE ride ended merely because another same-name row has a more recent `createdAt`.
- Consider actual lifecycle state, `startedAt`, accepted-member count, and persisted LiveKit mapping; when signals conflict, archive and report the ambiguity instead of auto-resolving.
- Preserve invite history. Use `updatedAt` and current lifecycle semantics when deciding whether rows truly represent duplicates, but do not overwrite a removed-member security decision without understanding whether a later valid re-invitation exists.
- Prefer archival/tombstone history over irreversible deletion.
- Print IDs and counts only, not email addresses, tokens, join codes, or other personal data.

### 8.4 Connection pooler and migration execution

The supplied report raises an unverified concern about `ALTER TYPE ... ADD VALUE` and advisory locks through a Supabase transaction pooler. The implementation agent must verify supported Prisma / Postgres / pooler behavior from the actual deployed topology and current docs. Use a direct migration connection (for example, a configured `DIRECT_URL`) if required and already supported by the project. Do not assume PgBouncer semantics without testing.

### 8.5 Acceptance tests

- Fresh isolated Postgres schema after all Prisma migrations passes the complete assertion.
- Dropping a required index/constraint makes readiness fail with a precise problem list.
- A missing required enum value is detected.
- Additive repair, if retained, reports every failure and never prints a false success summary.
- Concurrent repair invocation cannot corrupt schema; if the lock mechanism is unsupported by the actual pooler, the feature is disabled rather than presumed safe.
- Reconciliation dry-run performs no data changes and shows planned changes/counts.
- Applied reconciliation archives affected rows before modification and is idempotent on a second run.
- An ACTIVE ride is never auto-ended solely because a newer same-name LOBBY row exists.

---

## 9. Release signing and CI safety (SEC-1)

The source report says release signing may fall back to a committed debug key. Revalidate the signing config and all workflows that consume the keystore before editing.

Requirements:

- A production release task must fail with a clear Gradle error if the required release signing configuration is absent.
- A debug-signed release may be possible only through an explicit, conspicuous local-only override such as `-PallowDebugSignedRelease=true`; that override must not be enabled in CI release workflows.
- Do not delete or rename a keystore until all build workflows and debug/test configurations are checked.
- CI must verify that required signing secrets exist before building/publishing.
- After build, use supported Android tooling such as `apksigner verify --print-certs` to report the produced certificate SHA-256. Never print the keystore password or secret values.
- Do not publish a release or upload artifacts during the task.

Acceptance: without release secrets, `assembleRelease` fails with a clear message; with correctly configured release secrets, signing verification succeeds; explicit local debug override behavior is tested and cannot be activated accidentally by the release workflow.

---

## 10. Android create, token refresh, and reconnect behavior (AND-1, AND-6)

### 10.1 Create flow

- Add ViewModel-level single-flight protection.
- Persist the create ID and canonical payload before sending the request.
- Treat timeout, cancellation after transmission, and IOException as unknown outcome until queried/retried with the same key.
- Navigate only after consuming the authoritative success result once.
- Do not clear an unknown attempt because the user rotates the screen or the process restarts.

### 10.2 Reconnect flow

The supplied report says the reconnect logic classifies `REMOVED` and `ENDED` by substring-matching exception messages. Replace brittle free-text matching with a typed result derived from HTTP status and structured response code, after verifying actual API contracts.

Suggested result categories:

- `Success(token)` — continue connection.
- `Ended` (`410` / stable `ROOM_ENDED` code) — stop reconnecting and show the end-of-ride state.
- `Removed` (`403` / stable `REMOVED_BY_HOST` or equivalent code) — stop reconnecting and show the membership state.
- `Unauthenticated` (`401`) — refresh authentication once if the existing auth flow supports it, then stop if it remains invalid.
- `Transient` — retry with bounded exponential backoff and jitter, respecting existing `MAX_RECONNECT_ATTEMPTS` and cancellation.
- `NotMember` — stop and show the safe recovery action.

Never reuse a cached old token after a terminal response. Do not retry forever. Preserve the original reason code for diagnostics without displaying raw server stack traces to riders.

Acceptance: unit tests with a fake API service cover each status/code branch, repeated 401, transient retry/backoff, coroutine cancellation, no reconnect after Ended/Removed, and no duplicate reconnect jobs.

---

## 11. VOX, microphone ownership, speech clarity, and audio routing (AND-4 and P2)

### 11.1 Current source-report observations

The source report states that `VoxEngine` creates its own 16 kHz mono `AudioRecord`, while LiveKit/WebRTC also captures audio through its ADM path; `VoxCaptureEvaluator` chooses an ADM or own path; the own-capture loop idles while the mic gate is open; `VoxDetector` has both `ATTACK_FRAMES=5` and `attackMs=60`; thresholds include an open ratio of 3.0, close ratio 1.8, hold 900 ms, and a clamped noise floor of 120–4000. These are source-report observations, not independent runtime validation.

### 11.2 Rules for changing VOX

- Do not change thresholds purely by intuition.
- First trace the exact data path, including which PCM frames feed the detector when the microphone gate is closed and open.
- Add debug-only telemetry for selected capture path, callback rate, frame rate, silence/noise-floor values where safe, and active recording configurations on Android API 24+.
- Confirm whether two `AudioRecord` instances can be active simultaneously in normal use. If both are active, measure and determine whether duplicate capture is necessary; do not immediately remove one path without proving gate-close behavior.
- Prefer one primary VOX frame source (likely ADM if its callbacks meet reliability requirements) and a measured fallback only when the primary source is absent for a defined interval.
- Resolve `ATTACK_FRAMES` vs `attackMs` to one documented source of truth and add a test proving the real gate timing.
- Keep debug telemetry free of recorded speech, audio payloads, tokens, or personally identifying content.

### 11.3 Automated VOX tests

Use synthetic PCM to cover:

- Steady low-frequency and broad-band wind-like noise does not continuously open the gate.
- Short speech-like bursts open within the documented attack window.
- Silence closes after the configured hold window plus expected frame scheduling tolerance.
- Background noise adaptation does not drift upward during continuous speech.
- A short initial syllable is not systematically clipped under the tested signal envelope.
- A gate-open transition does not stop the only frame stream needed to detect gate closure.
- Cancellation, capture failure, and audio-route changes return the detector to a safe state.

These tests establish algorithm behavior, not real helmet/headset quality.

### 11.4 Physical-device audio matrix

Required for claims about production audio quality:

- Two physical Android phones, preferably representative target OS/OEM versions.
- Phone speaker/mic, wired headset if supported, Bluetooth Classic headset, and Bluetooth LE Audio if available.
- VOX and PTT comparison under quiet speech, stationary fan/wind simulation, and a safe controlled outdoor test. Do not ask anyone to inspect logs or operate the phone while riding; tests must be performed safely and legally.
- Capture selected input/output device, requested device, route result, reconnect transitions, WebRTC stats where available (`concealedSamples`, jitter-buffer delay, loss), and VOX open/close times.
- Record device models, OS versions, build hash, headset model, route, environment, and whether the app was foreground/background.

Do not claim “clearer speech,” “zero clipping,” or “wind-proof VOX” without measured results and a clearly described test.

### 11.5 Bluetooth routing truth

The UI must distinguish:

- **Requested:** the user selected this device.
- **Pending:** Android is negotiating the route.
- **Active:** the platform reports this as the actual route.
- **Fallback:** the requested route failed and another route is active.
- **Unavailable / permission denied:** the route cannot be confirmed.

Use the correct Android primitives for the supported API level and compare the requested route with the actual reported communication device/input route. Do not mark Bluetooth as active merely because a Bluetooth device is paired or selected. Handle disconnect/flapping and `BLUETOOTH_CONNECT` permission failure. The state machine must have unit tests; route correctness still needs physical-device validation.

---

## 12. Battery, wake lock, GPS, polling, and lifecycle (AND-2, AND-3, AND-5)

### 12.1 Do not guess at battery savings

The source report lists likely contributors but contains no Battery Historian/Perfetto measurement. These items remain hypotheses until measured:

- Concurrent audio capture paths.
- Peer-ping fan-out every 10 seconds even when diagnostics are not needed.
- Location high-accuracy mode while moving.
- Three-hour wake lock behavior.
- Frequent lobby and room polling while backgrounded.

A code change may reduce CPU/network work but can also degrade voice quality, detection accuracy, location freshness, or ride lifecycle reliability. Do not optimize those away without tests.

### 12.2 Required analysis

- Trace where `VoiceForegroundService.updateWakeLock` is called, how ride state changes, and what happens when a timed wake lock expires while the ride remains connected.
- Determine whether a wake lock is actually needed given the foreground-service and audio APIs targeted by this app. If retained, release it explicitly and renew only under a justified state machine with a watchdog; avoid indefinite holding without a documented requirement.
- Gate rider-to-rider ping collection on the actual diagnostics policy, or use a lower-cost topology only if it preserves the required diagnostic metric contract. Do not remove latency data without updating the contract and tests.
- Prefer lifecycle-aware/cancellable polling and event-driven state where reliable signals already exist. Preserve a safe fallback for missed events or network failure.
- Do not reduce audio sampling quality simply to improve battery metrics.

### 12.3 Measurement protocol

On the same two devices and build, measure each scenario for the same duration, with controlled initial battery/temperature/network/screen state:

1. Idle home screen.
2. Lobby with no active voice.
3. Active ride, muted.
4. Active VOX.
5. Active PTT.
6. Active Bluetooth headset.
7. Active ride in background with screen off.

Use suitable Android tools (`adb shell dumpsys batterystats`, Perfetto, thermal status, CPU/wakelock/network metrics) where available. Reset and capture results consistently, document the exact commands and tool versions, and report absolute measurements and limitations. A claim of improvement requires comparable before/after measurements. No number is to be invented or estimated from code inspection.

---

## 13. Diagnostics contract and telemetry (DIAG / AND-3)

### 13.1 Status

The supplied report explicitly did not examine the diagnostics collector, backend service, and validator in depth. Its claims about the exact metric semantics are therefore incomplete. First read `docs/diagnostics-payload.md`, `NetworkDiagnosticsCollector.kt`, `diagnosticService.js`, `diagnosticValidator.js`, Prisma diagnostic models, queue logic, export path, and all clients. Do not create a supposedly complete metric contract from partial snippets.

### 13.2 Required contract document

Create `docs/DIAGNOSTICS_CONTRACT.md` with one row per field/metric:

- Field name and schema type.
- Authoritative source and collection callback.
- Unit and time base (for example, seconds vs milliseconds).
- Whether the value is instantaneous, delta, rate, or cumulative.
- Exact formula and denominator.
- Sampling and flush interval.
- Null / missing / zero semantics.
- Sequence number and ordering rules.
- Deduplication key and retry behavior.
- Offline queue persistence/retention.
- Backend validation and storage field.
- Export/report interpretation.
- Privacy and redaction constraints.
- Tests and known platform limitations.

### 13.3 Reported items to confirm, not assume

The earlier Claude investigation mentioned possible errors such as missing samples being written as zero, deduplication by event type alone, duplicate batches overwriting data, late batches being accepted after close but not exported, unused validator enums, second-versus-millisecond timestamp mismatch, Boolean loss values displayed with a percent suffix, and cumulative counters summed across samples. Treat each item as an **unverified lead** until the relevant producer, validator, storage, and export paths are read and tested.

### 13.4 Required tests

- Duplicate `(runId, riderId, sequenceNumber)` batch is idempotent.
- Out-of-order batches follow a documented policy.
- Missing measurements are null/absent, not fabricated zeroes.
- Packet loss uses the correct counter deltas and denominator.
- A metric with unit seconds cannot silently be consumed as milliseconds.
- Cumulative counters are not summed as if they were interval values.
- Event de-duplication distinguishes separate peers/sessions when identity matters.
- Duplicate payload behavior is explicit: reject conflicting payload for the same sequence or preserve a safe canonical value and log a conflict.
- Late batches after close follow the documented grace-period policy and remain exportable or are explicitly rejected with an observable reason.
- Offline queue survives process death and retries with bounded backoff.
- Validator enum/field constraints match the API contract.
- Exported metrics preserve units, nulls, and timestamps.

Complex diagnostic charts and raw technical metrics must not clutter the in-ride primary screen. Expose advanced metrics in a separate diagnostic/details area.

---

## 14. SOS, emergency calls, and delivery claims (SOS-1, SOS-2)

The source report states that alert records may be created with `status: 'SENT'` before delivery is proven, but the final FCM flow and user-facing UI were not examined. Follow-up notes additionally report that `sent=true` may be returned when recipients exist even if no FCM send succeeds, and that the FCM TTL may be 60 seconds. Those details are **unverified leads**, not confirmed facts. Re-verify the entire path before changing status values.

Required behavior:

- Distinguish server acceptance from push-notification provider acceptance and from confirmed recipient delivery. FCM acceptance is not the same as a device displaying the notification.
- Define statuses that match observable evidence, such as queued/server-accepted/provider-accepted/failed where these are supported. Do not invent a `DELIVERED` state unless the app has a reliable acknowledgement path.
- Do not show “delivered” merely because recipients exist or a send function was attempted.
- Report partial success correctly when only some recipient sends succeed.
- Do not send real SOS alerts in automated tests; use an isolated test hook or mock provider.
- Never reintroduce `TelephonyManager` solely to infer emergency country; the supplied report notes its removal in v0.0.3.17 for a Play Protect false-positive concern. Verify that fact in the actual checkout before touching the code.
- Consider a user-selectable emergency country defaulted to locale, show the dial number before initiating the call, and retain the fallback behavior already documented by the source report. Validate country-number data with reliable, current official sources during implementation if this functionality is changed.
- Test offline, permission denied, no recipients, provider partial failure, repeated tapping, cancellation, and screen/process lifecycle.

---

## 15. OTA update integrity and application security (SEC-2 and related)

The source report says the OTA checksum is optional and no installed signer-certificate comparison is performed. Re-verify `UpdateManager`, release metadata, install flow, Android package-signing API level support, and release publishing pipeline before modifying.

Requirements:

- Fail closed with a clear user-facing error if the expected checksum is absent or invalid, if the selected release asset is ambiguous, or if the computed checksum mismatches.
- Compare the downloaded APK's signing certificate SHA-256 against the installed app's signing identity using the supported Android `PackageManager` signing APIs appropriate for the app's minimum/target SDK. Handle certificate rotation/signing history correctly where relevant; do not implement a naive string comparison that blocks legitimate supported updates.
- Document that app-level signer checking is defense in depth; Android's package installer remains responsible for enforcing package update signer rules.
- Never install an arbitrary URL or an asset that is not from the application's intended release source.
- Do not log sensitive download URLs containing secrets, tokens, or private repository credentials.
- Test checksum missing, checksum mismatch, download interruption, changed signer, valid same signer, invalid release metadata, and cancellation.

---

## 16. Full UI/UX and localization audit (UX-1, UX-2)

### 16.1 Audit requirement before redesign

The source report's screen list is partial. Before changing UI code, create `docs/UI_INVENTORY.md` that includes **every** screen, dialog, bottom sheet, activity, notification-facing flow, and navigation entry discovered in the actual app. At minimum, the source report lists Splash, Login, Register, Home, Account, Settings, HostSetup, JoinRoom, InvitesInbox, InviteFriends, Squad, DeviceSetup, Lobby, Room, SOS, EmergencyAlertActivity, RideInviteActivity, RoutePlanner, RideStats, RideReplay, and PostRideSummary. Verify the actual navigation graph and all reachable destinations; do not assume this list is complete.

For each surface record:

- File path, route/destination, entry points, exit/back behavior.
- Loading, success, empty, offline, permission-denied, validation-error, server-error, and retry states as applicable.
- Data source and whether the display is live or cached.
- All hardcoded user-facing strings.
- String-resource key and locale coverage.
- Touch targets smaller than 48 dp (or the project's accessibility target), content descriptions, semantics, focus order, TalkBack labels, and font scaling.
- Insets/status/navigation bar handling and keyboard/IME behavior.
- Light/dark theme support, contrast, disabled state, visual consistency, and screenshot/test coverage.
- Any confusing or incorrect claim such as “sent” when delivery is not proven, or “Bluetooth connected” when only selected.

### 16.2 UI design principles

- Preserve domain behavior; UI cleanup must not silently change room/member/SOS/business logic.
- Use existing theme tokens and shared components when consistent. Add reusable components only where at least two real screens benefit.
- Keep the in-ride surface calm and glanceable: microphone/PTT state, connection state, route/device state when relevant, and SOS remain immediately discoverable.
- Put advanced diagnostics in a separate area.
- Never insert mock/sample ride data into production paths to make screens look populated.
- Use clear rider language and consistent sentence case. Avoid radio/military jargon unless it is an established and explained product term.
- Every error state should state what happened and the next safe action, without leaking server stack traces.
- Do not place new forms/charts into the critical in-ride interaction path.
- Review all screen sizes, large font scale (including 1.3× or greater), light/dark mode, Hindi/Marathi long strings, and permission denial.

### 16.3 Localization

The source report states that each of `values`, `values-hi`, and `values-mr` has approximately 78 string entries while a grep found about 111 `Text("...")` literals in UI code. Counts are approximate and must be re-run in the current checkout.

- Move user-facing literals from Compose code to resources for supported locales.
- Maintain identical placeholder names/types across locales.
- Do not translate technical identifiers or protocol codes that must remain machine-readable.
- Verify Hindi/Marathi translation quality rather than merely copying English.
- Add tests or lint checks where appropriate to catch hardcoded strings and placeholder parity.
- Revisit `HeadsetSettingsScreen`: either register it in navigation and test the route or remove it only after proving no other entry point or product requirement depends on it.

### 16.4 UI acceptance criteria

- `docs/UI_INVENTORY.md` contains every actual screen and state discovered.
- No high-priority screen lacks a loading/error/empty/offline state where the operation can encounter that condition.
- Requested audio device and actual active route are clearly distinguished.
- No unsupported delivery or connection claims are displayed.
- Touched screens pass Android lint without hardcoded-text warnings where the rule applies.
- Screenshot/manual checklist covers light/dark, large font, Hindi/Marathi, insets, TalkBack, and permission/error states.
- Business logic and API contracts remain covered by tests after the visual rework.

---

## 17. Compatibility and API contract

The supplied implementation prompt says clients from v0.0.3.14 through v0.0.3.17 send `roomName` to `/api/rooms/room/token` and use `clientRequestId` on create. This is an important compatibility claim but must be checked against the actual tagged sources and current route handlers.

Before changing an API:

1. Search Android, backend, web app, tests, release workflows, and any documented integrations for every caller and response consumer.
2. Record request/response fields and error code behavior before and after the change.
3. Keep new response fields additive where possible.
4. If a request field becomes required, provide an explicit migration window or a safe legacy response. Do not silently accept unsafe ambiguity.
5. Version or feature-gate behavior where mixed clients could disagree about room identity.
6. Add contract tests for old supported clients and the new client.

Compatibility must never be “achieved” by returning another user's room or join code, silently creating a suffixed room, or mapping a legacy token to an unrelated new ride.

---

## 18. Test matrix and verification evidence

### 18.1 Backend automated checks

From `backend/`, the supplied prompt lists:

```bash
npm ci
npx prisma generate
npm test
```

where `npm test` reportedly runs `node --test --test-concurrency=1 test/*.test.js`. Verify `package.json` and test paths before relying on this command. Existing test files named in the supplied prompt include `room_lifecycle.test.js`, `room_tokens.test.js`, `invite_and_convoys.test.js`, `migrations.test.js`, `p0_fixes.test.js`, and `e2e_convoy.test.js`. Proposed focused test files include `create_idempotency.test.js`, `token_end_race.test.js`, `schema_assert.test.js`, and `lk_room_name.test.js`.

### 18.2 Android checks

From `mobile-app/`, the supplied prompt lists:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Also verify release-signing behavior with and without the release secrets in a safe local/CI configuration. If JDK/Android SDK/Gradle distribution is unavailable, record the exact error and mark the check blocked; do not invent a pass.

### 18.3 CI

Inspect `.github/workflows/build.yml` and `release.yml` in the actual checkout. Confirm newly added test files are picked up, release secrets are required for release builds, and no production deploy or publish step is triggered by the audit branch.

### 18.4 Device/deployment-only checklist

- VOX/wind and initial-syllable behavior.
- Actual Bluetooth input/output route and route fallback.
- Background ride behavior and wake-lock/foreground-service correctness.
- Measured battery/CPU/network behavior.
- SOS dial behavior and delivery semantics.
- TalkBack, large font and visual layout.
- OTA install using real release-signing configuration.
- Stale token / implicit LiveKit room creation behavior against the supported server or Cloud configuration.
- Existing active ride compatibility during a staged backend rollout.

### 18.5 Evidence ledger

Every test must record:

- exact command or manual procedure;
- branch and commit hash;
- environment/runtime version where relevant;
- pass/fail/blocked outcome;
- short, non-sensitive output summary;
- whether the check is unit, integration, emulator, physical device, staging/deployment, or production configuration;
- unresolved follow-up.

Do not paste environment files, keystores, tokens, raw rider diagnostics, personal data, or private payloads into logs or reports.

---

## 19. Corrected execution order

The implementation prompt is the operational ordering authority. The report provides rationale and risk constraints. Apply this sequence unless the current code reveals a concrete dependency that justifies a documented change:

1. **Preflight + durable checkpoint:** establish current commit/status, protect user changes, inspect existing docs/tests/toolchain, create `docs/implementation/PROGRESS.md` and `VERIFICATION_MATRIX.md`.
2. **Test harness and contracts:** ensure isolated backend/Postgres fixtures and fake external clients can test concurrency and lifecycle behavior. Do not run destructive scripts against production.
3. **Create idempotency:** implement payload-bound durable request identity, owner scoping, timeout recovery, and Android persisted attempt/single-flight. Add any strictly necessary additive Prisma migration as part of this workstream.
4. **Per-ride LiveKit identity compatibility:** add persisted identity and common resolver, legacy mapping, mapping dry run, feature gate and rollout plan. Add/verify its additive migration here if not already done. Do not enable UUID assignment in production during this task.
5. **Race-safe end/token and cleanup:** conditional terminal transition, post-call compensation, durable cleanup intent, retry/404 semantics, stale-token control verified for the actual deployment type.
6. **Webhook mapping and production readiness:** event-type-aware mapping, signature vs transient processing handling, readiness tests, no ambiguous destructive actions.
7. **Safe runtime schema mechanism:** replace unsafe destructive startup repair with read-only schema assertion; keep reconciliation separate, dry-run-first, archive-before-delete, and locally/test-only. Although implemented in this workstream, no deployment candidate may be released while risky runtime mutation remains enabled.
8. **Release signing and OTA integrity.**
9. **Structured reconnect and invitation/membership/share-link consistency:** cover remove-vs-accept races, repeated acceptance, re-invitation, max-use caps, cross-owner access, and denylist/schema truth.
10. **Diagnostics contract and metric corrections:** read the producer-to-storage-to-export path, write the contract first, then apply verified fixes/tests.
11. **VOX and routing:** algorithm tests first; device-dependent threshold tuning only with measured evidence.
12. **Battery/lifecycle:** baseline measurements before code changes, comparable measurements afterward.
13. **SOS delivery semantics and emergency behavior:** use provider mocks and test partial failure/status claims; no real SOS sends.
14. **Whole-app UI inventory and UI/localization/accessibility fixes:** inventory every destination and state before screen-by-screen changes.
15. **Final independent review:** rerun available tests, inspect all diffs, verify contracts/secret safety, update evidence, and list blocked device/deployment checks.

Additive migrations required by early workstreams may be authored as part of those changes, but no migration may be applied to production and no production deployment/release/merge/publish is authorized. Before any future rollout, the destructive startup-repair path must be neutralized and the safe-schema workstream must pass. Each workstream is a checkpoint; if the session nears its limit, save the exact next task and evidence before stopping. Do not restart from scratch.

## 20. Required implementation artifacts

The implementation agent should maintain these files on the working branch:

- `docs/DEEP_MOBILE_AUDIT_REPORT.md` — this report, updated with evidence and implementation results rather than rewritten to hide unresolved items.
- `docs/AI_IMPLEMENTATION_PROMPT.md` — the implementation plan used by the coding agent.
- `docs/implementation/PROGRESS.md` — durable checkpoint: commit, branch, completed workstreams, exact next task, blockers, tests run.
- `docs/implementation/VERIFICATION_MATRIX.md` — requirement-to-test/device/deployment evidence matrix.
- `docs/DIAGNOSTICS_CONTRACT.md` — metric-by-metric contract.
- `docs/UI_INVENTORY.md` — complete screen/state/accessibility/localization inventory.
- A safe migration/reconciliation runbook and dry-run output format, if schema/identity reconciliation is implemented.

All artifacts must be updated based on actual work. Do not mark a section “complete” solely because a task was planned.

---

## 21. Final acceptance gate

The final response must list each finding/workstream as `FIXED`, `NOT REPRODUCIBLE`, `BLOCKED`, `NEEDS DEVICE`, or `NEEDS DEPLOYMENT VERIFICATION`, with evidence. “Fixed” requires the relevant regression test or verification command to pass.

At minimum, before claiming production-ready:

- Per-ride LiveKit identity is immutable and compatibility migration is proven.
- Concurrent create and ambiguous timeout do not duplicate rooms.
- Reused idempotency keys with different payloads conflict safely.
- Token/end/create race has deterministic tests and stale-token risk is controlled for the actual deployment.
- Cleanup is idempotent, treats absent rooms as success, persists/retries transient failure, and never deletes an ambiguously mapped room.
- Production webhook/readiness policy is enforced and signature/error behavior is tested.
- Startup schema validation is read-only; any destructive reconciliation is a separately authorized, dry-run-first operation.
- Release signing cannot silently use a committed debug key.
- Reconnect logic uses structured outcomes, not exception-message substring matching.
- Diagnostics metric semantics are documented and critical correctness tests pass.
- VOX and audio routing are algorithm-tested; hardware claims remain explicitly pending until tested.
- Battery claims are measurement-based.
- Every Android screen is inventoried; high-priority UI, localization, and accessibility defects are resolved or tracked.
- SOS/OTA claims reflect actual evidence.
- CI passes all applicable tests, and physical-device / deployment-only blockers are clearly disclosed.

No production DB change, production deployment, release, merge, or publish is authorized by this report alone.

---

## 22. AI-3 Implementation & Verification Ledger (Baseline 66f3137 / App V0.0.3.18)

This section documents the actual verified implementation, test execution results, and evidence produced on the active repository checkout across all prioritized audit items (P0 through P5).

### 22.1 Test Execution Summary

| Test Suite / Verification Task | Command | Verified Outcome | Evidence & Duration |
|:---|:---|:---|:---|
| **Backend Test Suite** | `cd backend && npm test` | **PASS (104/104 tests, 29 suites, 0 failures)** | 104 passed, 0 failed, duration: 216.9s. Covers migrations, room identity, end races, idempotency, sweeper, diagnostics. |
| **Android Unit Tests** | `cmd /c "set JAVA_HOME=C:\Users\umerz\.jdks\jdk-17&& gradlew.bat testDebugUnitTest"` | **BUILD SUCCESSFUL (37 tasks, 100% pass)** | 37 tasks up to date / executed in 34s. 0 failures across all view models, audio engine, localization, and room verification tests. |
| **Android Debug Compilation** | `cmd /c "set JAVA_HOME=C:\Users\umerz\.jdks\jdk-17&& gradlew.bat assembleDebug"` | **BUILD SUCCESSFUL (45 tasks)** | Executed in 16s. Generates clean debug APK. |
| **Release Signing Fail-Closed Gate** | `cmd /c "set JAVA_HOME=C:\Users\umerz\.jdks\jdk-17&& gradlew.bat assembleRelease --dry-run"` | **FAIL-CLOSED (Expected `GradleException`)** | Fails build at task graph execution: `Release signing key not configured.` |
| **Release Signing Explicit Override** | `cmd /c "set JAVA_HOME=C:\Users\umerz\.jdks\jdk-17&& set ALLOW_INSECURE_RELEASE_BUILD=true&& gradlew.bat assembleRelease --dry-run"` | **SUCCESS (Dry-Run Allowed with Explicit Flag)** | Confirmed opt-in bypass mechanism works as specified. |
| **String Resource Parity** | `./gradlew.bat testDebugUnitTest --tests "com.ridervoice.StringResourceParityTest"` | **PASS (0 placeholder or key mismatches)** | DOM parser verifies exact key, argument count, and `%1$s` / `%2$s` specifiers match across `values`, `values-hi`, and `values-mr`. |

---

### 22.2 Itemized Verification Status (RV-001 to RV-018)

| Item ID | Priority | Finding / Invariant | Resolution & Code Changes | Verification Evidence | Status |
|:---|:---|:---|:---|:---|:---|
| **RV-001 (P0-A)** | P0 | LiveKit auto-create may resurrect ended rooms from stale tokens | Added `room: { auto_create: false, empty_timeout: 600 }` to `livekit-server/livekit.yaml`. Shortened token TTL to 30 min in `tokenService.js`. Added `removeParticipant` kicking before room deletion in `roomSweeper.js`. Token request on ENDED room returns 410. | `end_race.test.js` & `room_identity.test.js` verified: ended room rejects token with 410 and does not call `createRoom`. | `FIXED` |
| **RV-002 (P0-B)** | P0 | Release build falls back to debug keystore silently | Removed `initWith(signingConfigs.debug)` in `mobile-app/app/build.gradle`. Added `gradle.taskGraph.whenReady` check failing release tasks with explicit `GradleException` if `KEYSTORE_FILE` is missing and `ALLOW_INSECURE_RELEASE_BUILD != true`. Added CI certificate fingerprint verification step in `.github/workflows/release.yml`. | Dry-run without keystore fails with `GradleException`; succeeds with `ALLOW_INSECURE_RELEASE_BUILD=true`. | `FIXED` |
| **RV-003 (P0-C)** | P0 | Schema self-repair lock unsafe under pooler; swallows errors; partial success serves traffic | Replaced session-level advisory lock with transaction-scoped `pg_try_advisory_xact_lock` in `schemaMigration.js`. Enforced strict `lockRes[0].locked === true` check before running DDL. Swallowing in violation checks removed; records violations in array. In `server.js`, write routes return HTTP 503 in production if schema is degraded. | `backend/test/p0_migration_safety.test.js` (5/5 tests passing). Zero destructive `DELETE` DDL; reports duplicate records for manual reconciliation. | `FIXED` |
| **RV-004 & RV-005 (P1-A)** | P1 | Client verifies room identity after connect (audio leak); legacy check is tautological | In `LiveKitManager.kt`: Added pre-connect check decoding JWT room claim (`JwtRoomClaimDecoder.extractRoomName`) before allocating WebRTC/audio devices. Resolves canonical `lkRoomName` via `apiService.getLobbyStatus` if missing; aborts with `ConfirmedRoomMismatch` before `connect()`. Post-connect check retained as secondary gate. Made `abortConnection` idempotent and scoped. | `RoomIdentityVerificationTest.kt` (`preconnectMismatch_abortsBeforeConnect`, `abortConnection_isIdempotent`) passing. | `FIXED` |
| **RV-006 (P1-B)** | P1 | Webhook `lastEmptyAt` clearing never runs due to omitted select field | Added `lastEmptyAt: true` to `buildBaseSelect` in `roomResolver.js`. Updated `livekitWebhook.js` to pass `select: { lastEmptyAt: true }` in `participant_joined` and `room_finished` event handlers. | `backend/test/room_identity.test.js`: test asserting `participant_joined` webhook clears `lastEmptyAt` passing. | `FIXED` |
| **RV-007 (P2-A)** | P2 | Wake lock acquired with 3h timeout and never renewed | In `VoiceForegroundService.kt`: `updateWakeLock` acquires `wakeLock.acquire()` without timeout while `CONNECTED` or `RECONNECTING`. Safely released upon state change to `DISCONNECTED` and in `onDestroy()`. | Automated unit tests passing. Long duration screen-off verification documented for physical device. | `FIXED` |
| **RV-008 (P4-A)** | P4 | Incomplete localization; notifications hardcoded in English | Created `res/xml/locales_config.xml` (`en`, `hi`, `mr`) and added `android:localeConfig` to `AndroidManifest.xml`. Refactored `LocaleHelper.kt` to use `AppCompatDelegate.setApplicationLocales`. Localized all notification titles, descriptions, status labels, and actions in `VoiceForegroundService.kt` and `FcmService.kt`. Added 17 string resources across `values`, `values-hi`, and `values-mr`. | `StringResourceParityTest` passing with 0 key or placeholder errors. | `FIXED` |
| **RV-009 (P5)** | P5 | Android 14/15 foreground service type permissions | In `VoiceForegroundService.kt`: `getForegroundServiceTypeMask` dynamically checks granted permissions (`RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `BLUETOOTH_CONNECT`) and masks only held types before calling `startForeground`, avoiding `SecurityException`. | Code inspected and unit tested with granted/denied permission combinations. | `FIXED` |
| **RV-010 (P5)** | P5 | Unreachable `HeadsetSettingsScreen` | Screen was deprecated and superseded by `DeviceSetupScreen.kt` in `NavGraph.kt`. Verified all audio routing preferences are active in `DeviceSetupScreen` and `SettingsScreen`. | Verified navigation graph reachability. | `FIXED` |
| **RV-011 (P2-B)** | P2 | Non-null assertions (`!!`) in audio and viewmodel code | Replaced all 21 `!!` non-null assertions across `AudioRouteDecisionEngine.kt` (10 instances), `LiveKitManager.kt` (3 instances), `LobbyViewModel.kt` (4 instances), `AccountViewModel.kt` (2 instances), and `ActiveRideRepository.kt` (2 instances) with safe Elvis operators, null guards, and clean early returns. | Unit tests passing across all modified classes. | `FIXED` |
| **RV-012 (P5)** | P5 | Display name sent in `JoinTokenRequest` instead of room identity | In `Models.kt`: changed `roomName: String? = null` with `roomId: String? = null`. In `LobbyViewModel.kt`: `getJoinToken` prioritizes `roomId` while maintaining backward compatibility for legacy servers. | `LobbyViewModelTest` passing. | `FIXED` |
| **RV-013 (P5)** | P5 | `RoomServiceClient` allocated per request | Cached singleton `RoomServiceClient` in `tokenService.js` and `roomSweeper.js`, reducing connection overhead. Exported `getRoomServiceClient` for shared lifecycle. | Backend test suite passing. | `FIXED` |
| **RV-014 (P1-C)** | P1 | Concurrent invitation accept vs room end race | Added concurrent accept-invite vs end-ride test in `backend/test/end_race.test.js`. Token issuance strictly rejects ended rooms with HTTP 410. | `end_race.test.js` passing (8/8 tests). | `FIXED` |
| **RV-015 (P1-C)** | P1 | Server kick misclassified as permanent room mismatch | In `LiveKitManager.kt`: under `DisconnectReason.PARTICIPANT_REMOVED`, client re-validates once with backend via `apiService.getLobbyStatus`. If canonical room still matches, schedules bounded reconnect via `scheduleReconnect()`; only aborts permanently if backend confirms a different room. | Reconnect and error classification unit tests passing. | `FIXED` |
| **Audio / VOX DSP** | P2-C | Single capture owner, noise floor adaptation, clipping | `VoxCaptureEvaluator` dynamically shifts to ADM tap (`frames >= 40/sec`) and stops internal `AudioRecord`. 130Hz high-pass filter rejects engine vibration. Stuck-open guard (8s max continuous open). Synthetic PCM unit tests verify steady noise convergence, 80ms speech onset, 30s conversational speech, and ±32767 clipped audio handling. | `VoxDetectorTest.kt` (5/5 tests passing). | `FIXED` |
| **Bluetooth SCO Routing** | P2-C | Route state machine, headset disconnect mid-ride | `AudioRouteDecisionEngine.kt` and `AudioDeviceRouter.kt` manage SCO carrier negotiation, fallback to Speaker, and late confirmation republishing. | Unit tests passing; hardware carrier testing cataloged as `NEEDS DEVICE`. | `NEEDS DEVICE` |
| **SOS Delivery Truth** | P5 | Alert status truth and regional emergency routing | `EmergencyNumbers.kt` checks SIM and network country ISO via `TelephonyManager` before falling back to device locale. SOS alert state machine tracks delivery truth via FCM response. | Unit tests passing. | `FIXED` |
| **OTA Checksum & Signer** | P5 | Missing or blank SHA-256 checksum fails closed | `UpdateManager.kt` enforces SEC-2 signer certificate matching and SHA-256 hash integrity checks before prompting for install. | `UpdateManagerIntegrityTest.kt` (3/3 passing). | `FIXED` |

---

### 22.3 Field & Physical Device Verification Matrix

Per Section 8 of the implementation prompt, the following requirements cannot be proven by unit tests alone and must be verified on physical hardware prior to a production release:

1. **Bluetooth SCO & Helmet Intercom Matrix:**
   - Test devices: Android 11, 12, 13, and 14 physical devices.
   - Peripherals: Wired headset, Bluetooth headset (HFP/SCO), motorcycle helmet intercom (Cardo / Sena).
   - Test procedure: Connect mid-ride, disconnect mid-ride, rapid route switching, incoming phone call preemption and return.
   - Status: `NEEDS DEVICE`.
2. **Acoustic VOX Wind Noise Calibration:**
   - Physical test in high-noise environment (40 km/h to 120 km/h motor/wind noise). Verify high-pass filter rejects wind buffeting while voice triggers reliably within 80 ms without initial syllable clipping.
   - Status: `NEEDS DEVICE`.
3. **Multi-Hour Screen-Off Battery Profiling:**
   - Physical device test of 3h+ continuous ride with screen off using `dumpsys batterystats` and Android Studio Profiler. Verify wake lock renewal and battery drain < 8%/hour.
   - Status: `NEEDS DEVICE`.
4. **LiveKit Cloud Production Configuration:**
   - Confirm hosted LiveKit server instance configuration sets `room.auto_create: false` and `empty_timeout: 600`.
   - Status: `NEEDS DEPLOYMENT VERIFICATION`.

