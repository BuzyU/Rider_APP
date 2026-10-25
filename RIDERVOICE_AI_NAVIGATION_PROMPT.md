# RiderVoice AI Navigation & Execution Guide — READ THIS FIRST

Repository: `https://github.com/BuzyU/Rider_APP`

You are the coding AI working on RiderVoice. This file is the **navigation/orchestration guide**. It explains which supplied document has which job, which file to consult for each workstream, how to handle disagreements, and in what order to make fixes. It does not replace the implementation prompt or the audit report.

## 0. First, locate the supplied files correctly

The user may have downloaded the supplied Markdown files directly into the **repository root**. Do not assume they already live under `docs/`.

At the start, list the repository root and locate these exact filenames:

1. `RIDERVOICE_AI_NAVIGATION_PROMPT.md` — this guide.
2. `DEEP_MOBILE_AUDIT_REPORT.md` — technical findings, risks, evidence limitations, target invariants, and acceptance gates.
3. `AI_IMPLEMENTATION_PROMPT.md` — ordered implementation instructions, tests, checkpoints, and definition of done.
4. `ROOM_IDENTITY_ROLLOUT_RUNBOOK_TEMPLATE.md` — optional operational template for the LiveKit identity migration, if the user downloaded it or it exists in the package/docs tree.
5. `README_FIRST.md` and `DOCUMENT_CHANGES.md` — optional package-navigation/change notes, not source-code evidence.

Also search the repository's normal `docs/` paths for versions of the two main documents. The implementation prompt refers to canonical in-repository paths such as `docs/DEEP_MOBILE_AUDIT_REPORT.md` and `docs/AI_IMPLEMENTATION_PROMPT.md`.

### File-location rule

- Never silently ignore a root-level document merely because the prompt also mentions a `docs/` path.
- If a canonical `docs/` copy does not exist, preserve the root input unchanged and copy it to the canonical `docs/` path before starting implementation. Create parent directories as needed.
- If both root and `docs/` copies exist, compare them (for example, checksums or a diff). Do not overwrite either file until you have recorded which version contains which changes.
- If the copies differ materially, use the newest safety-integrated content as a proposal, compare it with the repository's existing audit, preserve useful repo-local information, and record the reconciliation in `docs/implementation/PROGRESS.md`. Do not maintain two silently divergent “current” versions. The canonical working copy during implementation is under `docs/`; root files remain preserved input snapshots unless the user explicitly directs otherwise.
- Do not assume the optional runbook exists. Search by filename in the root and `docs/` tree. If absent, derive a runbook from the detailed requirements in the report and implementation prompt and record that it was newly created, not supplied.

## 1. What each document is for

| File | Primary job | Use it when | Do not use it as |
|---|---|---|---|
| `RIDERVOICE_AI_NAVIGATION_PROMPT.md` | Navigation and orchestration | At the beginning; to understand file roles, source precedence, workstream routing, and checkpoint expectations | A replacement for technical findings or detailed acceptance tests |
| `DEEP_MOBILE_AUDIT_REPORT.md` | Technical rationale and risk baseline | You need to understand why a fix matters, the suspected root cause, invariants, compatibility risks, stop-ship conditions, and what still needs verification | Proof that an alleged defect was reproduced; a blanket instruction to change every mentioned line |
| `AI_IMPLEMENTATION_PROMPT.md` | Action plan for the coding agent | You need implementation order, exact workstream tasks, test expectations, commands, constraints, checkpoint artifacts, and definition of done | Proof that a build/test/device check passed, or permission to deploy/change production |
| `ROOM_IDENTITY_ROLLOUT_RUNBOOK_TEMPLATE.md` | Future controlled rollout planning | Only for preparing the staged LiveKit per-ride identity migration and its operational preflight/rollback gates | Authorization to inspect production, flip flags, deploy, or run production data writes |
| `README_FIRST.md` | Package orientation | To understand the downloaded archive and file inventory | Technical source of truth |
| `DOCUMENT_CHANGES.md` | Summary of the document revision | To see what was changed and what evidence limitations remain | A substitute for reading the report and prompt |
| `*_ORIGINAL.txt` or other clearly named original copies | Provenance/history | To resolve what the earlier documents said or trace a discarded recommendation | Current instructions when the safety-integrated documents supersede them |
| `docs/AUDIT_FINDINGS.md` | Existing repo audit/history | To compare prior findings against the current code and prior fixes | Guaranteed current truth; historical green test claims do not prove the current HEAD passes |
| `docs/UX_COPY.md` | Existing product terminology/copy guidance | When editing visible wording, labels, errors, buttons, and rider-facing copy | Permission to redesign business flows without checking the implementation requirements |
| `docs/diagnostics-payload.md` | Existing diagnostics payload guide | Before changing metric collection, validation, persistence, or export | A complete metric contract until the producer, backend storage, and export paths are all reconciled |
| Actual source, Prisma schema/migrations, tests, CI workflows, and current Git state | Current behavior and executable truth | Before each change, to determine what the current code actually does | Something that can be skipped in favor of the documents |

The report and prompt were revised from user-supplied audit material and follow-up notes. They were **not** generated by an independent fresh clone, passing test run, device test, or production inspection. Re-verify every finding against the active checkout.

## 2. Source-of-truth and conflict policy

Use this precedence:

1. **User's explicit current instructions and safety constraints** govern the task. This task authorizes code changes only on a working branch; it does not authorize production actions.
2. **Actual current repository state** establishes what files, routes, schema, contracts, tests, and behavior exist now.
3. **`AI_IMPLEMENTATION_PROMPT.md`** supplies the implementation sequence and minimum workstream acceptance requirements, after verifying them against current code.
4. **`DEEP_MOBILE_AUDIT_REPORT.md`** supplies rationale, threat/risk analysis, system invariants, and stop-ship constraints.
5. Existing repository docs and original uploaded documents supply context and history; verify them and do not treat old claims as current evidence.

If the report and implementation prompt conflict, do not choose silently. Log the conflict and its consequence in `docs/implementation/PROGRESS.md`; reconcile them in a small proposed edit before dependent code changes. Prefer the safer behavior when resolving security/data-integrity issues, but document the decision. Do not soften a stop-ship gate just to finish faster.

## 3. Required work before changing application code

1. Run `git status --short`, `git branch --show-current`, `git log -5 --oneline`, and `git diff --stat`. Preserve all user modifications and untracked files.
2. Establish exact `HEAD`, tag/version if available, and whether the checkout matches reported baseline `c7ba75a` / `v0.0.3.17`. If not, record it and re-evaluate all relevant findings.
3. Read this guide, the full report, and the full implementation prompt—not just their summaries. Then read existing `docs/AUDIT_FINDINGS.md`, `docs/UX_COPY.md`, `docs/diagnostics-payload.md`, Prisma schema/migrations, route/service callers, tests, and CI files relevant to the first workstream.
4. Create or update `docs/implementation/PROGRESS.md` and `docs/implementation/VERIFICATION_MATRIX.md` before editing app code. If existing files contain valuable content, extend them rather than overwrite them.
5. Make a finding matrix with finding ID, actual current path/symbol, status (`VERIFIED IN CODE`, `REPRODUCED`, `SUSPECTED`, `NOT EXAMINED`, `BLOCKED`, `NEEDS DEVICE`, or `NEEDS DEPLOYMENT VERIFICATION`), planned test, and decision.
6. Do not redo the entire audit unnecessarily. Check prior claims efficiently, focus the first execution on the next bounded workstream, and save evidence as you go.

If the repository state is dirty, do not reset, clean, stash, or overwrite work. Continue on a branch only after inspecting the state and choosing a safe way to preserve all changes. If the requested branch already exists, inspect it before reuse.

## 4. Which document/section to use for each fix

Section numbers below refer to the revised documents. Recheck headings if the files change.

| Workstream / fix | First read in audit report | Execute using implementation prompt | Supporting code/docs to inspect | Required evidence/artifact |
|---|---|---|---|---|
| Preflight / baseline / avoid repeating work | Sections 1–2 and 18 | Sections 0–3, 20–22 | Git status/log, repo tree, existing progress files, package scripts, CI | `docs/implementation/PROGRESS.md`, `VERIFICATION_MATRIX.md` |
| Duplicate ride creation / timeout retry / `Name-2` / request IDs | Section 5 | Section 4 | Backend create handler, Prisma `Room` model/indexes, error middleware, Android HostSetup screen/ViewModel/API model, web callers | Concurrency/idempotency tests; payload-hash and timeout-retry evidence |
| LiveKit room identity / UUID migration / legacy active rides | Section 4 | Section 5 | Token service, all create/ensure/delete/list/send paths, room sweeper, lobby routes, webhook, `ROOM_ID_MODE` usage, web and older supported clients | Persisted identity resolver tests, mapping dry run, staged rollout/rollback runbook |
| Ride ends while token refresh/create is in flight / stale token recreates room | Section 6 | Section 6 | Token endpoints, `issueRoomToken`, end route, sweeper, LiveKit server/config/client token grants | Deterministic race test, durable cleanup/retry test, explicit stale-token deployment result |
| LiveKit webhook mapping, retries, readiness | Section 7 | Section 7 | Signature verification, participant and room-level events, server health/readiness endpoints, provider retry contract | Signature and transient-error tests; no ambiguous destructive event handling |
| Runtime schema repair / duplicate rows / migration safety | Section 8 | Section 8 | `schemaMigration.js`, `schema.prisma`, every Prisma migration, startup hooks, `render.yaml`, connection/pooler config | Read-only schema assertions; isolated DB migration and reconciliation tests; archive/restore runbook |
| Release signing / committed debug key / OTA checksum and signer | Sections 9 and 15 | Sections 9 and 18 | Gradle signing config, release/build workflows, `UpdateManager`, release metadata and Android signing APIs | Failure-without-secrets test, safe signing validation, OTA integrity tests |
| Reconnect after removal/ended ride / 401 and transient errors | Section 10 | Section 10 | LiveKitManager, token provider/API service, typed HTTP errors, coroutine cancellation | Fake API status/code tests and no-reconnect-after-terminal tests |
| Invite accept / re-invite / join code / share link / max-use cap / denylist | Supplemental leads in Section 2.1 and Section 10 | Section 11 | `membershipService.js`, join/share/invite routes, Prisma schema/client, token issuance, Android invite screens | Remove-vs-accept race, repeated accept, cross-owner, re-invite, concurrent maxUses and denylist-model validation |
| VOX / microphone capture / gate close / initial syllable | Section 11 | Section 12 | `VoxEngine.kt`, detector, capture evaluator, LiveKit/ADM integration, audio profiles and tests | Synthetic PCM tests and explicit physical-device test plan; no unmeasured threshold tuning |
| Actual Bluetooth input/output routing | Section 11 | Section 13 | `AudioDeviceRouter`, communication-device APIs, permissions, routing UI and fallback paths | Route state-machine tests plus hardware route matrix |
| Battery / wake locks / peer pings / polling / GPS | Section 12 | Section 14 | `VoiceForegroundService`, `LiveKitManager`, `RoomViewModel`, location/thermal policies | Comparable before/after measurement plan and evidence; don't claim savings without metrics |
| Diagnostics formulas / null-versus-zero / event dedupe / exports | Section 13 | Section 15 | `diagnostics-payload.md`, collector, diagnostic service/validator, queue, Prisma models, export paths | `docs/DIAGNOSTICS_CONTRACT.md`, metric correctness/idempotency tests |
| SOS / FCM partial failure / status claims / emergency country | Section 14 and supplemental leads in Section 2.1 | Section 17 | Emergency routes, alert model, all FCM sends and retry paths, SOS UI/activities, emergency-number handling | Mock-provider tests, no real SOS; UI copy matches observable server/provider/recipient state |
| Whole app UI / labels / localization / accessibility / unreachable screens | Section 16 | Section 16 | All Compose screens, nav graph, activities, dialogs/sheets, strings and translations, `UX_COPY.md` | `docs/UI_INVENTORY.md`, per-screen/device accessibility checklist, `lintDebug` evidence |
| Cross-cutting API compatibility / web consumers / older Android builds | Section 17 | Section 19 | Tags v0.0.3.14–.17 if present, all route consumers, request/response models, web app, tests | Contract test matrix and explicit unsupported-client list |
| Final review / release readiness | Sections 18–21 | Sections 20–23 | Full diff, CI, tests, workflows, git status | Final evidence ledger, unresolved blocker table, no production deployment/merge/release |

## 5. Safe implementation order

Follow the implementation prompt's sequence, unless current-code evidence reveals a dependency. If you change the sequence, record the reason and risk in `PROGRESS.md`.

1. Preflight, checkpoint, current-source reconciliation.
2. Isolated test harness and contracts for concurrency/lifecycle behavior.
3. Durable create idempotency and Android persisted create-attempt identity.
4. Persisted per-ride LiveKit identity, common resolver, legacy mapping, feature-gated staged rollout, and runbook. Do not flip production flags.
5. End/token/create race, terminal-state checks, compensation, durable cleanup, and stale-token control.
6. Webhook event mapping and production-readiness enforcement.
7. Read-only schema assertion and safe, dry-run-first reconciliation tooling. Do not deploy anything while the unsafe runtime repair path remains active.
8. Release signing and OTA integrity.
9. Structured reconnect plus invitation/membership/share-link concurrency and schema-backed denylist validation.
10. Diagnostics contract and verified metric fixes.
11. VOX/microphone capture and Bluetooth routing correctness.
12. Battery/foreground/background lifecycle, measure first.
13. SOS status/delivery semantics and emergency behavior.
14. Full UI inventory, UI/UX corrections, localization, accessibility.
15. Cross-component tests and independent final diff review.

Within a workstream, use: inspect current path → reproduce/test the claim where feasible → implement the smallest fix → run focused tests → run broader relevant checks → review diff and `git diff --check` → update checkpoints and docs. Work in small, reversible changes. Keep app behavior untouched where the issue is not reproduced and there is no independently justified safety fix.

## 6. Critical reported leads that must not disappear

The supplemental table in report Section 2.1 must be entered into the progress/verification matrix even if a previous audit did not include it. In particular, explicitly investigate:

- Invite/membership read-then-update races that can reaccept a removed rider.
- Share-link `maxUses` enforcement under concurrent accepts.
- Whether any `prisma.roomDenylist` access maps to an actual schema-backed model/table.
- Non-atomic concurrent `/end` calls.
- Removed riders reconnecting with unexpired tokens.
- Webhook transient handler failures being mislabeled as signature/authentication failures.
- SOS response booleans/state that claim success despite zero successful FCM sends; provider TTL and retry semantics.
- Diagnostics metric issues already listed in report Section 13.3.
- The actual endpoint/config source for `ROOM_ID_MODE`; do not assume `/health` exposes it without inspecting current source.

These are leads to verify, not confirmed production incidents. The report must not use them to imply they were reproduced.

## 7. Workstream checkpoint and time-limit behavior

The prior attempts timed out when the whole audit/implementation was requested in one pass. Do not recreate that failure mode:

- Complete one bounded workstream at a time.
- Save evidence and decisions immediately into `PROGRESS.md` and `VERIFICATION_MATRIX.md`.
- Do not repeat a completed audit phase unless new evidence requires it.
- When nearing a time/context limit, stop at a clean boundary, save changed files, state exactly what is finished, and write one precise next task.
- On resumption, read the checkpoint and continue from the exact next task. Do not restart the audit from scratch.
- Do not wait for user approval after every harmless local step. Stop and ask only when actual authorization, ambiguous destructive behavior, production access, or a material unresolved decision is required.

## 8. Universal safety gates

- No production data changes, production migration apply, production flag changes, deployment, release, push, merge, tag, or publishing.
- No secrets, tokens, personal rider data, raw sensitive diagnostics, or keystore values in output.
- Never treat `VERIFIED IN CODE` as `REPRODUCED`.
- Never fabricate tests, build results, physical-device measurements, or production configuration.
- Never guess an ambiguous LiveKit room mapping before a delete/kick/end action.
- Never silently downgrade a critical acceptance gate to make the task appear complete.
- No direct global UUID switch. Preserve active legacy room identities through a tested compatibility stage.
- A database status reread alone does not eliminate the create/end race; test the in-flight external-call interleaving.
- `roomCreate:false` must not be presented as proof that a stale token cannot recreate a room; verify actual deployment behavior.
- A provider-accepted FCM send must not be described as proven recipient delivery unless an acknowledgement path exists.
- A successful compile does not prove voice, routing, battery, SOS, OTA, or production readiness.

## 9. Final response requirements

Report:

1. The exact source commit and branch worked on.
2. Which workstreams were completed and which remain blocked.
3. Files changed and why.
4. Tests/commands actually run with pass/fail/blocked results.
5. Device-only and deployment-only checks still needed.
6. Every remaining critical/high risk with a responsible next action.
7. Confirmation that no production data/config/deployment/release/merge/publish was touched.
8. The exact next task if any work remains.

Use only evidence-supported statuses: `FIXED`, `NOT REPRODUCIBLE`, `BLOCKED`, `NEEDS DEVICE`, `NEEDS DEPLOYMENT VERIFICATION`, or `OPEN`. Do not claim production-ready while the report's stop-ship conditions remain open.
