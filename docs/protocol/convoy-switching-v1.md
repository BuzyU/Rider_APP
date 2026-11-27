# RiderVoice Convoy-Wide Voice Switching — Byte-Level Protocol Appendix (v1)

**Companion Spec:** `RiderVoice_Convoy_Wide_Voice_Switching_Engineering_Plan_v7_FINAL.md` (Phase 1 / Gate B)  
**Wire Transport:** `MeshPacketV4` (`type = CONTROL`, `codeV4 = 3`), encrypted end-to-end with AES-256-GCM (96-bit tag).  
**Byte Order:** Big-endian (network byte order) for all multi-byte integers.

---

## 1. `MeshPacketV4` Envelope for `CONTROL(STATE)`

| Offset | Width | Field | Value / Notes |
|---|---|---|---|
| `0` | 1 B | `vtf` | `version(3b)=4 \| type(2b)=3 (CONTROL) \| fmt(3b)=0` = **`0x98`** |
| `1` | 1 B | `ttlHop` | `ttl(4b) \| hopCount(4b)` — **MUTABLE on relay** (excluded from AAD). Initial `ttl = 5`, `hopCount = 0`. |
| `2` | 4 B | `srcShort` | Upper 32 bits of origin `deviceId` UUID (`msb >>> 32`). |
| `6` | 4 B | `prevShort` | Upper 32 bits of transmitting hop `deviceId` UUID — **MUTABLE on relay**. |
| `10` | 4 B | `epoch` | 32-bit random transport epoch for `MeshPacketV4` key/nonce isolation. |
| `14` | 3 B | `seq` | 24-bit unsigned sequence counter (`mod 2^24`). |
| `17` | `N` B | `ciphertext` | Encrypted `ConvoyStatePayload` (`1 <= N <= 400`). |
| `17+N` | 12 B | `tag` | AES-256-GCM 96-bit authentication tag. |

- **Per-Source Subkey:** `HKDF-SHA256(ikm = payloadKey, salt = "rvmesh/v4/src", info = srcShort(4) || epoch(4))`
- **Deterministic Nonce (12 B):** `typeCode(1)=0x03 || seq(3) || 0x00000000(4) || epoch(4)`
- **AAD (12 B):** `vtf(1) || srcShort(4) || epoch(4) || seq(3)` (immutable fields only).

---

## 2. Canonical Identifiers & Domain-Separated SHA-256

All member identifiers (`stableMemberId`, i.e., Firebase UID) are normalized with Unicode **NFC** (`Normalizer.Form.NFC`) and encoded as UTF-8 before hashing or sorting. Unsigned lexicographic comparison of UTF-8 NFC byte arrays defines canonical member ordering.

### 2.1 `rosterHash` (32 B full, 8 B wire prefix)

```text
SHA-256(
  0x01                                  // 1 B canonical roster format version
  || sessionId (16 B raw UUID)          // MSB (8 B BE) || LSB (8 B BE)
  || memberCount (1 B uint8)            // 1..N (duplicate IDs rejected)
  || for each member in canonical UTF-8 NFC byte order:
       len(memberIdBytes) (2 B uint16 BE)
       || memberIdBytes (UTF-8 NFC)
       || roleByte (1 B: 0x01 = MESH_PARTICIPANT, 0x02 = NON_MESH_PARTICIPANT)
)
```

`rosterHash8` = bytes `0..7` of the 32-byte digest.  
`meshParticipants` = ordered list of members with `role == MESH_PARTICIPANT` (`0..5`, max 6). Bit `i` in `readyBitmap` / `excludedBitmap` (`1 shl i`) indexes `meshParticipants[i]`.

### 2.2 `proposerTag` (8 B)

```text
SHA-256(
  "rvtag/v1" (8 B ASCII)
  || sessionId (16 B raw UUID)
  || stableMemberIdBytes (UTF-8 NFC)
)[0..7]
```

Roster-independent so two phones with mismatched snapshots identify an `OFFLINE` proposer identically.

### 2.3 `decisionId` (8 B) & `proposalId` Recomputation

| Decision Kind | Exact SHA-256 Preimage (Big-Endian) | Truncation |
|---|---|---|
| `OFFLINE` (`ADOPTED`) | `"rvdec/v1/offline"` (16 B) \|\| `sessionId` (16 B) \|\| `generation` (4 B) \|\| `0x01` (mode) \|\| `0x00` (phase) \|\| `proposerTag` (8 B) | `[0..7]` |
| `ONLINE_PROPOSED` | `"rvdec/v1/online_proposed"` (24 B) \|\| `sessionId` (16 B) \|\| `generation` (4 B) \|\| `0x00` (mode) \|\| `0x01` (phase) \|\| `proposerTag` (8 B) \|\| `rosterHash8` (8 B) \|\| `baseOfflineId` (8 B) \|\| `excludedBitmap` (1 B) \|\| `manualExclusion` (1 B: `0x00`/`0x01`) | `[0..7]` |
| `ONLINE_COMMITTED` | `"rvdec/v1/online_committed"` (25 B) \|\| `sessionId` (16 B) \|\| `generation` (4 B) \|\| `0x00` (mode) \|\| `0x02` (phase) \|\| `proposerTag` (8 B) \|\| `rosterHash8` (8 B) \|\| `baseOfflineId` (8 B) \|\| `excludedBitmap` (1 B) \|\| `manualExclusion` (1 B) \|\| `readyBitmap` (1 B) \|\| `ackSeqCount` (1 B) \|\| `ackSeqs` (`ackSeqCount * 2 B` uint16 BE) | `[0..7]` |

- **`proposalId` from commit:** Any receiver recomputes the `proposalId` bound to an `ONLINE_COMMITTED` decision by hashing the `ONLINE_PROPOSED` preimage using the commit's `generation`, `proposerTag`, `rosterHash8`, `baseOfflineId`, `excludedBitmap`, and `manualExclusion`.
- **`ackSeqs` ordering:** Length equals `meshParticipants.size` (`<= 6`). Entry `i` (`0..meshParticipants.size - 1`) is the 16-bit `seq` of the ACK readiness record held from `meshParticipants[i]` at the commit decision point (`0` when `i` is in `excludedBitmap`).

---

## 3. `ConvoyStatePayload` Binary Wire Format (`extVersion = 1`, `configVersion = 1`)

### 3.1 Payload Header (4 B, mandatory)

| Offset | Width | Field | Constraints |
|---|---|---|---|
| `0` | 1 B | `extVersion` | `0x01` (v1). Unknown versions rejected cleanly without crash. |
| `1` | 1 B | `configVersion` | `0x01` (covers Section 15 timer/parameter set). |
| `2` | 1 B | `recordCount` | `0..6`. Values `> 6` rejected as malformed. |
| `3` | 1 B | `payloadFlags` | bit 0: `hasDecision`; bit 1: `isSuspectBurst`; bit 2: `isProposalAbort`; bits 3..7: reserved (`0`). |

### 3.2 Optional Decision Block (present iff `hasDecision == 1`)

| Width | Field | Notes |
|---|---|---|
| 4 B | `generation` | Unsigned 32-bit monotonic generation (`1 .. 0xFFFFFFF0`). |
| 1 B | `modePhaseFlags` | bit 0: `mode` (`0=ONLINE`, `1=OFFLINE`); bits 1..2: `phase` (`0=ADOPTED`, `1=ONLINE_PROPOSED`, `2=ONLINE_COMMITTED`); bit 3: `manualExclusion`; bits 4..7: `0`. |
| 8 B | `proposerTag` | Origin proposer tag. |
| 8 B | `decisionId` | Canonical 8-byte decision ID. |
| *If `mode == ONLINE`:* | | |
| 8 B | `rosterHash8` | Truncated canonical roster hash. |
| 8 B | `baseOfflineId` | `decisionId` of the `OFFLINE` decision being recovered from. |
| 1 B | `excludedBitmap` | Bitmask indexed by `meshParticipants` (`bits >= meshParticipants.size` must be `0`). |
| *If `mode == ONLINE && phase == ONLINE_COMMITTED`:* | | |
| 1 B | `readyBitmap` | Disjoint from `excludedBitmap`; `readyBitmap \| excludedBitmap == (1 shl N) - 1`. |
| 1 B | `ackSeqCount` | `1..6` (must match `meshParticipants.size` on validation). |
| `2 * ackSeqCount` B | `ackSeqs` | Big-endian uint16 array in canonical `meshParticipants` order. |

- `OFFLINE` decision block size: **21 B** (`4 + 1 + 8 + 8`).
- `ONLINE_PROPOSED` decision block size: **38 B** (`21 + 8 + 8 + 1`).
- `ONLINE_COMMITTED` (6 participants) decision block size: **52 B** (`38 + 1 + 1 + 12`).

### 3.3 Readiness Records (`recordCount` × 27 B each)

Per Phase 0 Item 1 (`CONVOY_SWITCHING_PHASE0_MISMATCH_REPORT.md`), readiness records carry a dedicated 64-bit CSPRNG `senderIncarnation` rather than relying on 32-bit `MeshPacketV4.epoch`.

| Offset | Width | Field | Notes |
|---|---|---|---|
| `0` | 1 B | `rosterIdx` | `0..5` index into `meshParticipants`. |
| `1` | 8 B | `senderIncarnation` | 64-bit random incarnation generated on process start. |
| `9` | 2 B | `seq` | Unsigned 16-bit sequence counter; increments on every emission within `senderIncarnation`. |
| `11` | 1 B | `flags` | bit 0: `livekitReady`; bit 1: `recoveryEligible`; bit 2: `meshReady`; bit 3: `netLost`; bits 4..5: `localMode` (`0=ONLINE_ACTIVE`, `1=OFFLINE_ACTIVE`, `2=UNAVAILABLE_OR_FAILED`, `3=TRANSITIONING`); bit 6: `readinessSource` (`0=AUTO`, `1=MANUAL`); bit 7: `0`. |
| `12` | 4 B | `baseGen` | Unsigned 32-bit `OFFLINE` generation adopted by origin (`0` if none). |
| `16` | 8 B | `ackFor` | `proposalId` acknowledged (`0x00..00` if none). |
| `24` | 2 B | `ageTenthsSec` | Unsigned 16-bit (`ageMs / 100`, capped at `65535`; `0` in origin record). |
| `26` | 1 B | `hops` | `0` in origin record; `1..5` in re-advertised second-hand records. |

---

## 4. Worst-Case Datagram Budget (6 Mesh Participants)

| Component | Bytes |
|---|---|
| `MeshPacketV4` header | 17 B |
| `ConvoyStatePayload` header | 4 B |
| `ONLINE_COMMITTED` decision (6 participants) | 52 B |
| 6 × 27 B readiness records | 162 B |
| **Max `ConvoyStatePayload` plaintext** | **218 B** (`<= 400 B` `MAX_CONTROL_PAYLOAD_BYTES`, 182 B headroom) |
| AES-256-GCM tag | 12 B |
| **Total UDP payload on wire** | **247 B** (`<= 1,472 B` Ethernet/Wi-Fi MTU, zero fragmentation) |

---

## 5. Compatibility, Malformed Bounds, and Relay Rules

1. **Old Clients:** `MeshPacketType.fromCodeV4(3)` returned `null` in prior builds, causing `MeshPacketV4.decode()` to return `BAD_TYPE` and `ForwardingEngine` to drop cleanly with zero crashes. `HELLO` wire format (`HelloCodec`) is untouched.
2. **Version Mismatch:**
   - Unknown `extVersion` → ignore payload, increment malformed/unsupported diagnostic, do not mutate state.
   - Different `configVersion` (on known `extVersion`) → **still adopt valid `OFFLINE` decisions** (safe direction); **never** propose, ACK, or execute `ONLINE` decisions.
3. **Malformed Bounds:** Reject any payload shorter than 4 B, truncated mid-decision/record, trailing garbage bytes (`remaining != 0`), `recordCount > 6`, invalid `mode`/`phase` (`phase == 3`, `mode == OFFLINE && phase != 0`), `rosterIdx >= 6`, `ackSeqCount !in 1..6`, or out-of-range bitmap bits.
4. **Two-Path Relay Behavior:**
   - **Path 1 (Forwarded Original `CONTROL`):** `ForwardingEngine` decrements `ttl`, increments `hopCount`, sets `prevShort = selfShort`, and preserves ciphertext/tag untouched. Receiver computes transit age as `hopCount * HOP_PENALTY` (`500 ms`).
   - **Path 2 (Re-advertisement):** Origin records (`hops = 0, age = 0`) held by a node may be embedded in that node's own authenticated `CONTROL` message with `hops += 1` and `ageTenthsSec += ceil((heldMs + HOP_PENALTY) / 100)`. Records with `hops > 5` or `ageMs >= 6_000 ms` are dropped and never re-advertised.

---

## 6. Published Reference Test Vectors (Normative)

All vectors below use `sessionId = 11223344-5566-4777-8899-aabbccddeeff`.

- **Input Roster (unsorted):** `("rider-uid-charlie", MESH_PARTICIPANT)`, `("rider-uid-alpha", MESH_PARTICIPANT)`, `("rider-uid-bravo", NON_MESH_PARTICIPANT)`
- **Canonical Sorted Order:** `[rider-uid-alpha (MESH), rider-uid-bravo (NON_MESH), rider-uid-charlie (MESH)]`
- **`meshParticipants` (index 0, 1):** `["rider-uid-alpha", "rider-uid-charlie"]`
- **`rosterHash` (32 B):** `bc828197b52e28bfb7a0ae2f41da7208eee20220c9551889b0b70a4c0538311d`
- **`rosterHash8` (8 B):** `bc828197b52e28bf`
- **`proposerTag("rider-uid-alpha")`:** `fc6357361240605c`
- **`proposerTag("rider-uid-charlie")`:** `6111afa96c165867`
- **`OFFLINE` decision (`gen = 1`, `proposer = alpha`):** `decisionId = c9a3dde6bbd38ddf`
- **`ONLINE_PROPOSED` decision (`gen = 2`, `proposer = charlie`, `baseOfflineId = c9a3dde6bbd38ddf`, `excludedBitmap = 0x00`, `manualExclusion = false`):** `decisionId = dd6cc707da420d5d`
- **`ONLINE_COMMITTED` decision (`gen = 2`, `proposer = charlie`, `readyBitmap = 0x03`, `excludedBitmap = 0x00`, `manualExclusion = false`, `ackSeqs = [105, 210]`):**
  - `decisionId = ff8081a78e3f2b45`
  - Recomputed `proposalId` from commit fields = `dd6cc707da420d5d`
  - Bitmap check: `(0x03 & 0x00) == 0` (disjoint) and `(0x03 | 0x00) == 0x03` (`(1 shl 2) - 1`, complete).
