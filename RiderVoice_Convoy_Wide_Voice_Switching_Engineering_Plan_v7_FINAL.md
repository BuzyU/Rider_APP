# RiderVoice — Convoy-Wide Online/Offline Voice Switching

## Engineering Specification v7 (FINAL, single consolidated body)

**Status:** Final proposed specification. One body, no override sections.
This is the frozen architecture. Only Phase 0 findings, field-test evidence
or an explicit threat-model change may reopen it (Section 25).\
**Scope:** Android client, offline UDP mesh, LiveKit voice transport,
convoy membership, automatic transport switching.\
**Objective:** Keep the whole convoy on one voice transport, switch
predictably, recover safely, stay battery-conscious, and never run a
second audio pipeline.

---

# 0. How to read this document

## 0.1 Tiers

Every rule is tagged with the tier that must implement it. Build in this
order. Do not start a tier before the previous tier passes its
acceptance criteria (Section 23).

| Tier | Meaning | Ships |
|---|---|---|
| **T1** | Solves the actual problem: one rider drops, the convoy moves together, and it comes back together. | First release |
| **T2** | Hardening for long rides and messy real-world connectivity (tunnels, long outages, flaky riders). | After T1 field tests |
| **T3** | Defence against a *malicious* convoy member. Only needed if the threat model changes (Section 1.3). | Only if required |

An untagged rule applies to all tiers.

## 0.2 What changed from v6

v7 is a clarification release. The architecture and the decision/recovery
protocol are unchanged.

1. **Relay model made exact (11.3, 11.5, 11.2).** v6 let relays "increase
   age" while saying relays cannot alter protected fields, which cannot
   both hold under AES-GCM. v7 defines two carriage paths: forwarded
   original packets (relays touch only `ttlHop` and `prevShort`; the
   receiver adds the hop penalty itself) and re-advertisement (a node
   composes a new, separately authenticated message that embeds second-hand
   records, changing only `age` and `hops`). A `hops` field is added.
2. **ACK arbitration (8.4).** With one `ackFor` per record, a member now
   acknowledges only the currently winning live proposal. Competing
   same-generation proposals, supersession and the loser's ACK are fully
   specified.
3. **Deterministic commit validation (8.5, 9.3).** The receiver-side
   "presence veto" depended on what each phone happened to hear, so the
   same commit could be accepted on one phone and rejected on another. All
   validation now uses only the commit's fields, the roster and the
   config version. Observation-based checks moved to the proposer. A
   rejected commit has a defined outcome, and the proposer self-validates.
4. **Sequence baseline expiry (8.2).** A member's `seq` baseline is
   discarded after 10 minutes of silence, removing wrap-around ambiguity.
5. **UI and metrics (18, Phase 7).** "Convoy decided online, you are not"
   is a required, explicit state; the failed-recovery rate is a required
   field measurement.
6. Phase 0 now also confirms what `ttlHop` carries. Invariants, tests,
   acceptance criteria and the decision record are extended.

## 0.3 Non-negotiable rules for implementers

1. Never implement this as a per-phone internet-loss listener that
   switches that phone on its own. The purpose is convoy-wide
   coordination with independently verified local execution.
2. Do not skip Phase 0 because the repository probably supports
   something. Every assumption here is unverified until Phase 0 reports
   evidence with file references.
3. A convoy decision is not local transport state. Never display, log or
   test one as the other.
4. `AudioOwnershipArbiter` remains the only audio-ownership authority.
   Nothing in this protocol acquires audio.
5. Do not start T2 or T3 work during T1.
6. After the freeze, change the design only for a Phase 0 mismatch, field
   evidence, or a threat-model decision (Section 25).

---

# 1. Decision summary

## 1.1 The design in one paragraph

The convoy runs a **decentralized, leaderless, eventually convergent
transport protocol**. Each phone keeps a durable, monotonic *convoy
decision* (`generation`, `mode`, `phase`, `proposer`) and gossips it,
together with a small readiness digest, in repeated state messages. A rider
that confirms total internet loss proposes OFFLINE; everyone who learns the
higher decision executes the existing `fallbackToMesh()`. Returning online
is two-phase: a proposal, per-member acknowledgements, a short hold, then a
commit. Each phone separately proves that it changed transport and reports
the truth. `AudioOwnershipArbiter`, the 400 ms quiet guard and the
existing rollback stay exactly as they are.

## 1.2 The guarantee we can honestly give

The protocol cannot make every phone switch at the same instant. UDP loses
packets, the mesh can partition, and a phone may have no route.

**Provided:**

- Every reachable, compatible phone eventually learns the highest valid
  decision.
- A phone executes it only when its local prerequisites hold, and rolls
  back safely if the handoff fails.
- Partitions that merge converge by deterministic ordering.
- A phone that cannot reach the mesh is reported as locally unavailable and
  is never counted as switched.
- No known member is abandoned without an explicit, recorded exclusion.

**Not provided:** strict distributed consensus, or a guarantee that all
riders always share one transport. The UI must show the convoy's decided
mode separately from each rider's actual local mode.

## 1.3 Trust model (decided)

- **T1/T2: crash-fault trust.** Every member holding the offline session
  keys is an honest participant whose phone, radio or app may fail, restart
  or lose packets. Messages are protected by the existing AES-256-GCM
  session crypto; that proves "a member of this session sent it", not
  "member X sent it".
- This matches reality: any member already holds the keys to forge any
  other member's voice packets, so forging a protocol message adds no new
  capability.
- **T3 (only if a malicious-member threat is required):** per-member
  signing keys and signed ACK evidence. This needs key distribution at
  provisioning, which touches frozen backend paths, so it is deliberately
  out of scope.

## 1.4 Product policies (fixed)

| Topic | Rule |
|---|---|
| Group fallback | Enabled for rides with 2–6 mesh participants. |
| More than 6 mesh participants | Group fallback disabled **and** the existing per-device fallback suppressed for that ride (a lone rider on a mesh nobody listens to would be silent). Shown before ride start. |
| Opted-out / mesh-ineligible riders | Stay on LiveKit all ride. Never required for recovery, never "excluded". UI says they cannot hear mesh riders. |
| Single rider's loss | The existing 10 s confirmed-total-loss trigger stays. One rider's confirmed loss moves the whole group to mesh. Disclose this trade-off before the ride; do not silently change the trigger. |
| Offline network | Existing shared Wi-Fi/hotspot only. No BLE, Wi-Fi Direct or Wi-Fi Aware. |
| Leader / consensus service | None. Every phone runs the same pure Kotlin logic. |
| Audio ownership | `AudioOwnershipArbiter` unchanged. |
| Standby | Discovery and state messages only. No mic capture, Opus encoding, mesh playback or second audio pipeline. |
| Returning rider without internet | Does not automatically pull the group offline. Needs normal failure confirmation, proposal guards and a 60 s cooldown (9.4). |
| Excluding riders from recovery | Only by one explicit rider confirmation of a full list that names every excluded rider and warns they may lose voice contact (T1). |

---

# 2. Existing architecture and constraints

This design builds on `VOICE_ONLINE_OFFLINE_DEEP_DIVE.md`. It does not
replace the audio stack.

## 2.1 Online (LiveKit)

- SFU over cellular/Wi-Fi. Backend authenticates users and manages
  membership while online. JWT lifetime 30 min, refreshed by
  `ensureFreshToken()`.
- `LiveKitManager` verifies the expected room identity before
  `CONNECTED`. `autoSubscribe = false`. Reconnect: up to 10 attempts with
  backoff, then it stops.
- **`livekitReady` means:** connected, expected room identity verified,
  token valid or refreshable, participant authorised, and the audio
  transport can be activated. An open socket is not enough.

## 2.2 Offline (UDP mesh)

- UDP port `45454` over an existing shared Wi-Fi/hotspot. The app does not
  create or join networks. Up to six devices, five hops.
- `MeshPacketV4`: 17-byte header, ciphertext, 12-byte GCM tag. Types:
  `VOICE=0`, `HELLO=1`, `BYE=2`; **type `3` is unused**.
- Per-source key: `HKDF(payloadKey, "rvmesh/v4/src", srcShort || epoch)`;
  deterministic nonce `(type, seq, epoch)`. Relays rewrite only `ttlHop`
  and `prevShort`.
- HELLO carries node UUID, display name, codec capabilities and the direct
  neighbour list. `MeshCadence`: BOOST 750 ms for 5 s, ACTIVE 1.5 s,
  STANDBY (warm discovery).
- **A valid local route is a local fact.** It does not prove that every
  member is reachable.

## 2.3 Existing handoff (preserved)

- `VoiceSessionCoordinator.fallbackToMesh()`: checks `mesh.hasValidRoute`,
  suspends LiveKit capture, verifies ≥ 400 ms quiet, transfers ownership
  to `OFFLINE_MESH`, starts mesh audio, rolls back on failure.
- `recoverToLiveKit()`: tears down mesh audio, returns ownership to
  `LIVEKIT`, resumes WebRTC audio, may keep low-power discovery.
- `AudioOwnershipArbiter`: only authority for `NONE | LIVEKIT |
  OFFLINE_MESH`. Never two owners.

The new protocol never duplicates audio ownership logic.

## 2.4 Existing guardrails (initial values preserved)

| Guard | Value |
|---|---|
| Total-loss confirmation | 10 s (exponential backoff up to 8×) |
| Recovery stability | 8 s |
| Minimum dwell | 20 s |
| Transition limit | 3 per rolling 120 s |
| Quiet guard | 400 ms |
| JWT lifetime | 30 min |
| Offline session persistence | 8 h |

Trigger stays **total loss**: `!isValidatedInternet && !isLiveKitConnected
&& !isHealthProbeSuccessful`. High RTT or packet loss alone never triggers
fallback.

---

# 3. Components

| Component | Responsibility | Change |
|---|---|---|
| `AutomaticSwitchStateMachine` | Detect local loss/recovery; emit proposal *intents*; never commit a convoy transport change itself. | Modify |
| `ConvoyMembershipSnapshot` | Persisted roster, `rosterHash`, `meshParticipants`. | New |
| `ConvoyTransportState` | Highest accepted decision and local execution status. | New |
| `ConvoyTransportCoordinator` | **Pure Kotlin**: ordering, adoption, proposal lifecycle, commit validation, exclusions, merge. | New |
| `HandoffExecutor` | Single-flight serialization of handoffs (12). | New |
| `VoiceSessionCoordinator` | Executes the existing `fallbackToMesh()` / `recoverToLiveKit()`. | Small hook |
| `AudioOwnershipArbiter` | Single audio owner, quiet guard. | Unchanged |
| State codec | HELLO extension and CONTROL(STATE) (11). | New / extend |
| UI mapper | Convoy decision plus per-rider actual state. | New / extend |

## 3.1 Dependency direction

```text
Network / LiveKit / Mesh observations
              |
AutomaticSwitchStateMachine  --(intent)-->  ConvoyTransportCoordinator
                                                   |
                                         accepted decision + actions
                                                   |
                                             HandoffExecutor
                                                   |
                                        VoiceSessionCoordinator
                                                   |
                                          AudioOwnershipArbiter
                                                   |
                                      LiveKit audio  or  Mesh audio
```

The coordinator receives observations and returns actions. It never sees
`Context`, LiveKit `Room`, `AudioManager`, sockets or coroutine scopes.

## 3.2 Clock

Inject a monotonic clock (`nowMs()`). Use it for confirmation, stability,
lease/age, grace, dwell, rate limits and backoff. **Never compare wall
clocks between devices.** The coordinator is deterministic for the same
inputs and timestamps.

---

# 4. Membership

## 4.1 Two sets, never confused

- `knownMembers`: the canonical ride roster.
- `reachablePeers`: members currently heard on the mesh.

**A missing HELLO never removes a member.** A member leaves the roster only
through an authoritative roster update received while online (or an
existing explicit ride-control event). A local timeout is not a removal.

## 4.2 Snapshot **[T1]**

```kotlin
data class ConvoyMembershipSnapshot(
    val sessionId: ByteArray,           // 16 bytes, from the offline session
    val members: List<ConvoyMember>,    // canonical sorted order
    val meshParticipants: List<String>, // stableMemberIds, canonical order
    val rosterHash: ByteArray,          // 32 bytes (8 on the wire)
    val localRevision: Long             // diagnostics/UI only, never on the wire
)

data class ConvoyMember(
    val stableMemberId: String,
    val role: MemberRole                // MESH_PARTICIPANT | NON_MESH_PARTICIPANT
)
```

**Roster hash (identity, not authority):**

```text
SHA-256( 0x01                               // canonical roster version
         || sessionId (16 B)
         || memberCount (1 B)
         || for each member, sorted by UTF-8 NFC bytes of stableMemberId:
              len(stableMemberId) (2 B, big-endian)
              || stableMemberId bytes
              || role (1 B) )
```

- Duplicate IDs: reject the snapshot.
- The hash covers **only** immutable identity and role. It never covers
  mutable observations (network state, `meshEligibility` after ride start,
  last HELLO, readiness, battery, execution status).
- `localRevision` is a per-phone counter for the UI. It is never sent,
  compared or ordered across devices. (Two phones that applied the same
  roster in a different order would otherwise disagree falsely.)
- Persist the actual roster, not just the hash, in the existing encrypted
  offline session storage. The roster is trusted because it came from the
  authenticated online membership flow.

## 4.3 Where the data comes from

- The backend returns no membership revision and backend paths are
  CI-frozen. Do not add one.
- `stableMemberId` must be a per-user ID already returned by the existing
  roster/status responses and identical on every device. **Phase 0
  verifies this.** `srcShort` (32 bits) is only a routing identity, mapped
  to `stableMemberId` with collision detection.
- `meshParticipants` = members whose offline readiness is `READY` at ride
  start, frozen in canonical order, persisted with the snapshot. It changes
  only through an authenticated online roster update before the session is
  frozen, or through a newly provisioned ride. **A role cannot change while
  offline.**
- Bitmaps on the wire index `meshParticipants` (at most 6 bits, one byte).
  Never index a transient peer list.

## 4.4 Membership while online and mismatches

- Joins/leaves while online: rebuild the snapshot, recompute `rosterHash`,
  persist, advertise.
- Offline, membership is **frozen**. No phone invents a roster from who it
  can hear.
- If two devices advertise different `rosterHash`: do not merge or compare
  subsets. Enter `MEMBERSHIP_MISMATCH` for ONLINE purposes only.
- **A mismatch blocks only ONLINE proposals, commits and exclusions. It
  never blocks adoption or execution of an OFFLINE decision.** OFFLINE is
  the safe direction, and a rider who rejected it over a roster
  discrepancy would sit on LiveKit alone, recreating the split. OFFLINE
  needs only session authentication.

---

# 5. Decision model

## 5.1 Data **[T1]**

```kotlin
enum class TransportMode { ONLINE, OFFLINE }
enum class DecisionPhase { ADOPTED, ONLINE_PROPOSED, ONLINE_COMMITTED }

data class TransportDecision(
    val generation: Long,              // unsigned 32-bit on the wire
    val mode: TransportMode,
    val phase: DecisionPhase,
    val proposerTag: ByteArray,        // 8 bytes, roster-independent (below)
    val rosterHash: ByteArray?,        // ONLINE decisions only
    val baseOfflineId: ByteArray?,     // ONLINE decisions only
    val excludedBitmap: Int,           // ONLINE only; bit i = meshParticipants[i]
    val readyBitmap: Int,              // ONLINE_COMMITTED only
    val ackSeqs: List<Int>,            // ONLINE_COMMITTED only, diagnostics
    val manualExclusion: Boolean,      // ONLINE only
    val decisionId: ByteArray          // 8 bytes
)
```

`generation` is deliberately named differently from `MeshPacketV4.epoch`,
which is a transport field and is not reused for convoy decisions.

**`proposerTag`** = first 8 bytes of `SHA-256("rvtag/v1" || sessionId ||
stableMemberId)`. Each phone computes its own tag and the tags of the
roster members it knows. The tag depends on **no roster index and no
roster agreement**, so it identifies an OFFLINE proposer correctly even
when two phones hold different snapshots (4.4). A receiver needs its
roster only to *map* a tag to a member, which ONLINE validation requires
and OFFLINE ordering and IDs never do. Equal tags mean the same proposer
(an 8-byte collision inside one convoy is negligible).

## 5.2 Decision IDs

`decisionId` = first 8 bytes of SHA-256 over a domain tag plus canonical
fields, versioned. 64-bit IDs are enough here: with 1,000 decisions in one
ride the collision probability is about 3×10⁻¹⁴.

| Decision | Fields hashed (after tag `rvdec/v1/<kind>` and `sessionId`) |
|---|---|
| OFFLINE | generation, mode, phase, proposerTag. **No** roster, bitmaps or observations. |
| ONLINE_PROPOSED | generation, mode, phase, proposerTag, rosterHash, baseOfflineId, excludedBitmap, manualExclusion |
| ONLINE_COMMITTED | the proposed fields plus readyBitmap and ackSeqs |

OFFLINE IDs ignore the roster so two phones with different local roster
snapshots never see the same fallback as same-proposer equivocation.
**No timestamps** in any ID: phones do not share a clock.

**`proposalId`.** The ID that ACKs bind to is the `ONLINE_PROPOSED` ID,
computed over the proposed fields only. A commit does not need to carry
it: any receiver recomputes it from the commit's own `generation`,
`proposerTag`, `rosterHash`, `baseOfflineId`, `excludedBitmap` and
`manualExclusion`. A commit is therefore self-contained with respect to
*which proposal it commits*, even for a phone that never saw the
proposal.

## 5.3 Generation **[T1]**

1. A new decision uses `generationHighWater + 1`. The **high-water mark**
   is the highest generation this phone has ever *created or observed*
   in any authenticated, well-formed session decision, whatever its phase
   or outcome. It is distinct from the *accepted* generation, which is the
   highest decision the phone has adopted.
2. A phone never decreases its accepted generation or its high-water
   mark. A decision with a lower generation than the accepted one is
   ignored.
3. **Write-ahead reservation:** persist the new high-water mark durably
   *before* sending a proposal. An aborted, expired or superseded
   proposal therefore keeps its generation consumed, even if the process
   dies. Gaps in the sequence are normal and harmless: only comparison
   matters.
4. Observing a valid decision with a higher generation raises the
   high-water mark (persisted before the phone next creates a decision),
   so a phone that merely saw an aborted proposal never reuses its
   generation either.
5. Generation and high-water mark belong to the offline session and are
   persisted atomically with it (16.1). Generations are compared as plain
   unsigned 32-bit integers.
6. **Generations never wrap.** Never compute a generation modulo 2^32 and
   never compare generations with serial-number arithmetic (that is only
   for the 16-bit readiness `seq`). When `generationHighWater` reaches
   `0xFFFFFFF0` the phone stops creating decisions of any kind, reports
   `GENERATION_EXHAUSTED`, and keeps following decisions from others. The
   current accepted decision stays in force. Only a new ride (a new
   authenticated offline session, that is a new backup code) resets the
   generation space.
7. **Plausibility bound.** When a baseline exists, an observed decision
   whose generation exceeds `generationHighWater + 65,536` is rejected
   with a diagnostic and does not raise the high-water mark. A single
   corrupt or buggy decision therefore cannot exhaust a session. (At the
   existing limits an 8-hour partition produces on the order of 30,000
   generations at most, including aborted proposals, so legitimate jumps
   stay inside the bound.) A phone in `GENERATION_UNKNOWN` has no baseline
   and adopts a peer's generation under 16.2 without this check.
8. A phone that lost its generation state follows 16.2.

## 5.4 Ordering (the only conflict rule) **[T1]**

For valid decisions (ONLINE decisions must also carry the same
`rosterHash`):

1. Higher `generation` wins.
2. At equal generation, `OFFLINE` beats `ONLINE`.
3. At equal generation and mode `ONLINE`, `ONLINE_COMMITTED` beats
   `ONLINE_PROPOSED`.
4. At equal generation, mode and phase, the smaller `proposerTag` wins
   (unsigned byte-wise comparison of the 8-byte tags). This needs no
   roster, so it works for OFFLINE decisions under a roster mismatch.
5. **Equivocation** = same generation, mode, phase and `proposerTag` with
   different `decisionId`. Treat as corruption: never merge fields, never
   execute either ONLINE decision, keep the last safe state, emit a
   diagnostic. (OFFLINE cannot equivocate, because its ID depends only on
   those four fields.)

A decision is an atomic immutable value. Never combine the mode of one
with the exclusions of another.

OFFLINE wins ties because an ambiguous ONLINE recovery must not pull
reachable riders off a working mesh. It is only *executable* when the phone
has a route and passes the handoff checks. Otherwise it records
`FALLBACK_UNAVAILABLE` and never claims `OFFLINE_ACTIVE`.

---

# 6. State model

Keep **convoy decision state** and **local execution state** separate.

```kotlin
// Convoy decision (what the convoy is trying to use)
enum class ConvoyModeState {
    ONLINE, OFFLINE, ONLINE_PROPOSED, ONLINE_COMMITTED,
    MEMBERSHIP_MISMATCH, DECISION_CONFLICT
}

// Local execution (what this phone has actually done)
enum class LocalTransportState {
    ONLINE_ACTIVE, ONLINE_SUSPECT, STARTING_OFFLINE, OFFLINE_ACTIVE,
    FALLBACK_UNAVAILABLE, STARTING_ONLINE, RECOVERY_FAILED,
    VOICE_UNAVAILABLE, GENERATION_UNKNOWN, GENERATION_EXHAUSTED,
    MEMBERSHIP_MISMATCH, LIVEKIT_PROBE_UNAVAILABLE
}

enum class LiveKitReadiness { NOT_READY, CONNECTING, CONNECTED_UNVERIFIED, READY, FAILED }
enum class MeshReadiness { NOT_READY, DISCOVERING, ROUTE_AVAILABLE, ROUTE_UNAVAILABLE, AUDIO_READY, FAILED }
```

**Never set `ONLINE_ACTIVE` or `OFFLINE_ACTIVE` from a convoy decision
alone.** Those states require a successful local ownership transfer and
audio-path verification. `meshReady` in state messages means local route
readiness, not "everyone is reachable".

A phone may know the convoy is `ONLINE_COMMITTED` while its own transport
is `OFFLINE_ACTIVE` or `RECOVERY_FAILED`. This degraded state is valid and
must be shown honestly.

---

# 7. Fallback: ONLINE → OFFLINE **[T1]**

Single-phase at the decision layer. It does not wait for every rider to
acknowledge, because waiting for an unreachable rider would block the
riders who can talk.

## 7.1 When a phone may propose OFFLINE

All of:

- total loss confirmed for 10 s;
- offline mesh gate enabled for the ride and group fallback eligible
  (≤ 6 mesh participants);
- valid persisted snapshot and generation (not `GENERATION_UNKNOWN`);
- this phone is a mesh participant;
- dwell, rate limit, and "new failure episode" guards permit (7.7);
- a valid local mesh route (a phone with neither internet nor a route has
  no path to tell anyone and stays locally unavailable with bounded
  retries).

Proposal: `generation = generationHighWater + 1` (reserved write-ahead,
5.3), `mode = OFFLINE`, `phase = ADOPTED`, `proposerTag = ownTag`.

## 7.2 Early warning (no battery cost)

When `NET_CAPABILITY_VALIDATED` drops (event-driven), send
`netState=SUSPECT` and a bounded state/HELLO burst. Peers move to BOOST
cadence and pre-warm the route. **No mic, no encoding, no playback, no
ownership transfer.** If connectivity returns before confirmation, return
to normal standby without switching.

## 7.3 Adoption

On a valid higher-ranked OFFLINE decision:

1. Authenticate; check protocol/config version and session ID.
2. Check ordering (5.4). **Do not** reject for a roster mismatch (4.4).
3. Persist the accepted decision atomically **before** executing.
4. Enqueue it in the `HandoffExecutor` (12).
5. If `mesh.hasValidRoute`, run the existing `fallbackToMesh()`; otherwise
   record `FALLBACK_UNAVAILABLE`.
6. Advertise the adopted decision and the local execution state.

A phone that missed the original proposal adopts it later from any state
message, executes if it has a route, ignores duplicates by `decisionId`,
and never creates a second generation merely because the decision arrived
late.

## 7.4 Local failure

If `fallbackToMesh()` fails: the existing rollback restores LiveKit; report
`FALLBACK_UNAVAILABLE` with a bounded reason; **do not** create a new
generation because execution failed; retry only after backoff and only if
prerequisites change; keep advertising the OFFLINE decision and the local
failure; never count this rider as `OFFLINE_ACTIVE`. If LiveKit is also
unavailable, show "voice unavailable on this device".

## 7.5 Actions emitted by the coordinator

```kotlin
sealed interface TransportAction {
    data class ExecuteFallback(val decisionId: ByteArray) : TransportAction
    data class ExecuteRecovery(val decisionId: ByteArray) : TransportAction
    data class ReportFallbackUnavailable(val reason: String) : TransportAction
    data class ReportRecoveryFailed(val reason: String) : TransportAction
    data class AdvertiseState(val state: ConvoyStateSnapshot) : TransportAction
    data class SendControlBurst(val state: ConvoyStateSnapshot) : TransportAction
    data class PromptRider(val kind: PromptKind, val memberId: String?) : TransportAction
    data object NoOp : TransportAction
}
```

## 7.6 Guards apply to proposing, not to executing

Failure confirmation, dwell (20 s) and the rate limit (3 per 120 s)
decide whether a phone may *create* a proposal. **They never block
execution of an already-accepted decision**; otherwise a phone that
adopted OFFLINE 5 s after its last handoff would stay on LiveKit while
everyone else moved. A node that just executed a decision must not create
another proposal for the same failure episode: it needs a genuinely new
failure or recovery episode plus the normal confirmation. This suppresses
*proposals only*, never a valid higher decision from peers. Count a
transition only when this phone actually changes its audio owner; do not
count proposals, duplicate adoptions, or failed attempts that roll back.

---

# 8. Recovery: OFFLINE → ONLINE **[T1]**

Recovery is the dangerous direction: it can pull reachable riders off a
working mesh while someone is still offline. It therefore needs readiness,
acknowledgements and an explicit commit.

## 8.1 Recovery eligibility

A member is `recoveryEligible` only after **all** of:

1. validated internet restored;
2. LiveKit connected or reconnected;
3. expected room identity verified and member authorised;
4. token valid (run `ensureFreshToken()` if needed);
5. the above stable for 8 s with no new failure.

## 8.2 Readiness records and leases

A readiness record is **bound** to: session, the member's adopted OFFLINE
`baseGen`, the sender's `epoch`, a sequence number within that epoch, and
its flags. A record for a different session, an earlier base generation, a
retired epoch, or an older sequence never satisfies a recovery.

- **Lease = 6 s**, measured on the *receiver's* monotonic clock. Sender
  wall-clock time is never used.
- **A lease is renewed only by a strictly newer sequence number from the
  origin within the same sender epoch.** A duplicate, a retransmission or
  an old record forwarded again never renews it. The origin increments
  `seq` on **every** emission (heartbeat and change), not only on change.
  Compare `seq` with serial-number arithmetic (16-bit, window 2^15) so
  wrap-around is safe.
- Record age and hop accounting follow 11.3: a receiver-side hop penalty
  for forwarded packets, and `age` plus `hops` inside the re-advertiser's
  own authenticated message for second-hand records.
- **Sequence baseline expiry.** `seq` is 16 bits and compared with
  serial-number arithmetic. A member's sequence baseline is discarded
  after `SEQ_RESYNC` (10 min) of silence from that member; the next record
  is then treated as new, subject to the 11.6 warm-up. This removes any
  wrap-around ambiguity, because no comparison ever spans more than a few
  thousand emissions (state bursts are bounded to a handful of extra
  emissions per change, at least 100 ms apart).
- The lease is a little longer than three 1.5 s ACTIVE HELLO intervals, to
  tolerate one lost packet. Freshness uses the cadence in force while
  OFFLINE (ACTIVE), not STANDBY.
- After a receiver restart no record counts as fresh until the warm-up in
  11.6 has passed.

## 8.3 Proposal **[T1]**

Any mesh participant may propose ONLINE when:

- it has been eligible for 8 s;
- every required member has a live record with `recoveryEligible = true`
  and the same `baseGen` as the proposer;
- `rosterHash` matches;
- the current decision is OFFLINE and no newer OFFLINE is known;
- dwell and rate guards permit.

Required members = `meshParticipants − excludedBitmap` (9). Not
`reachablePeers`. A proposal is not permission to switch audio.

Proposal: `generation = generationHighWater + 1` (reserved write-ahead, 5.3),
`mode = ONLINE`, `phase =
ONLINE_PROPOSED`, with `baseOfflineId`, `rosterHash`, `excludedBitmap`.

## 8.4 Proposal lifecycle

- **Stagger, and no competing proposals:** a member MUST NOT create a
  proposal while a live `ONLINE_PROPOSED` for the current OFFLINE base is
  visible; it acknowledges that proposal instead. Otherwise it delays its
  own by `rosterIndex × 300 ms`. Simultaneous creation can still happen
  and yields same-generation proposals, which resolve by 5.4 and the
  arbitration rule below.
- **TTL = 6 s**, owned by the proposer on its own clock. A receiver treats
  a proposal as live only while it keeps receiving the advertisement within
  its configured `PROPOSAL_TTL` window and has seen no abort, superseding
  decision or accepted commit. It never compares the proposer's clock with
  its own. A proposal that simply disappears expires locally; the proposer
  emits an explicit abort where possible.
- **Acknowledgement:** a required member that sees the live proposal and
  is *currently* recovery-eligible with matching `baseGen` sets
  `ackFor = proposalId` (the full 8-byte `decisionId`) in its readiness
  record, with a new sequence number, for the rest of the TTL. The ACK is
  an exact binding to that proposal.
- **ACK arbitration (one `ackFor` per record).** A member acknowledges
  only the *currently winning* live `ONLINE_PROPOSED` proposal for the
  current OFFLINE base, ranked by 5.4 (higher generation, then smaller
  `proposerTag`). When a higher-ranked proposal appears, the member clears
  its old `ackFor` and publishes a new `seq` with `ackFor` set to the
  winner, if it is still eligible. An ACK for a losing, superseded or
  expired proposal never counts toward the winner (`ackFor` mismatch). A
  proposer that sees a higher-ranked proposal abandons its own and
  acknowledges the winner, because it is a required member too. A proposer
  commits only while its own proposal is still the winner at the decision
  point.
- **Withdrawal:** a member that loses readiness immediately sets
  `recoveryEligible = false`, clears `ackFor`, and bumps its sequence. This
  cancels its ACK.
- **Abort:** TTL expiry without full ACKs, or any higher decision,
  abandons the proposal. Its generation stays consumed. A late ACK for an
  expired proposal cannot revive it.
- **Proposer lost:** the proposal expires; any eligible member re-proposes
  at `generationHighWater + 1`.
- **Restart:** proposals are ephemeral and are not persisted. A proposer
  that restarts never commits a proposal created before the restart. The
  proposal's generation stays consumed through the persisted high-water
  mark (5.3, 16.1), so the restarted phone cannot reuse it.

## 8.5 Commit

Only the proposer commits, and only when **all** hold at the commit
decision point:

- every required member has a live ACK for this proposal (in lease);
- those ACKs have all been present for at least `COMMIT_HOLD` = 1.5 s
  (one ACTIVE HELLO interval) with no withdrawal seen;
- no higher decision is known and `rosterHash` still matches.

**Commit decision point.** The proposer atomically snapshots every
required member's ACK record (sequence and lease), verifies the hold and
that no record with a higher sequence shows a withdrawal, and only then
constructs the commit. Withdrawals arriving after that point do not
retract it (8.6).

The commit keeps the proposal's generation (already reserved, so no new
reservation), sets `phase = ONLINE_COMMITTED`, and carries `readyBitmap`,
`excludedBitmap`, `manualExclusion` and the `ackSeqs` it used. It is a
distinct decision with its own `decisionId`.

**`ackSeqs` layout (unambiguous).** One 16-bit entry per
`meshParticipants` index, in canonical order. Entry *i* is the `seq` of
the ACK record (whose `ackFor` equals this proposal's ID) that the
committer held from member *i* at the decision point, in the epoch it then
considered current for member *i*; 0 for excluded members. The epoch is
not transmitted. The field exists to correlate logs across phones. It is
**not evidence**: it carries no sender epoch and receivers never use it
for validation.

**Receiver validation of a commit:**

1. Authenticated, correct session and **equal config version**;
   `proposerTag` maps to a mesh participant in this roster who is in
   `readyBitmap` and not excluded.
2. `rosterHash` equals the receiver's own (an ONLINE decision is never
   executed on a roster mismatch).
3. `readyBitmap` and `excludedBitmap` are disjoint and together cover every
   mesh participant; no unknown indices.
4. The exclusion rules in 9.3 hold. They are observation-independent: they
   use only the commit's own fields, the roster and the configuration.
5. It wins ordering (5.4); no higher OFFLINE is adopted.

A receiver that fails 1–5 does not execute ONLINE and keeps its safe mode.
A receiver that **missed the proposal and other members' records** still
validates the commit from these checks alone. It does not need to have
overheard anyone's readiness. This is what prevents a rider who missed
packets from being stranded on mesh while the rest go online.

**Under the crash-fault model the commit is a coverage certificate, not a
cryptographic proof** (1.3). `readyBitmap`/`ackSeqs` let receivers detect
inconsistency and let diagnostics reconstruct what the committer saw. A
receiver whose own fresh newer record contradicts an entry logs a
diagnostic; it still follows the commit (liveness over suspicion).

**Deterministic adoption.** Validation uses only the commit's fields, the
receiver's roster and the (equal) config version, never the receiver's own
transient observations. Every receiver therefore reaches the same
accept/reject outcome for the same commit. The proposer runs the same
validation function on its own commit before sending or executing it. A
commit that fails validation is rejected the same way everywhere: each
phone retains its previous safe decision, records `DECISION_CONFLICT` with
a diagnostic, keeps advertising its state, and converges when a higher
valid decision arrives (a new OFFLINE, or a valid re-proposal at a higher
generation).

## 8.6 Residual race (documented, not solved)

UDP is asynchronous. Member B can ACK, lose internet, publish a
withdrawal, and the withdrawal can reach A after A has committed.

The protocol bounds this with the lease, the 1.5 s hold, and "no
withdrawal seen". It cannot eliminate it. Therefore:

- Before any handoff each phone **rechecks its own** current LiveKit
  readiness and room identity.
- A phone that adopted a valid ONLINE commit but is no longer ready does
  **not** claim `ONLINE_ACTIVE`. It reports `RECOVERY_FAILED` (convoy
  decided ONLINE, local not ready), keeps its working mesh path if
  possible, and advertises the failure. It may later propose OFFLINE after
  normal confirmation, guards and the 60 s cooldown (9.4).
- A newer OFFLINE decision supersedes an ONLINE commit by ordering. A
  stale withdrawal cannot reverse a newer commit.

**Withdrawal timing (each case must have a test):**

| When the withdrawal happens | Outcome |
|---|---|
| Before the commit decision point | The ACK is gone, so the commit is blocked. The proposal expires or is re-acknowledged. |
| After the decision point, during commit construction | The commit proceeds from the snapshot. The withdrawing member rechecks locally before handoff; if not ready, `RECOVERY_FAILED`. |
| After construction, before transmission | Same. The proposer does not retract. |
| After transmission, before receipt | Same. Receivers validate by the 8.5 checks. |
| After a receiver adopted the commit | The commit stands. The executor rechecks before handoff; if not ready, `RECOVERY_FAILED`. A later OFFLINE decision may follow under 9.4. |

## 8.7 Recovery execution

After validating a commit: persist it, enqueue in the `HandoffExecutor`,
recheck local readiness, call `recoverToLiveKit()`, report
`ONLINE_ACTIVE` only after the handoff succeeds, keep mesh discovery in
standby only if the gate and battery policy allow, and on failure report
`RECOVERY_FAILED` and retain/re-enter mesh if usable. A failed local
recovery never creates a new generation.

| Local situation after a failed or skipped recovery | Local state | Voice |
|---|---|---|
| Rollback restored mesh ownership and the route is valid | `RECOVERY_FAILED` | Mesh voice stays active, but the rest of the convoy is online. UI: "Others returned online; you are on offline voice." |
| Mesh route is also lost and LiveKit is not ready | `VOICE_UNAVAILABLE` | None. Bounded retry of both transports. |
| LiveKit becomes ready later | `RECOVERY_FAILED` → retry | Recovery retried through the executor with backoff. |
| Rider cannot recover while the convoy stays ONLINE_COMMITTED | stays failed | May propose OFFLINE only after confirmation, guards and cooldown (9.4). |

## 8.8 Recovery liveness: never stuck offline **[T1]**

Without an escape, one failed mechanism (probe unavailable, a member that
never gets internet, repeated lost ACKs) keeps the whole convoy on 16 kbps
mesh for the rest of the ride and keeps the Wi-Fi locks on.

- **"Return online" action.** A recovery-eligible rider opens a screen
  that lists, **in full**, every rider who is not currently eligible and
  would be excluded. **One confirmation of that explicit list** starts a
  manual proposal with `manualExclusion = true` and exactly that
  `excludedBitmap` (9.1). If the list changes before the rider confirms,
  the screen refreshes and requires a new confirmation.
- **Stuck prompt.** Show a persistent, non-default prompt to an eligible
  rider when either (a) all other participants are eligible except some for
  ≥ `REACHABLE_NOT_READY_PROMPT` (120 s), or (b) three proposals in a row
  were abandoned.
- **No automatic acceptance.** A prompt never times out into acceptance.
- If safe LiveKit probing is unavailable (13), automatic readiness is
  disabled and recovery uses Protocol M (8.9).

## 8.9 Manual-only recovery (Protocol M) **[T1 when 13.3 applies]**

Used when safe LiveKit probing is unavailable (13.3), and valid whenever a
rider prefers to assert readiness themselves. It is the **same**
proposal / ACK / hold / commit protocol as 8.3–8.7. Only the **readiness
source** differs.

1. **Readiness source.** `recoveryEligible` is set by a deliberate rider
   assertion ("I have internet — return to online voice") instead of an
   automatic probe. The record's `readinessSource` bit is `MANUAL`.
2. **Validity of the assertion.** It is allowed only while
   `NET_CAPABILITY_VALIDATED` is true and no new total-loss episode has
   started. It lapses automatically when validated internet is lost or
   the rider cancels. It is renewed by the heartbeat only while those
   hold, and it is bound to the current OFFLINE `baseGen`.
3. **Prompt.** Every rider with validated internet is offered "Online
   voice may be available. Return online?" once per OFFLINE episode,
   after the 8 s stability period. It is non-default and never
   auto-accepted.
4. **Everything after the assertion is 8.3–8.7 unchanged:** a proposal
   when every required member has asserted, the exact ACK, the hold, the
   commit, local recheck before handoff, and `RECOVERY_FAILED` handling.
5. **One rider cannot pull the others online.** A rider who cannot
   connect never asserts, so recovery waits for them. Otherwise a rider
   uses "Return online" with an explicit exclusion list (8.8, 9.1). Only
   asserted readiness or an explicit confirmed exclusion can satisfy a
   required member.
6. **A wrong assertion.** If the trial handoff fails on a phone,
   `RECOVERY_FAILED`, mesh retained by rollback, the assertion is cleared,
   and the rider is prompted again only after `REFALLBACK_COOLDOWN`.
7. **Mixed convoys.** Automatic-probe and manual members can coexist; the
   record carries the source and both count the same.
8. **Residual.** A human assertion is less reliable than a verified
   LiveKit connection, so the 8.6 residual race is larger here. The local
   recheck and `RECOVERY_FAILED` path are mandatory, and field tests must
   measure the wrong-assertion rate.

---

# 9. Exclusions

An exclusion is scoped to **one ONLINE commit**. It is never a roster
removal. The excluded rider stays in the roster and is shown as
`EXCLUDED_FROM_ONLINE_RECOVERY`.

## 9.1 Manual exclusion **[T1]**

Covers members who are unreachable **or** reachable-but-never-ready (for
example a dead SIM on the whole route).

**Policy (UI and protocol are identical):** one confirmation by one
current mesh participant of **one explicit list** naming every rider who
will be excluded. There is no per-rider confirmation and no hidden
member. The proposal's `excludedBitmap` must equal the confirmed list
exactly; if the list changes before confirmation the rider must confirm
again. The screen states that excluded riders may lose voice contact with
the convoy. This is a deliberate product trade-off, not a group vote and
not proof that the excluded riders agree. Record who initiated it. It
also resolves a 2-rider convoy whose partner never recovers.

## 9.2 Automatic exclusion of unreachable members **[T2]**

A member may be excluded without a prompt only if all hold:

- it is in the roster and has not left;
- no valid state message from it for `UNREACHABLE_GRACE` (45 s, configurable);
- no fresh ready record from it;
- the proposer has evidence it was part of the offline convoy.

A missing HELLO never removes it from the roster; it only permits this
exception. A reachable-but-not-ready member is **never** excluded
automatically.

## 9.3 Exclusion validation (deterministic)

An earlier version let each receiver veto an exclusion based on what *it* happened to
hear. Different receivers could then accept or reject the same commit.
Receivers now validate using **only the commit's own fields, their roster
and their configuration**, never their transient observations.

**Receiver rules (identical on every phone):**

1. The proposer is never excluded and is in `readyBitmap`.
2. `excludedBitmap` and `readyBitmap` are disjoint and cover every mesh
   participant (8.5).
3. `manualExclusion = true`: accepted. A person confirmed the explicit
   list (9.1).
4. `manualExclusion = false`: a T1 build requires `excludedBitmap = 0`. A
   T2 build also accepts an automatic exclusion that satisfies the
   **majority limit**: fewer than half of the mesh participants excluded
   (6 participants: at most 2). A T2 build advertises a different
   `configVersion` from a T1 build, so mixed builds never take part in the
   same ONLINE decision (11.1).

**Proposer rules (the proposer's duty; receivers cannot check them):**

5. Never exclude a member for which you hold a live
   `recoveryEligible = true` record.
6. Exclude automatically (T2) only a member silent for `UNREACHABLE_GRACE`.
   A present-but-not-ready member is excluded only manually (9.1).
7. A receiver whose own observation contradicts an exclusion logs
   `EXCLUSION_CONTRADICTS_LOCAL_OBSERVATION` and still follows the commit.

**Why this is safe.** Excluding a member who is actually ready is
harmless: a ready member reconnects to LiveKit and joins the online
convoy by itself (9.4). The case that needs a human is excluding a
present-but-not-ready member, which is why rule 6 reserves it for manual
exclusion.

## 9.4 Excluded member returns **[T1]**

- **With LiveKit ready:** validate the current ONLINE decision, connect to
  the expected room, become an online participant. It must not pull the
  convoy back offline merely because it was excluded.
- **Without LiveKit but on the mesh:** adopt the latest ONLINE decision as
  the convoy decision, report that it cannot execute it, and propose a
  higher-generation OFFLINE **only** after normal failure confirmation,
  dwell and rate guards, **and** not within `REFALLBACK_COOLDOWN` (60 s) of
  the ONLINE commit that excluded it. This path drags healthy riders back
  to mesh, so it carries its own cooldown.
- **On neither transport:** show "voice unavailable", keep bounded
  discovery/retry, never claim the convoy was rejoined.

---

# 10. Partition and merge

Partitions will happen. Rules **[T1]**:

- Each component keeps exchanging state internally, keeps the full roster,
  and treats missing members as known-but-unreachable.
- A component may adopt any higher valid decision it receives.
- A component never concludes the others left the ride, and never commits
  ONLINE by ignoring members unless 9.1/9.2 permit.
- Competing decisions resolve by 5.4: e.g. OFFLINE gen 8 beats ONLINE gen 7;
  at equal generation OFFLINE beats ONLINE.
- Same-generation, same-phase, same-proposer ONLINE conflicts are
  equivocation (5.4.5): execute neither, keep the last safe local
  transport, resolve through a higher valid decision.

**Merge:** exchange state → validate session and (for ONLINE) roster hash
→ compare complete decisions → adopt the winner atomically → execute
locally only if prerequisites hold → re-advertise → reconcile local
execution status separately. Never merge only the mode or only the
generation.

---

# 11. Wire protocol

Final byte layout is produced in Phase 1 (Gate B). The widths below are the
**proposed** layout and the size calculation Gate B must confirm.

## 11.1 Versioning and carriage

- Add an explicit `extVersion` and `extLength`. Old clients ignore unknown
  extension data; new clients accept HELLOs without the extension.
- **State record carriage:** inside the HELLO extension if it fits the
  existing HELLO size budget; otherwise in a periodic `CONTROL(STATE)`
  packet (type 3) at the same cadence. Either way it is **periodic
  repeated state, never a one-time event**.
- **CONTROL (type 3)** is also used for immediate bursts on state change
  (new proposal, ACK, withdrawal, commit) to accelerate convergence. It is
  never the only source of truth. Before using type 3, confirm every
  existing decoder, filter and test handles an unknown type safely.
- If the existing version/type encoding cannot carry this safely, define a
  new mesh protocol version with a migration plan rather than overloading
  reserved bits.
- A `configVersion` byte covers the timer set.
- **OFFLINE adoption** requires only a session-authenticated packet whose
  decision part parses under a known `extVersion`. A different
  `configVersion` **never** blocks OFFLINE adoption, because OFFLINE is
  the safe direction.
- **ONLINE proposals, ACKs and commits** require an equal `extVersion`
  *and* an equal `configVersion`. A phone with a different value neither
  proposes, acknowledges nor executes ONLINE decisions, and the UI says an
  app update is needed.
- A phone that cannot parse a newer `extVersion` ignores the extension
  (it cannot adopt anything from it) and shows a diagnostic.

## 11.2 State record (proposed layout)

**Decision part (every state message):**

| Field | Bytes | Notes |
|---|---|---|
| extVersion, extLength | 2 | |
| configVersion | 1 | |
| generation | 4 | unsigned |
| modePhaseFlags | 1 | bit0 mode, bits1–2 phase, bit3 manualExclusion |
| proposerTag | 8 | roster-independent (5.1); tie-break key |
| rosterHash | 8 | truncated SHA-256 |
| baseOfflineId | 8 | zero for OFFLINE |
| excludedBitmap | 1 | |
| readyBitmap | 1 | commit only |
| decisionId | 8 | |
| ackSeqs | 12 | commit only (6 × 2) |

≈ 54 bytes worst case.

**Readiness record, one per mesh participant (own + second-hand):**

| Field | Bytes | Notes |
|---|---|---|
| rosterIdx | 1 | origin member |
| senderEpoch | 4 | origin `MeshPacketV4.epoch` |
| seq | 2 | increments on every emission, within that epoch |
| flags | 1 | livekitReady, recoveryEligible, meshReady, netLost, localMode (2 bits), readinessSource (1 bit: auto probe / manual assert) |
| baseGen | 4 | OFFLINE generation this member has adopted |
| ackFor | 8 | proposalId being acknowledged, else zero |
| age | 2 | ms/100: record age as measured by the re-advertiser, including hop penalties (11.3); 0 in an origin record |
| hops | 1 | re-advertisement hops so far; 0 in an origin record |

= 23 bytes × 6 = 138 bytes.

**Worst case for six participants:** ~196 bytes of extension, plus the
existing HELLO fields (~60–80 B), 17-byte header and 12-byte tag: roughly
290–310 bytes, well under a 1,472-byte UDP payload. **Phase 0/1 must check
whether an existing HELLO size cap is lower; if so use CONTROL(STATE).**
Fragmentation is not allowed unless explicitly designed, bounded and
tested.

**Identifier widths (decision).** The normative default is 8 bytes for
`rosterHash`, `baseOfflineId`, `decisionId`, `proposerTag` and `ackFor`.
Under the crash-fault model (1.3) these identifiers only need to avoid
*accidental* collisions: for 1,000 decisions in a ride the chance of two
IDs colliding is about 3×10⁻¹⁴, and two different rosters colliding on
an 8-byte hash is about 5×10⁻²⁰ per comparison. The expensive field is
`ackFor`, repeated in up to six readiness records. Widths are versioned
codec constants (a change bumps `extVersion`). Phase 1 **may** widen
`rosterHash`, `baseOfflineId` and `decisionId` to 16 bytes if the size
budget allows without forcing CONTROL(STATE) or exceeding any HELLO cap,
and must record that choice. Widen all identifiers to 16 bytes if T3 is
ever adopted.

## 11.3 Multi-hop readiness propagation **[T1 if Phase 0 shows multi-hop; else T2]**

Without it, a proposer in a chain A–B–C–D never sees D's ACK.

**Two carriage paths, never mixed inside one packet.**

**Path 1: forwarded original packets.** The existing forwarding engine
rewrites only the header fields `ttlHop` and `prevShort`. The ciphertext
and GCM tag are untouched, so no relay ever edits an authenticated field.
The readiness record inside is the origin's own (`hops = 0`, `age = 0`).
The **receiver** computes the transit age itself:
`hopsTravelled × HOP_PENALTY`, taking `hopsTravelled` from the header hop
information. Phase 0 must confirm that the header carries a usable hop
count and a known initial TTL. If it does not, Path 1 cannot carry
readiness across hops.

**Path 2: re-advertisement** (needed if state messages are not
forwarded). A node composes a **new** state message with its **own**
sequence, epoch and AES-GCM protection, embedding copies of other members'
latest live records. These copies are second-hand claims by the
re-advertiser. It may change only `age` and `hops`: `hops += 1` and
`age += heldMs + HOP_PENALTY`, where `heldMs` is its own monotonic time
since it received the record. `rosterIdx`, `senderEpoch`, `seq`, flags,
`baseGen` and `ackFor` are copied unchanged. Drop a record whose `hops`
would exceed 5 or whose `age` ≥ lease.

**Receiver freshness.** Effective origin time = `receivedAt − age −
HOP_PENALTY` (the last link). A direct observation of the origin always
takes precedence over second-hand copies.

**Trust.** A second-hand record is a claim by an authenticated session
member, accepted under the crash-fault model (1.3). It can never be fresher
than the origin's own sequence progress allows (next bullet).

- **Forwarding cannot renew freshness.** A record refreshes a lease only
  with a strictly higher `seq` within the sender's current epoch (11.6). An
  old record relayed again carries an old `seq` and changes nothing.
- **Explicit assumption:** real network transit per hop is at most
  `HOP_PENALTY`. Phones have no synchronized clock, so transit time is not
  measured. The protocol does **not** claim to bound age under arbitrary
  delay. If transit exceeds the assumption, the effect is the same class
  as the 8.6 residual race, mitigated by the commit hold, exact ACK
  binding and the local recheck before handoff. Phase 0 measures real
  per-hop delay to validate the value (22.2).
- The decision itself spreads by the same mechanism: a node that learns a
  higher valid decision updates its accepted state and advertises it on
  its next state message and a bounded CONTROL burst.
- On one shared hotspot every member is normally one hop away, unless the
  hotspot blocks client-to-client traffic. Phase 0 checks this and the
  existing HELLO forwarding. If neither HELLO nor state messages are
  forwarded across hops, forwarding (Path 1) or re-advertisement (Path 2)
  must be added in the permitted mesh layer before claiming multi-hop
  behavior.

## 11.4 Parsing safety **[T1]**

Every decoder: checks min/max size before reading; validates extension
version and exact length; rejects invalid enums, roster indices, bitmap
bits outside the participant count, and malformed IDs; avoids unbounded
allocation; never crashes on truncated, duplicated, reordered or unknown
packets; rate-limits malformed-packet diagnostics; preserves old-packet
behavior.

## 11.5 Authentication and replay

Reuse the existing AES-GCM protection and replay windows. No second crypto
system. A packet from another session cannot change this convoy's decision;
a replayed old decision is ignored by generation ordering. Relays (the
existing forwarding engine) change only `ttlHop` and `prevShort` and never
touch ciphertext or tag. A second-hand record is composed by a
re-advertiser inside its own authenticated message (11.3); no relay ever
edits another node's authenticated bytes.

## 11.6 Sender incarnation = `MeshPacketV4.epoch` **[T1]**

The v3 challenge/response handshake is removed. It adds a way to get stuck
"non-ready" over a lossy network and defends against a threat outside the
trust model.

- **Phase 0 must confirm** that `epoch` changes on every sender restart.
  Strong evidence: nonce `(type, seq, epoch)` and key `HKDF(srcShort,
  epoch)` would repeat on restart otherwise, which is catastrophic for
  AES-GCM.
- Per member, track a **current epoch** and a small in-memory **retired
  epoch list** (last 8). A record with a retired epoch is ignored. A
  never-seen epoch becomes current and retires the previous. Sequences
  compare only within the current epoch.
- Delayed packets from a retired epoch are ignored even if reordered after
  the new epoch is accepted.
- Residual: an old epoch this receiver never saw could briefly look new.
  Impact is bounded by the 6 s lease and by the commit hold (1.5 s, all
  ACKs consistent).
- **Collision bound.** With a 32-bit random epoch and *n* restarts of one
  sender inside one offline session, two incarnations collide with
  probability about n²/2^33 (n = 20 gives about 5×10⁻⁸). That is
  acceptable for this protocol's correctness. Nonce uniqueness of the
  *existing* AES-GCM design is a separate matter: Phase 0 must report it
  as its own finding (22.2 item 1) and this specification does not change
  the crypto.
- **Receiver restart.** The retired-epoch list is in memory and is lost on
  restart, so a restarted phone could mistake a delayed old-epoch packet
  for a new sender. After a restart the phone enters a **warm-up of
  `EPOCH_WARMUP` (6 s)**: it adopts decisions normally, but counts no
  member's readiness record as fresh until it has seen that member's epoch
  unchanged across two consecutive records spanning at least one ACTIVE
  interval. Replay state restarts with the process, as in the existing
  design.
- **Why the exposure is narrow.** A commit needs an ACK bound to the
  exact `decisionId` of a proposal created within the last `PROPOSAL_TTL`.
  An old-incarnation packet cannot carry such an ACK unless that
  incarnation was alive within the last seconds. Epoch ambiguity can
  therefore affect only *proposal gating* (the eligibility flags), never
  the commit itself.
- If Phase 0 cannot establish **all** of the properties in 22.2 item 1,
  introduce a dedicated random 64-bit `senderIncarnation`, generated from
  a CSPRNG at each process start and carried in every readiness record,
  with the same retired-list and warm-up handling. Do not approve the
  `epoch` assumption from the nonce argument alone.

---

# 12. Single-flight handoff executor **[T1]**

Serialize all handoffs. Never cancel at an arbitrary point if that could
leave audio ownership ambiguous.

1. Keep one `desiredDecision` (highest valid) and one
   `actualLocalTransport`.
2. If a handoff is in flight, do not start another.
3. When it finishes or rolls back, compare actual state with the **latest**
   desired decision (not the one that started the handoff) and execute it
   if prerequisites hold.
4. Duplicates are idempotent. A newer decision is never discarded because
   a handoff is in progress.
5. Every queued action carries its `decisionId`; a superseded action is
   dropped before it starts.
6. A failed handoff rolls back via the existing coordinator and is
   reported.
7. Never run `recoverToLiveKit()` while `fallbackToMesh()` is mid-way, and
   never both.
8. Repeat until actual state agrees with desired, or a bounded retry limit
   is reached. `AudioOwnershipArbiter` stays the only authority on
   ownership; the executor only sequences calls.

Test rapid sequences (several newer decisions during a partition merge).

---

# 13. LiveKit readiness probing while OFFLINE

Recovery needs `livekitReady`, but `LiveKitManager` stops after 10
attempts and is CI-frozen. After a long outage readiness would never be
reached without something driving reconnects.

## 13.1 Behavior **[T1, conditional on 13.2]**

Mesh-side integration (allowed paths) drives bounded probing through the
existing public API:

- attempt one reconnect/verify on each `NET_CAPABILITY_VALIDATED` → true;
- while validated internet exists but LiveKit is not READY, retry every
  `LIVEKIT_PROBE_INTERVAL` (15 s growing to 60 s);
- no attempts while there is no validated network (saves battery);
- run `ensureFreshToken()` first if the JWT is expired or near expiry;
- report `NOT_READY → CONNECTING → READY | FAILED` into
  `LiveKitReadiness`.

## 13.2 It must be proven audio-silent

Before enabling, source inspection **and instrumented tests** must prove
that reconnecting, refreshing a token, verifying room identity and
subscribing to control state do **not**: acquire mic capture, publish a
local audio track, start WebRTC ADM capture, subscribe/play remote audio,
move `AudioOwnershipArbiter` away from `OFFLINE_MESH`, or route audio
through LiveKit while mesh owns the HAL.

If the existing API cannot do this, use a dedicated control/readiness
connection path. Do not edit frozen `LiveKitManager` paths without explicit
allowlist approval.

## 13.3 If safe probing is impossible

Expose `LIVEKIT_PROBE_UNAVAILABLE`. Automatic readiness is unavailable and
**`livekitReady` is never faked**. Recovery then uses **Protocol M** (8.9),
where readiness comes from the rider's assertion and the normal
proposal/ACK/commit protocol applies. The preferred alternative is an
allowlisted minimal change that makes a safe probe possible. Decide before
T1 ships.

---

# 14. Battery and standby

Battery claims are measured, not assumed.

- **ONLINE and healthy:** no mesh mic capture, no Opus encode, no mesh
  AudioTrack, no ownership acquisition. Only the standby discovery/state
  traffic at the existing STANDBY cadence. No unnecessary high-performance
  Wi-Fi locks.
- **During SUSPECT:** bounded BOOST burst only (7.2).
- **Active mesh:** only after OFFLINE is adopted and the route validates;
  use the existing BOOST then ACTIVE cadence; avoid duplicate discovery
  loops.
- **LiveKit while OFFLINE:** keep the connection only when validated
  internet exists, with no audio published or subscribed (13). Do not keep
  retrying with no network.
- **Measure:** standby power over a representative ride, state-message
  count/bytes, Wi-Fi and multicast lock durations, CPU wakeups from mesh,
  time in BOOST/ACTIVE/STANDBY, audio-pipeline active time, battery delta
  under comparable conditions. Use `dumpsys batterystats`. Do not claim "no
  extra cost" until measured. Thermal behavior on a hot handlebar mount
  must be tested.

---

# 15. Timers and consistency

| Parameter | Initial value | Tier | Notes |
|---|---|---|---|
| Failure confirmation | 10 s | T1 | Existing |
| Recovery stability | 8 s | T1 | Existing |
| Minimum dwell | 20 s | T1 | Existing; proposals only |
| Transition limit | 3 / 120 s | T1 | Existing; proposals only |
| Readiness lease | 6 s | T1 | Receiver clock; renewed only by a newer `seq` |
| `HOP_PENALTY` | 500 ms per relay | T1 | Assumed max transit per hop; measure in Phase 0 |
| `EPOCH_WARMUP` | 6 s | T1 | After receiver restart |
| `SEQ_RESYNC` | 10 min | T1 | Discard a member's `seq` baseline after this much silence |
| `PROPOSAL_TTL` | 6 s | T1 | |
| `COMMIT_HOLD` | 1.5 s | T1 | One ACTIVE interval |
| Proposal stagger | `rosterIndex × 300 ms` | T1 | |
| `REFALLBACK_COOLDOWN` | 60 s | T1 | Per excluded member |
| `REACHABLE_NOT_READY_PROMPT` | 120 s | T1 | |
| `LIVEKIT_PROBE_INTERVAL` | 15 s → 60 s | T1 | |
| `UNREACHABLE_GRACE` | 45 s | T2 | |
| Auto-exclusion limit | < half of participants | T2 | |
| HELLO BOOST / ACTIVE | 750 ms / 1.5 s | existing | |
| HELLO STANDBY | existing value | existing | Do not change without measurement |

**Constraints (validate at startup):**

1. `UNREACHABLE_GRACE` > lease + stability + `PROPOSAL_TTL` + `COMMIT_HOLD`
   (6 + 8 + 6 + 1.5 = 21.5 s).
2. Lease ≥ 3 × ACTIVE interval, and lease > maxHops × `HOP_PENALTY` + one
   ACTIVE interval (5 × 0.5 + 1.5 = 4 s).
3. All values are protocol constants advertised via `configVersion`;
   phones on different values do not take part in ONLINE decisions.
4. Treat values as defaults for controlled field validation, not proven
   production constants.

**Backoff:** bounded exponential for repeated local failures; reset only
after a stable success; a repeated state message never resets backoff;
duplicate proposals never create retries; never spin on an unavailable
route.

---

# 16. Persistence and process death

## 16.1 What is persisted **[T1]**

Session ID; snapshot and `rosterHash`; highest accepted decision (ID,
generation, phase, protocol version); **`generationHighWater`**; local
transition history for rate limits; offline session expiry; last local
execution state (diagnostics only).

Write the accepted decision **atomically** before executing it. Persist
`generationHighWater` **write-ahead**, before sending any proposal (5.3),
and again whenever an observed decision raises it. A torn or partial
record is treated as corrupt (16.2).

**Not persisted as truth:** `reachablePeers`, a peer's old `livekitReady`
or `meshReady`, an assumption that a socket is connected, a transient route
result.

## 16.2 Lost or corrupt generation (no guessing) **[T1]**

A phone that loses or corrupts its generation record **or its high-water
mark** MUST:

1. enter `GENERATION_UNKNOWN`;
2. **stop creating decisions** for this session (it still follows and
   executes decisions from others);
3. keep authenticated discovery running;
4. adopt a valid current decision learned from an authenticated peer,
   set its high-water mark to at least that generation, and persist both
   atomically before proposing again.

It MUST NOT initialize the generation to 0 or 1 and MUST NOT conclude the
convoy is at a low generation because nothing was heard within a timeout.
Silence is not proof that a higher generation does not exist. In practice
this costs nothing: a lone phone with no peers has no one to tell.

## 16.3 After restart

Restore the encrypted session, snapshot and highest decision → reinit
discovery → revalidate the local route → reconnect/refresh LiveKit if
appropriate → revalidate room identity → reconcile with received state →
only then report the actual local transport. A persisted `OFFLINE_ACTIVE`
is **not** proof that mesh audio is active after a restart.

---

# 17. Security and robustness

Treat every packet as untrusted until authenticated and validated.

- Bind every decision to the session; bind ONLINE decisions to the roster
  hash; validate proposer membership; reject unknown proposer indices,
  invalid exclusion bits, invalid phase/mode combinations, and ONLINE
  commits that exclude more than policy permits.
- Never accept a decision from a non-member or from another session merely
  because the short ID matches.
- Bounded parsing. Do not log pairing secrets, keys or tokens. Do not log
  full stable member IDs in ordinary logs. Rate-limit diagnostics.
- A hash is a consistency check, not proof of authorization.
- Keep existing AES-GCM and replay protection unchanged.
- **T3 only:** signed per-member ACK evidence, an authenticated
  incarnation-change handshake, and a commit that carries verifiable
  evidence for every required member. Requires per-member keys distributed
  at provisioning; do not start it without a threat-model decision and a
  backend allowlist.

---

# 18. UI

The UI must never show one green "Offline connected" for the whole convoy
if individual riders have failed locally.

- **Convoy status:** Online voice · Switching convoy to offline voice ·
  Offline voice selected · Returning to online voice · Offline voice
  limited (some riders unreachable) · Group offline voice unavailable for
  more than 6 mesh participants.
- **Per rider:** Online · Offline mesh connected · Switching · Mesh
  unavailable · LiveKit reconnecting · Excluded from online recovery ·
  Voice unavailable · Cannot hear mesh riders (non-mesh participant).
- **Decided is not done.** When the convoy decision is ONLINE but this
  phone's own transport is not (`RECOVERY_FAILED`, still recovering, or on
  mesh), say so plainly: "Convoy is back online. You are still on offline
  voice." This matters most under Protocol M, where readiness is a rider's
  assertion.
- Never say "left convoy" for a rider who merely vanished from HELLO.
- **Pre-ride readiness:** accepted riders, mesh-ready, opted out,
  unavailable/timed out, whether group fallback is enabled, the shared
  Wi-Fi requirement, the six-device maximum, **and the trade-off that one
  rider's loss moves everyone to mesh voice**. If more than 6 are mesh
  participants, state that group fallback is disabled. If some riders are
  non-participants, say they will not hear mesh riders after a fallback.
- **Prompts** (8.8, 9.1): affirmative, non-default action, names the
  rider, warns they may lose contact, never auto-accepts.

---

# 19. Pure Kotlin coordinator API

```kotlin
interface MonotonicClock { fun nowMs(): Long }

data class MemberObservation(
    val memberId: String,
    val lastStateAtMs: Long?,
    val netState: NetState,
    val livekitReadiness: LiveKitReadiness,
    val recoveryEligible: Boolean,
    val meshReadiness: MeshReadiness,
    val localExecutionState: LocalTransportState,
    val readinessSeq: Long,
    val senderEpoch: Int,
    val baseGen: Long,
    val ackFor: ByteArray?,
    val ageMs: Int
)

data class CoordinatorInput(
    val localMemberId: String,
    val membership: ConvoyMembershipSnapshot,
    val currentDecision: TransportDecision?,
    val observations: List<MemberObservation>,
    val localTotalLossConfirmed: Boolean,
    val localRecoveryStable: Boolean,
    val localMeshRouteAvailable: Boolean,
    val generationHighWater: Long,
    val manualReadyAsserted: Boolean       // Protocol M readiness (8.9)
)

data class CoordinatorOutput(
    val acceptedDecision: TransportDecision?,
    val actions: List<TransportAction>,
    val generationToReserve: Long?,        // persist BEFORE sending a proposal
    val diagnostics: List<ProtocolDiagnostic>
)
```

Input: immutable observations. Core: deterministic evaluation. Output:
immutable actions. Side effects: Android integration. Time: injected.
Persistence: an interface outside the pure logic. Never pass `Context`,
`Room`, `AudioManager`, `DatagramSocket` or coroutine scopes in.

---

# 20. Test simulator and invariants

## 20.1 Simulator **[T1, before any Android work]**

Virtual time, deterministic seeds, reproducible traces. Supports: multiple
nodes; packet loss, duplication, reordering and variable delay; partitions
and merges; node crash and restart; delayed state messages; stale readiness
records; different local connectivity and route availability; conflicting
proposals; membership mismatch; **line, chain, star and partitioned
topologies**. No `Thread.sleep`; advance virtual time.

## 20.2 Invariants to assert

1. A node never decreases its accepted generation or its generation
   high-water mark.
2. A node never merges fields from two decisions.
3. A stale decision never overrides a newer one.
4. OFFLINE wins an equal-generation ONLINE/OFFLINE conflict.
5. A proposer's own commit never conflicts with its own proposal.
6. A phone never reports `OFFLINE_ACTIVE` without a successful local mesh
   activation.
7. A phone never reports `ONLINE_ACTIVE` without verified LiveKit room
   identity and a successful local activation.
8. Audio ownership is never `LIVEKIT` and `OFFLINE_MESH` at once.
9. A missing HELLO never removes a member from the roster.
10. An ONLINE commit never silently omits a member; every participant is
    in `readyBitmap` or `excludedBitmap`, never both.
11. Commit validation is observation-independent: the same commit gives the
    same accept/reject outcome on every phone with the same roster and
    config. A proposer never excludes a member for which it holds a live
    ready record. A T1 build never accepts an automatic exclusion.
12. A stale or expired readiness record never satisfies recovery, and a
    relayed record never outlives its lease.
13. Duplicate messages are idempotent.
14. A missed CONTROL burst is recovered from periodic state messages.
15. A roster mismatch never produces a valid ONLINE commit, and never
    blocks an OFFLINE decision.
16. A restart never treats stale local transport state as active without
    revalidation.
17. A failed handoff never creates an unbounded retry loop.
18. The transition-rate guard is enforced on proposals and never blocks an
    accepted decision.
19. A phone with no path cannot be assumed to have received a decision.
20. Handoffs never run concurrently, and the final local state converges
    to the latest accepted decision.
21. A restarted sender's readiness is accepted after its epoch changes and
    delayed retired-epoch packets are ignored.
22. A phone in `GENERATION_UNKNOWN` never proposes.
23. A rider-driven path (explicit exclusion list or Protocol M) can always
    produce a valid ONLINE commit whenever the initiator is eligible.
24. A node never uses one generation for two different proposals, including
    across process restarts.
25. Forwarding or retransmitting an old readiness record never renews its
    lease.
26. OFFLINE adoption, tie-breaking and decision IDs never depend on the
    local roster.
27. A manual exclusion bitmap always equals the list the rider confirmed.
28. A restarted receiver counts no readiness record as fresh during
    warm-up.
29. A member's `ackFor` references only the currently winning live proposal
    or is zero; an ACK for a losing, superseded or expired proposal never
    counts toward the winner.
30. A relay never alters another node's authenticated bytes. A second-hand
    record differs from its origin copy only in `age` and `hops`.
31. A commit that fails validation leaves every phone on its previous safe
    decision with `DECISION_CONFLICT`, and the proposer's own commit passes
    the same validation before it is sent or executed.

---

# 21. Edge-case test matrix (mandatory)

Every test asserts **both** the convoy decision and each node's actual
local transport.

**Fallback.** One rider loses all internet with a route; with no route; two
riders at once; all riders at once; loss then recovery inside the 10 s
confirmation; OFFLINE received after missing the proposal; the same OFFLINE
repeatedly; an older ONLINE after adopting OFFLINE; OFFLINE adopted despite
a roster mismatch; adopted within 20 s of the last handoff.

**Recovery.** One rider recovers to the right LiveKit room; socket
connected but wrong room; reconnect with expired token; token refresh
succeeds/fails; eligible for 7.9 s then loses connectivity; record goes
stale before commit; all members ready; a member unreachable beyond grace;
**commit received after all CONTROL bursts were lost**; commit received by a
rider who missed the proposal and other members' records; `recoverToLiveKit()`
fails after commit; a rider in `readyBitmap` loses internet before the
commit arrives.

**Proposal lifecycle and race.** Proposer commits its own proposal (no
false equivocation); TTL expires with one ACK missing; withdrawal just
before commit; withdrawal during commit construction; after construction
before transmit; after transmit before receipt; after the receiver adopted;
proposer disappears; several members eligible at once; a stale ACK for an
expired proposal cannot revive it; repeated abandoned proposals trigger the
stuck prompt.

**Concurrent decisions.** Two riders propose OFFLINE at the same
generation; ONLINE and OFFLINE at the same generation; two ONLINE proposals
with different proposers; two ONLINE commits with different exclusion
bitmaps; the same proposer emitting two different decisions at the same
generation and phase; a lower generation after a higher one; unknown
proposer; invalid roster hash.

**Partition and merge.** One rider separated from five; 3 vs 3; one side
proposes OFFLINE while the other stays ONLINE; both propose OFFLINE;
ONLINE proposal vs OFFLINE; merge after an exclusion commit; an excluded
rider returns after grace; a member repeatedly disappearing and returning;
hotspot owner loses power; Wi-Fi interface change and socket rebind; a
partition longer than the LiveKit token lifetime; **a 4+ node chain where
the proposer must learn the far member's readiness**; relay changes hop
metadata but not the origin decision or record.

**Membership.** Joins/leaves before ride start; joins/removals while
online; different `localRevision` counters with identical rosters (hashes
match); same members with different network observations (hashes equal);
same members with a different role (hashes differ, ONLINE blocked);
duplicate member IDs rejected; Unicode normalization variants hash
deterministically; short node-ID collision; `OPTED_OUT`, `UNAVAILABLE`,
`TIMED_OUT` members; six mesh participants plus opted-out roster members;
seven mesh participants (group fallback disabled, per-device fallback
suppressed).

**Generation and incarnation.** Missing generation record with no peers:
does not propose; torn/corrupt record → `GENERATION_UNKNOWN`; adopts a
higher decision from a peer and persists before proposing; delayed lower
generation after restart ignored; restart resets readiness sequence and is
accepted via the new epoch; delayed packet from a retired epoch ignored.

**Exclusion and product.** Manual prompt times out: no acceptance; explicit
confirmation creates a proposal with `manualExclusion=true`; UI warns the
excluded rider may lose contact; excluded rider stays in the roster;
excluded rider returns with no internet during the 60 s cooldown: no
immediate OFFLINE proposal; a proposer holding a live ready record for a
member does not exclude it; a commit that excludes a ready member is
accepted identically by a phone that hears that member and one that does
not; a T1 build rejects an automatic exclusion; automatic exclusion of
half or more rejected **[T2]**; a reachable-but-never-ready rider for a whole ride
(prompt then manual exclusion); a two-rider convoy whose partner never
recovers.

**LiveKit and audio.** A probe while mesh owns audio: no ADM capture, no
mic publish, no remote subscribe/play, no change to the arbiter; token
refresh during active offline voice does not interrupt mesh; probe API
unavailable: readiness stays false, no fake recovery; a 40-minute outage
that exhausts reconnect attempts, then internet returns; PTT, VOX and audio
focus change during a serialized handoff create no dual ownership; a new
decision arrives during the 400 ms quiet guard and the executor converges
to the latest.

**Lifecycle.** Process killed while ONLINE, OFFLINE, during fallback and
during recovery; device reboot during an offline session; offline session
expires after 8 h; repeated network-callback validation changes; duplicate
and reordered packets during a transition; malformed lengths and unknown
extension versions; battery saver/doze changes discovery cadence;
Bluetooth route changes during fallback.

**v6 additions (kept).** Proposer tag: two phones with different rosters adopt the
same OFFLINE decision and break a same-generation tie identically.
Generation: proposal aborted, process killed, restarted phone proposes
again with a new generation (never the aborted one); observed aborted
proposal raises the high-water mark on other phones; write-ahead
reservation survives a crash before send. Freshness: an old record relayed
repeatedly never renews a lease; a record delayed in transit within and
beyond `HOP_PENALTY`; a 5-hop chain stays inside the lease. Restart: a
restarted receiver ignores readiness during warm-up; a delayed old-epoch
packet after receiver restart cannot satisfy a commit because the ACK is
bound to the proposal. Protocol M: a rider asserts readiness and
validated internet is then lost (assertion lapses, ACK withdrawn); one
rider asserts while others cannot (no recovery without an explicit
confirmed list); mixed auto/manual members; a wrong assertion causes
`RECOVERY_FAILED` and a cooldown before the next prompt. Exclusion list:
the confirmed list changes before confirmation (re-confirm required);
proposal bitmap equals the confirmed list. Compatibility: a different
`configVersion` still adopts OFFLINE but never ONLINE; an unknown newer
`extVersion` is ignored with a diagnostic. Failure outcomes: rollback keeps
mesh (`RECOVERY_FAILED`), mesh also lost (`VOICE_UNAVAILABLE`), later
retry.

**v7 additions.** Competing proposals: two members create ONLINE
proposals at the same generation and a third acknowledges one; a
higher-ranked proposal supersedes an acknowledged one (old `ackFor`
cleared, new `seq` published); an ACK for the loser never counts for the
winner; a proposer abandons its own proposal on seeing the winner; a
proposer commits only while still the winner. Relay model: a forwarded
packet's age is the receiver-side hop count × `HOP_PENALTY`; a
re-advertised record differs from its origin copy only in `age` and
`hops`; a record past 5 hops or past the lease is dropped; direct
observation beats second-hand copies; a relay path that never touches
ciphertext keeps GCM verification valid. Sequence: a member silent for
over `SEQ_RESYNC` is treated as new with warm-up; no comparison crosses a
wrap. Commit validation: the same commit evaluated by phones with
different observations gives the same outcome; a structurally invalid
commit is rejected everywhere, leaves `DECISION_CONFLICT`, and converges
when a higher valid decision arrives; the proposer self-validates and does
not execute an invalid commit.

---

# 22. CI, repository integration and phases

## 22.1 CI constraints (from the design doc)

- Frozen unless allowlisted: `LiveKitManager.kt`, `state/`, `audio/`,
  `VoiceForegroundService.kt`, backend, web app, LiveKit server.
- Mesh invariants: no broad `Throwable` catches, no `Thread.sleep` in mesh
  tests, no `runCatching` around suspend audio/transport calls; require
  `NET_CAPABILITY_VALIDATED`.
- Minimum JVM test census 704; required test matrix; release DEX checks
  for the offline mesh build flags.
- **CI path matching is authoritative.** Inspect the scripts and
  `.github/frozen-allowlist`, resolve every file path, produce a
  file-change plan, and verify no frozen file is touched without explicit
  allowlisting. Add tests without deleting or weakening existing ones.
  Everything in this design lives in `mesh/` (switching, transport, codec).

## 22.2 Phases

**Phase 0 — Repository verification (no code changes).** Read the actual
coordinator, state machine, HELLO codec, packet parser, session
persistence and handoff code. Record and report any mismatch with this
document:

1. `MeshPacketV4.epoch` (11.6). Establish **all** of: its actual bit
   width; that it is generated independently per sender from a CSPRNG;
   that it changes on every process restart; that it is not reused after a
   device reboot; the exact AES-GCM nonce and key construction and whether
   nonce reuse is possible under an epoch collision; the numeric collision
   probability for realistic restart counts; that a receiver can tell an
   old delayed packet from a new incarnation; and what replay state exists
   and how it behaves after a receiver restart. If any property cannot be
   established, use the dedicated 64-bit `senderIncarnation` in 11.6. Do
   not compromise on this. Report any nonce-reuse hazard in the existing
   crypto as a separate, standalone finding.
2. Where `stableMemberId` comes from, and that it is identical on every
   device (4.3).
3. Whether HELLO is forwarded across hops, what `ttlHop` actually carries
   (a usable hop count and a known initial TTL), and whether the shared
   hotspot blocks client-to-client traffic (11.3).
4. The existing HELLO size cap, and whether any decoder/filter/test breaks
   on packet type 3 (11.1).
5. What a `LiveKitManager` reconnect does to mic/publish/subscribe/arbiter
   state, and the public API available (13).
6. How the existing per-device fallback is switched off for ineligible
   convoys (1.4).
7. Mesh session persistence atomicity (16.1), and the arbiter's ownership
   transitions and rollback.
8. Measured one-way mesh transit delay and per-hop forwarding delay on real
   devices, to validate `HOP_PENALTY` (11.3).

*Exit: a written mismatch report. Each item above has a verdict, the
evidence, and exact file and line references; the frozen-file and CI
restriction list is included; no code was changed.*

**Phase 1 — Byte-level protocol appendix.** Exact field order, widths,
endianness, maximum sizes; canonical serialization for the roster hash and
decision IDs; compatibility and old-version behavior; malformed/oversized
packet behavior; relay behavior; worst-case packet sizes for six
participants.
*Exit: no approximate sizes or undefined transitions remain, and published
test vectors exist and pass for: `rosterHash`, `proposerTag`, every
`decisionId`, `proposalId` recomputation from a commit's own fields,
commit coverage validation (disjoint and complete bitmaps), and `ackSeqs`
ordering.*

**Phase 2 — Pure Kotlin coordinator (T1).** Immutable models, comparator,
roster snapshot and hash, fallback adoption, proposal lifecycle,
acknowledgements, commit validation, manual exclusion, generation
persistence interface, executor model, injected clock.
*Exit: deterministic JVM tests pass with no Android dependencies.*

**Phase 3 — Simulator and adversarial tests.** Section 20 and the
matching rows of Section 21.
*Exit: invariants hold under loss, duplication, reordering, partitions and
restart.*

**Phase 4 — Wire protocol.** State record/HELLO extension, CONTROL type 3,
versioning, bounds checks, old/new compatibility, malformed and replayed
packets.
*Exit: codec and integration tests pass.*

**Phase 5 — Android integration.** Connect health signals to intents,
decisions to `VoiceSessionCoordinator` through the executor, report local
state, keep standby audio inactive, prove audio-silent LiveKit probing,
preserve arbiter/quiet guard/rollback.
*Exit: no duplicate audio owner, no false "active" status, no unbounded
retries.*

**Phase 6 — UI and pre-ride gate.** Section 18.
*Exit: riders can tell whether voice is online, offline, unavailable or
degraded.*

**Phase 7 — Field validation (gate for T1 release).** Real devices and
real helmet/Bluetooth hardware. Measure: switching latency, lost-voice
duration during handoff, recovery latency, **failed-recovery rate (Protocol
M assertions or automatic readiness that did not reach LiveKit)**, mesh
packet loss, state-message
overhead, battery, Bluetooth routing stability, thermal behavior, tunnel
and hotspot-owner failure, and **whether riders understand the degraded
states**. Tune the timers from measurements.

**Phase 8 — T2 hardening.** Automatic exclusion, majority limit, grace
tuning, long-outage behavior. Same exit discipline.

**T3 is not scheduled.** Start only after an explicit threat-model
decision.

---

# 23. Acceptance criteria

## T1 (first release)

- [ ] No central leader and no backend dependency for offline decisions.
- [ ] Generation is durable and monotonic; loss or corruption blocks
      proposing until recovered from an authenticated peer.
- [ ] Decision IDs are canonical, versioned and mode-specific; ordering
      includes phase; a commit never conflicts with its own proposal.
- [ ] OFFLINE adoption is session-authenticated, idempotent, and never
      blocked by a roster mismatch or by dwell/rate guards.
- [ ] `rosterHash` excludes mutable fields; `localRevision` is never used
      across devices.
- [ ] ONLINE recovery requires the exact `rosterHash` and a full-ID
      proposal ACK from every required member.
- [ ] The commit is a coverage certificate (`readyBitmap` ∪
      `excludedBitmap` = all mesh participants, disjoint); a receiver that
      missed earlier packets still validates it.
- [ ] Readiness leases, the commit hold and withdrawals are bounded and
      tested, including every withdrawal-timing case.
- [ ] Readiness reaches the proposer across the mesh topology in use;
      relayed records never outlive their lease.
- [ ] Retired-epoch packets are ignored; a restarted sender's readiness is
      accepted; a restarted receiver passes the warm-up before counting
      readiness.
- [ ] OFFLINE proposer identity and tie-break use the roster-independent
      `proposerTag`.
- [ ] `generationHighWater` is persisted write-ahead; no generation is
      reused for two proposals, including across restarts.
- [ ] A readiness lease is renewed only by a strictly newer sequence;
      relay `age` includes the hop penalty; the transit assumption is
      documented and measured.
- [ ] Protocol M (or an allowlisted probe) is implemented, so recovery
      never depends on an undefined manual trial.
- [ ] Manual exclusion is one confirmation of one explicit list, and the
      bitmap equals that list.
- [ ] Single-flight executor converges to the latest accepted decision;
      handoffs never overlap.
- [ ] ACK arbitration is implemented and tested: one `ackFor` per record,
      only the winning live proposal is acknowledged.
- [ ] Commit validation is observation-independent and identical on every
      phone; a rejected commit leaves a safe decision and converges.
- [ ] The relay model is explicit: no relay edits authenticated bytes;
      re-advertised records change only `age` and `hops`.
- [ ] Protocol M is a supported T1 path with the failed-recovery rate
      measured in the field, and the UI states plainly when the convoy has
      decided ONLINE but this phone has not.
- [ ] Manual exclusion is explicit and affirmative, warns of lost contact,
      and the proposer never excludes a member it holds a live ready record
      for.
- [ ] A rider-driven path (explicit exclusion list or Protocol M) can
      always produce a valid ONLINE commit when the initiator is eligible.
- [ ] LiveKit probing is proven not to capture, publish or subscribe audio
      while mesh owns the HAL, or recovery uses Protocol M (8.9).
- [ ] Local states are never reported from a convoy decision alone;
      degraded states are shown honestly.
- [ ] Mesh capacity counts only mesh participants (≤ 6); above that, group
      and per-device fallback are disabled for the ride with a pre-ride
      warning.
- [ ] Standby has no second audio pipeline; `AudioOwnershipArbiter` and the
      400 ms quiet guard are unchanged.
- [ ] Codecs are versioned and bounds-checked; old-packet compatibility is
      tested.
- [ ] All simulator tests pass with deterministic seeds.
- [ ] CI frozen paths, test census floor, required test matrix and release
      DEX checks pass.
- [ ] Field tests validate battery, switching latency, packet loss,
      partition merge, and rider comprehension.

## T2

- [ ] Automatic exclusion follows 9.2–9.3 with the majority limit.
- [ ] Timers tuned from field data and validated against the Section 15
      constraints.
- [ ] Long-outage (token expiry, exhausted reconnects) recovery verified.

---

# 24. Final position

## 24.1 Architecture

- Decentralized, leaderless, eventually convergent transport decisions.
- Crash-fault trust model; Byzantine defences are T3 and opt-in.
- Canonical membership identity = `rosterHash`; no cross-device revision.
- Durable monotonic generation with a write-ahead high-water mark; no
  guessing after state loss.
- Roster-independent OFFLINE proposer identity (`proposerTag`).
- Freshness renewed only by newer sequence numbers; explicit transit
  assumption; a defined relay and re-advertisement model.
- Deterministic, observation-independent commit validation; a single
  winning ACK per member.
- OFFLINE wins ties and is adopted despite a roster mismatch.
- Single-phase OFFLINE; two-phase ONLINE with exact ACKs, a lease, a hold
  and an explicit commit.
- Readiness gossip with age across hops; sender incarnation via the
  existing `epoch` plus a retired list.
- Always-available manual return online; no way for the convoy to be
  stuck offline.
- One serialized local executor; no second audio pipeline; arbiter
  unchanged.
- Exclusions are explicit, visible, scoped to one ONLINE commit.

## 24.2 Open decisions (for the owner)

1. Whether one rider's 10 s loss should move the whole group to mesh, or
   whether a different quorum/duration is wanted for a single affected
   rider. v7 keeps the existing trigger and discloses the trade-off.
2. Whether a returning rider without internet may *automatically* pull the
   convoy offline. v7 allows it only after confirmation, guards and the
   60 s cooldown. A prompt-only policy is also defensible.
3. Final timer values (grace, prompt, cooldown) after field tests.
4. Whether to accept Protocol M (manual readiness) or allowlist a LiveKit
   probe change (13.3).
5. Whether the threat model ever requires T3.

## 24.3 Known limits (state them plainly)

- **No shared local network, no fallback for that rider.** No switching
  algorithm can reach a phone with no path. Fixing this needs Wi-Fi Direct
  or Wi-Fi Aware, which is a separate project.
- This is eventual convergence, not strict consensus. A valid local route
  proves only that one device has a route.
- A valid ONLINE commit does not prove a member's radio stayed healthy until
  the packet arrived (8.6). Each phone verifies, executes and reports its
  own transport.
- Group fallback is limited to six mesh participants.
- Freshness across hops rests on a stated transit-delay assumption
  (`HOP_PENALTY`), not on a measured age. Phones have no synchronized
  clocks, so the protocol cannot measure age. Field measurements must
  validate the value, and it must never be treated as an unconditional
  guarantee.
- "Never fails" cannot be promised. What is promised is safe degradation:
  rollback on failure, stale decisions ignored, no rider left without voice
  when a working transport path to the convoy exists and local transport
  activation succeeds, and a rider-driven way back online.

**The most important rule:** a convoy decision is not proof that every
phone changed transport. The protocol decides what the convoy should use.
Each phone proves what it actually did, reports the difference honestly,
keeps its last working path where possible, and reconciles when
connectivity returns.

This document is not a claim that the repository already supports these
rules. If Phase 0 shows the repository cannot provide a per-sender epoch,
multi-hop state propagation, or audio-silent LiveKit probing, those
capabilities must be implemented or the affected feature must stay
disabled before release.

## 24.4 The five biggest risks and where they are controlled

| Risk | Controls in this document |
|---|---|
| Audio ownership race (both transports capture or publish) | Single-flight executor (12); arbiter unchanged; audio-silent probe (13.2); invariants 8 and 20 |
| Incorrect convoy convergence | Ordering (5.4); durable generation (5.3, 16); partition merge (10); simulator topologies (20.1); invariants 1 to 4 |
| False readiness | Eligibility and leases (8.1, 8.2); local recheck and withdrawal table (8.6); Protocol M caveat (8.9) |
| Stale or replayed state | Newer-`seq` renewal (8.2); epoch handling and warm-up (11.6); exact ACK binding (8.4); hop penalty (11.3) |
| Repository integration regression | CI constraints (22.1); Phase 0 mismatch report; changes confined to `mesh/`; test census floor; field validation (Phase 7) |

---

# 25. Decision record

These questions are settled. Do not reopen them without new evidence from
a Phase 0 mismatch, field testing, or a threat-model change.

| Decision | Rationale | Reopen only if |
|---|---|---|
| Crash-fault trust model; no signed ACK evidence | The existing key hierarchy cannot prove which member sent a message, and any member can already forge voice packets. Signatures need key distribution through frozen backend paths. | A malicious-member threat is required (T3) |
| Sender incarnation is `MeshPacketV4.epoch`; no handshake | Nonce and key construction strongly suggest a per-sender epoch. A challenge/response over lossy UDP adds a way to get stuck non-ready. | Phase 0 cannot establish the epoch properties (use `senderIncarnation`, still no handshake) |
| 8-byte identifiers | Only accidental collisions matter in the crash-fault model; probabilities are negligible (11.2). | T3 is adopted, or Phase 1 budget allows widening |
| OFFLINE wins ties; single-phase OFFLINE | Safety direction; waiting for every ACK would let one unreachable rider block everyone who can talk. | Field data shows harmful flapping |
| ONLINE is two-phase with exact ACKs, hold and commit | Recovery is the dangerous direction. | Field data shows it cannot complete reliably |
| Existing 10 s total-loss trigger kept | Changing it silently changes user-visible behavior. | Field tests with real riders |
| More than 6 mesh participants: group and per-device fallback disabled | A lone rider on a mesh nobody listens to is silent. | A larger mesh is supported |
| Manual exclusion is one confirmation of one explicit list | Aligns UI and protocol and avoids hidden exclusions. | Product requires per-rider consent |
| Leaderless, no coordinator or consensus service | Avoids a single point of failure and a consensus failure mode. | Never, short of a different product |
| Commit validation uses only the commit, the roster and the config; no receiver-side presence veto | Receiver-dependent observations let the same commit be accepted on one phone and rejected on another. Excluding a ready rider is harmless because it joins LiveKit on its own. | Field data shows harmful exclusions |
| Relays never edit authenticated bytes; second-hand records are re-composed in the re-advertiser's own message | AES-GCM forbids in-flight edits; hop age must be receiver-computed or re-advertiser-authenticated. | Phase 0 shows the forwarding engine cannot support either path |

**Freeze rule.** Phase 0 produces the mismatch report. Resolve each
mismatch by changing the repository plan, not by loosening a rule here,
unless the report shows the rule cannot be implemented. Record every such
change in this section with its evidence.
