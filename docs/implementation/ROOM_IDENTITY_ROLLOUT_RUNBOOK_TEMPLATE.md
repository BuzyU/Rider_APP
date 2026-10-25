# RiderVoice LiveKit Room-Identity Rollout Runbook Template

**Status:** Template only — not an execution record.  
**Production actions:** None are authorized by this document.  
**Owner / approver:** To be assigned by the repository owner.  
**Target environment:** Fill in only after an authorized operator identifies the exact environment.  
**Change / release:** Fill in during the approved deployment process.

> Do not use this document as permission to connect to production, change configuration, pause ride creation, run a backfill, end rooms, delete rooms, or deploy. Complete the read-only gates below first and obtain the required operational approval.

---

## 1. Purpose and required invariant

Migrate from configuration-dependent or display-name-derived LiveKit room identities to an immutable persisted identity per ride, while allowing in-progress legacy rides and supported old clients to continue using their existing room identity.

For one ride, all authorized participants and every token issue/refresh, webhook, start, end, sweep, and cleanup operation must use the same external LiveKit room identity. Two different rides must never share one LiveKit room identity. A display-name change, retry, deployment, rollback, or environment flag change must not silently alter a ride's identity.

A UUID name prevents a later ride from accidentally sharing a legacy display-name room, but UUID naming alone does not prove that a stale token cannot recreate an ended ride. Verify and control implicit room creation separately.

## 2. Roles and approvals

- **Application owner:** approves user impact and any temporary creation pause.
- **Backend owner:** verifies compatibility version, schema, endpoints, and roll-forward/rollback builds.
- **LiveKit operator:** verifies actual room inventory, room-creation configuration, token and webhook behavior.
- **Database operator:** approves read-only inspection and any authorized mapping backfill. No destructive reconciliation is part of this plan.
- **Release operator:** verifies deployment order, replica drain, readiness, and rollback target.

No single unreviewed script should perform all of these steps automatically.

## 3. Preflight: read-only evidence capture

Record the timestamp, environment name, application release/commit, and operator responsible. Do not include credentials or tokens in the record.

### 3.1 Application deployment inventory

- [ ] Enumerate all serving backend replicas, their versions/commit hashes and configuration state without printing secret values.
- [ ] Determine whether deployment strategy allows old and new replicas to serve requests concurrently.
- [ ] Identify a compatibility release that reads the persisted identity but keeps old-room support.
- [ ] Confirm the final rollback target is the compatibility release, not a pre-compatibility build.
- [ ] Verify API and client support for the current minimum supported Android versions.

### 3.2 Effective identity configuration

- [ ] Determine the effective `ROOM_ID_MODE` / replacement feature-gate value on every replica.
- [ ] Record whether that value has changed historically if reliable audit evidence is available.
- [ ] Do not infer every existing ride's historical identity solely from today's environment variable.
- [ ] Determine whether any replica can still derive token room names directly from `Room.name`.

### 3.3 LiveKit configuration and observations

- [ ] Verify the actual LiveKit deployment type and server version.
- [ ] Verify whether implicit room creation on participant join can be disabled in that deployment.
- [ ] If self-hosted, verify the effective `room.auto_create` configuration using an authorized test procedure.
- [ ] If Cloud-hosted, do not assume a self-hosted setting exists; establish the supported equivalent or record the limitation.
- [ ] Read the current room inventory and sufficient metadata to correlate each active ride to a room.
- [ ] Verify `roomCreate:false` behavior separately from implicit room-creation behavior.
- [ ] Verify webhook delivery/signature configuration and whether room-level events carry the fields required by the mapping strategy.
- [ ] Record findings without tokens, signing keys, private payloads, rider names, or personal data.

### 3.4 Database evidence

- [ ] Confirm schema/migration version and whether the persisted identity field exists.
- [ ] Read current non-ended Room IDs/status/display names using an approved read-only path.
- [ ] Produce an identity mapping dry-run report with candidate identity, evidence source, confidence, and ambiguity reason.
- [ ] Check whether multiple non-ended rows map to one LiveKit identity.
- [ ] Check whether a room display name collides with an external legacy identity currently in use.
- [ ] Do not print join codes, emails, tokens, or personal information.
- [ ] Do not write mappings or execute reconciliation until a separate approval and a reviewed plan exists.

### 3.5 Preflight gate

Proceed only when:

- [ ] The compatibility build is identified and tested.
- [ ] Replica inventory is complete.
- [ ] The actual implicit-create risk is understood, or the deployment is explicitly marked BLOCKED.
- [ ] Every currently active ride has a verified identity or a documented manual-resolution blocker.
- [ ] There is a tested rollback target compatible with persisted identities.
- [ ] Required backups and change approvals are confirmed by the responsible operator.

If a gate is not met, stop. Do not guess an identity and do not change the production feature flag.

## 4. Code/migration readiness (must pass before deployment)

- [ ] Additive schema migration exists and has passed on isolated Postgres.
- [ ] Runtime startup uses read-only schema assertion for required schema objects.
- [ ] The common identity resolver is used by token issuance, start, end, sweeper, webhook, kick, list, and delete paths.
- [ ] Existing rooms with a verified persisted identity never have it rewritten by ordinary request handling.
- [ ] New room creation and identity assignment are atomic in the database.
- [ ] Unresolved identity returns a safe operational error rather than a guessed target.
- [ ] An active-room uniqueness invariant exists where supported; schema design does not impose an invalid global unique restriction over historical ended rides.
- [ ] Same-ride concurrent create/token operations cannot produce split identities.
- [ ] `deleteRoom` 404 is interpreted as already absent for that exact room.
- [ ] Ambiguous mappings never trigger delete/kick/end operations.
- [ ] All critical automated tests have passed and are captured in `VERIFICATION_MATRIX.md`.

## 5. Deployment sequence

### Stage 1 — Compatibility release, UUID creation disabled

- [ ] Approve temporary creation behavior: preferably pause new ride creation during the mixed-version backend rollout while allowing existing rides to continue.
- [ ] If creation cannot be paused, use an alternative rollout with a deterministic, tested guarantee that every old/new replica resolves identities identically. Merely assuming sticky load balancing is not sufficient.
- [ ] Deploy the compatibility release.
- [ ] Keep the new UUID assignment feature gate disabled.
- [ ] Confirm every serving replica reports the compatible version.
- [ ] Confirm no legacy replica remains registered or able to receive traffic.
- [ ] Confirm existing active rides remain connected/reconnectable in staging or a production-safe approved validation plan.

**Gate:** Do not create UUID-identity rides until all serving replicas understand persisted identity.

### Stage 2 — Resolve and persist legacy mappings

- [ ] Review the dry-run mapping report.
- [ ] Resolve every active ride mapping using authoritative evidence.
- [ ] Apply only reviewed, conditional compare-and-set mapping writes with a reviewed command/script.
- [ ] Verify affected row counts and re-read the mapping state.
- [ ] Confirm no active ride has a conflicting identity.
- [ ] Do not automatically map ambiguous rows; escalate them for manual resolution.
- [ ] Keep an audit record of the mapping decisions without sensitive values.

**Gate:** If an active ride remains ambiguous, block UUID rollout or use an approved plan that isolates it without changing its current identity.

### Stage 3 — Enable UUID for new rides

- [ ] Confirm all replicas run the compatibility release.
- [ ] Confirm all new ride creations persist their UUID identity atomically.
- [ ] Confirm existing mapped rides continue using their previous persisted names.
- [ ] Enable the UUID-for-new-rides flag through the approved config workflow.
- [ ] Resume new ride creation if it was paused.
- [ ] Validate a controlled test ride: host, invitee, join code, share link, token refresh, end, webhook, sweep/cleanup.
- [ ] Verify two rides with the same display name receive different LiveKit names.
- [ ] Check non-sensitive operational metrics and logs for split identity, unresolved mapping, failed cleanup, webhook mapping failure, and stale-token symptoms.

### Stage 4 — Observation window

- [ ] Monitor legacy active room count and confirm it declines naturally as rides end.
- [ ] Monitor unresolved identities and pending cleanup.
- [ ] Confirm room-ended/removed reconnect behavior using test riders in a controlled environment.
- [ ] Confirm no old version is issuing room-name grants for newly created rides.
- [ ] Keep rollback-compatible version available for the defined support window.

### Stage 5 — Retire legacy behavior (separate change only)

- [ ] Verify no supported active ride uses a legacy name.
- [ ] Verify old client support window and maximum token/ride lifetime have expired or been safely handled.
- [ ] Remove the legacy resolver only in a separate reviewed pull request with its own tests and rollback strategy.
- [ ] Do not couple this retirement to the first UUID rollout.

## 6. Rollback plan

Rollback must not return to a version that recomputes all names from `Room.name` after UUID rides have been created.

- [ ] Keep a tested compatibility release that honors persisted `lkRoomName`.
- [ ] If new ride creation fails, disable UUID assignment for **new** rides only; never rewrite persisted identities of existing rides.
- [ ] Keep existing rides on their persisted identities.
- [ ] Do not drop the identity column or reverse a non-destructive additive migration during emergency rollback.
- [ ] If some mappings are wrong, pause destructive cleanup and new creation if needed, preserve evidence, and repair mappings through an approved conditional procedure.
- [ ] Never mass-delete LiveKit rooms as a rollback shortcut.
- [ ] Verify route, webhook, sweeper and token resolver all use the rollback-compatible mapping before restoring traffic.

## 7. Incident / abort triggers

Abort the next rollout step and escalate if any of these occurs:

- Host and invitees receive different room identities for the same ride.
- An active ride's persisted identity changes unexpectedly.
- A webhook or sweeper targets a room with ambiguous mapping.
- New UUID ride is served by a backend version that ignores persisted identity.
- Old token can recreate a room in a way that violates the accepted security model.
- Cleanup failures increase and no durable pending cleanup exists.
- Readiness or DB schema assertion fails.
- There is evidence of missing rider/session data, destructive mutation, or unexpected termination of an ACTIVE ride.

## 8. Post-change evidence record

Fill this table after an approved deployment. Do not pre-fill a pass result.

| Check | Evidence/command | Result (`PASS` / `FAIL` / `BLOCKED`) | Owner | Notes |
|---|---|---|---|---|
| All backend replicas compatibility version | | | | |
| Active legacy identity map reviewed | | | | |
| UUID assignment gate | | | | |
| Host and invitee same identity | | | | |
| Same display name produces distinct new identities | | | | |
| Token refresh after end rejected | | | | |
| Old token / implicit room-create check | | | | |
| Webhook participant event mapping | | | | |
| Webhook room-level event mapping | | | | |
| End and sweeper cleanup | | | | |
| Failed cleanup retry | | | | |
| Readiness/schema check | | | | |
| Rollback target and procedure | | | | |

## 9. Final rules

- This is a template, not evidence that any rollout has happened.
- Do not execute production writes or deploy from an AI coding session by default.
- Every identity mutation must be justified, auditable, conditional, and reversible where possible.
- If evidence cannot prove which room belongs to a ride, stop and resolve the ambiguity; do not guess.
- UUID identity, stale-token prevention, webhook correctness, and cleanup reliability are separate controls. Passing one does not imply the others are solved.
