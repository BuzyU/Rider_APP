# Convoy-Wide Voice Switching (v7) — Phase 0 Repository Verification & Mismatch Report

**Target Spec:** `RiderVoice_Convoy_Wide_Voice_Switching_Engineering_Plan_v7_FINAL.md` (Section 22.2)  
**Baseline HEAD:** `13481e6` (`main`)  
**Scope:** Read-only verification across `mobile-app/`, `backend/`, and `.github/` (zero code changes).

---

## 1. Phase 0 Item Verdicts

### Item 1: `MeshPacketV4.epoch` (Section 11.6) — **MISMATCH**
- **Bit width & generation:** `localEpoch: Int = SecureRandom().nextInt()` is a 32-bit signed `Int` generated in `ForwardingEngine` (`mobile-app/app/src/main/java/com/ridervoice/mesh/forwarding/ForwardingEngine.kt:87`), instantiated fresh inside `OfflineMeshVoiceTransport.startDiscoveryOnly()` (`OfflineMeshVoiceTransport.kt:273-290`). It changes on every transport start, process restart, and device reboot.
- **AES-GCM key & nonce construction:**
  - `sourceKey = HKDF-SHA256(ikm = payloadKey, salt = "rvmesh/v4/src", info = srcShort(4) || epoch(4))` (`PayloadCrypto.kt:55-62`).
  - `nonce = typeCode(1) || seq(3) || 0(4) || epoch(4)` (`PayloadCrypto.kt:82-90`).
  - **Standalone Crypto Finding:** Under a 32-bit random epoch collision ($p \approx n^2 / 2^{33}$, e.g. $4.66 \times 10^{-8}$ at $n=20$ restarts, $1.16 \times 10^{-6}$ at $n=100$), **both** `sourceKey` and `nonce` repeat for the same `(srcShort, typeCode, seq)` — meaning AES-GCM `(key, nonce)` reuse would occur on an epoch collision.
- **Old delayed packet / retired-epoch handling (MISMATCH):**
  - `ForwardingEngine.pinnedEpochs[srcShort]` (`ForwardingEngine.kt:134, 621-633`) stores only **one** `Int` and unconditionally executes `pinnedEpochs[srcShort] = epoch` whenever `type == MeshPacketType.HELLO`. A delayed old-epoch `HELLO` can therefore overwrite the current pinned epoch. There is no 8-entry retired-epoch list.
  - `MeshGraphRepository.isStaleReport()` (`MeshGraphRepository.kt:63-64`) checks sequence ordering only when `prevEpoch == epoch`.
- **Receiver replay state after restart (MISMATCH):**
  - `ReplayWindow` (`ReplayWindow.kt:84-116`) is in-memory only with `maxEntries = 16` LRU keyed by `(srcShort, epoch, type)`. In a 6-device session with `VOICE`, `HELLO`, and `DIRECT_HELLO`, 18 active keys already exceed `maxEntries = 16`. No `EPOCH_WARMUP` exists after receiver restart.
- **Phase 1 Decision:** Per Section 11.6 & 22.2(1), introduce a dedicated 64-bit CSPRNG `senderIncarnation` in readiness records (with 8-entry retired list + `EPOCH_WARMUP = 6s` on receiver restart), **and** enlarge `ReplayWindow` capacity and add retired-epoch tracking in `ForwardingEngine` so old `HELLO`s cannot unpin a newer epoch.

### Item 2: `stableMemberId` Source & Consistency (Section 4.3) — **PARTIAL / MISMATCH**
- **Source:** `GET /api/lobby/:roomRef/status` returns Firebase UID (`User.id`) in `host.id` (`backend/src/routes/lobbyRoutes.js:589-595`) and `invites[].invitee.id` (`lobbyRoutes.js:553-559`), identical across all clients and matching LiveKit `identity` (`backend/src/services/tokenService.js:203`).
- **Mismatches:**
  1. `ActiveRideRepository` (`mobile-app/app/src/main/java/com/ridervoice/state/ActiveRideRepository.kt:30-44`) does not persist the member roster (`host.id`, `invites[]`, or readiness status) across `Phase.LIVE` or process death; `LobbyViewModel.startPolling()` (`LobbyViewModel.kt:160-165`) stops polling upon leaving `LobbyScreen`.
  2. `LobbyStatus.host` (`lobbyRoutes.js:589-595`) does not carry an `offlineConfigStatus` field (`offlineConfigStatus` is only returned on `invites[]`). At ride start, non-host riders infer host mesh participation from `room.offlineMeshEnabled` (host can opt out locally via `RoomMeshConfig`).

### Item 3: Multi-Hop `HELLO`, `ttlHop`, and Shared Hotspot Isolation (Section 11.3) — **PASS (Code) / UNVERIFIED (RF)**
- `ForwardingEngine.processIncomingFrame()` (`ForwardingEngine.kt:758-778`) forwards `HELLO` and `BYE` using `ttlPolicy.canForwardAfterReceive(ttl)` (`ttl > 1`, `HELLO_TTL = 5`, `MAX_HOPS = 5`, `TtlPolicy.kt:30-37`) via `MeshPacketV4.rewriteMutableFieldsForForward()` (`MeshPacketV4.kt:119-126`).
- `ttlHop` (`data[1]`) packs `ttl` in bits `7..4` (`HELLO_TTL = 5`, `DEFAULT_TTL = 5`) and `hop` in bits `3..0` (`0..5`). Relays decrement `ttl`, increment `hop`, and rewrite `prevShort` without touching ciphertext or the 12-byte GCM tag.
- Shared hotspot client-to-client isolation is unverified on hardware (`PHYS-01`/`PHYS-02` are `NOT EXECUTED`).

### Item 4: `HELLO` Size Cap & Packet Type `3` Safety (Section 11.1) — **PASS (Use Type `3`)**
- `MeshConfig.MAX_CONTROL_PAYLOAD_BYTES = 400`, `MAX_PAYLOAD_BYTES = 1_200` (`MeshConfig.kt:35-36`). `MeshPacketV4.decode()` enforces `data.size <= 17 + 1200 + 12 = 1229` (`MeshPacketV4.kt:67`).
- Worst-case existing `HELLO` (`16 + 1 + 96 + 4 + 1 + 5*16 + 10 + 6 = 214 B`) + worst-case 6-participant v7 state (~244 B with 64-bit `senderIncarnation`) exceeds `MAX_CONTROL_PAYLOAD_BYTES = 400`, and `HelloCodec.decode()` rejects unknown trailing TLVs (`else -> return null`, `HelloCodec.kt:106`).
- Packet type `3` in `vtf` returns `null` from `MeshPacketType.fromCodeV4(3)` (`MeshPacket.kt:10-19`) and is safely dropped as `BAD_TYPE` / `DECODE_FAILED` (`MeshPacketV4.kt:77-78`, `ForwardingEngine.kt:479-482`). **Phase 1 uses `CONTROL(STATE)` type `3`.**

### Item 5: `LiveKitManager` Reconnect & Audio-Silent Probing (Section 13) — **MISMATCH**
- `LiveKitManager.connectInternal()` (`LiveKitManager.kt:338-458`) unconditionally starts `audioDeviceRouter.start()`, publishes `LocalAudioTrack`, enables remote tracks, and runs `startVoxSafely()` after 150 ms.
- `LiveKitVoiceTransport` (`LiveKitVoiceTransport.kt:184-192`) relies on `delay(250L); reassertPauseMute()` while `pausedByCoordinator == true`, which is **not** proven audio-silent under Section 13.2.
- `LiveKitManager.scheduleReconnect()` stops after `MAX_RECONNECT_ATTEMPTS = 10` (`LiveKitManager.kt:184, 916`).
- **Resolution:** Add an explicit `silentProbeMode` in `LiveKitManager.kt` (which is already in `.github/frozen-allowlist`) that connects, refreshes token, verifies `expectedLkRoomName`, and stays audio/ADM/router-silent while mesh owns the HAL, while retaining **Protocol M** as fallback if probing is unavailable.

### Item 6: Switching Off Fallback for >6 Mesh Participants (Section 1.4) — **MISMATCH**
- `OfflineMeshGate` (`OfflineMeshGate.kt:53-57`) and `RoomMeshFlagMirror` (`RoomMeshFlagMirror.kt:27-38`) do not check participant count; `SessionManager` (`OfflineSession.kt:137`) only rejects the 7th device on arrival.
- **Resolution:** Enforce `meshParticipants.size in 2..6` (or `<= 6`) in `ConvoyMembershipSnapshot` and disable both convoy-wide and per-device fallback when `> 6` mesh participants, surfacing the pre-ride notice in `LobbyScreen`.

### Item 7: Session Persistence Atomicity & Arbiter / Rollback (Section 16.1) — **PARTIAL / MISMATCH**
- `EncryptedPrefsBackupSessionStore` (`OfflineSessionHolder.kt:98-103`) uses asynchronous `.apply()` and stores only pairing metadata (no `ConvoyMembershipSnapshot`, `acceptedDecision`, or `generationHighWater`).
- `AudioOwnershipArbiter` (`AudioOwnershipArbiter.kt:28-132`) and `VoiceSessionCoordinator` (`VoiceSessionCoordinator.kt:158-279`) enforce strict `Mutex` ownership, 400 ms quiet guard (`verifyLiveKitQuiet`), and `NonCancellable` rollback.
- *(Minor doc drift: `AntiFlappingConfig.maxTransitionsPerWindow` is `3` in `SwitchingModels.kt:127`, matching v7, whereas `docs/implementation/mesh/state-machine.md:78` still states `4`.)*

### Item 8: Measured Transit & Per-Hop Forwarding Delay (Section 11.3) — **NOT MEASURED**
- All physical tests `PHYS-01`..`PHYS-23` in `docs/testing/PHYSICAL_TEST_CHECKLIST.md:16-39` are `NOT EXECUTED`. `HOP_PENALTY = 500 ms` remains unvalidated on real hardware until Phase 7.

---

## 2. Frozen-File & CI Restriction Impact List

- **CI Guard Scripts:** `.github/scripts/mesh-commit-guard.sh`, `.github/scripts/mesh-invariants.sh`, `.github/test-census-floor` (`704`).
- **Already Allowlisted in `.github/frozen-allowlist` (used only where strictly needed):**
  - `mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt` (minimal `silentProbeMode` support)
  - `mobile-app/app/src/main/java/com/ridervoice/state/ActiveRideRepository.kt` (expose/mirror roster snapshot safely)
  - `mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt`
  - `mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/RideTopBar.kt`
  - `mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/RiderList.kt`
- **Strictly Untouched Frozen Paths:** `ConnectionState.kt`, `NetworkResilienceManager.kt`, `VoiceForegroundService.kt`, `audio/`, `diagnostics/`, `RoomScreen.kt`, `backend/`, `web-app/`, `livekit-server/`, `docs/plan/OFFLINE_MESH_PLAN.md`.
- **Primary Implementation Home (Unfrozen):** `mobile-app/app/src/main/java/com/ridervoice/mesh/` and `mobile-app/app/src/test/java/com/ridervoice/mesh/` (plus `LobbyViewModel.kt`, `LobbyScreen.kt`, `strings.xml` for Phase 6 UI & pre-ride gate).
