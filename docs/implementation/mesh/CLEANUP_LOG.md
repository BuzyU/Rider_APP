# RiderVoice Offline Mesh - Cleanup Log (WP-41)

Date: 4 Nov 2026
Author: Implementing Engineer
Reference Rule: R9 (Deletion Rule)

---

## 1. Deleted Files and Symbols (Grep Proofs)

### 1.1 Dead Code: `PartialMeshPeer`
- **Location prior to deletion**: `mobile-app/app/src/main/java/com/ridervoice/mesh/switching/SwitchingModels.kt`
- **Reason**: Unreferenced artifact from early partial mesh designs. Replaced by `PeerMeshHealth` and `MeshPeerHealthState`.
- **Grep verification (zero active references in code, tests, gradle, CI, docs)**:
```
$ git grep "PartialMeshPeer"
docs/implementation/mesh/DEFECT_REGISTER.md:| **A32** | LOW | FIXED | `SwitchingModels.kt:40`, `MeshConfig.kt:6` | Dead code `PartialMeshPeer` and `AUDIO_PORT` redundant alias. |
```
*(Only historical mentions in DEFECT_REGISTER.md and this log).*

---

### 1.2 Redundant Constant Alias: `MeshConfig.AUDIO_PORT`
- **Location prior to deletion**: `mobile-app/app/src/main/java/com/ridervoice/mesh/MeshConfig.kt`
- **Reason**: Owner Decision Q2 consolidated all mesh operations on port `45454` (`MESH_PORT`). `AUDIO_PORT` was retained as an alias. All usages in source and tests were migrated directly to `MESH_PORT` and the alias was deleted.
- **Grep verification**:
```
$ git grep "AUDIO_PORT" mobile-app/
(0 hits)
```

---

### 1.3 Stale Audit Document: `docs/implementation/mesh/AUDIT_FACTS.md`
- **Reason**: Tier 1 audit output containing stale claims superseded by WP-34 through WP-40.
- **Git deletion**: `git rm docs/implementation/mesh/AUDIT_FACTS.md`
- **Grep verification**:
```
$ git grep "AUDIT_FACTS.md"
(0 active references outside DEFECT_REGISTER.md and this log)
```

---

### 1.4 Stale Binary File: `RiderVoice Offline Mesh v2.0 - Independent Audit, Risk Register & Fix Prompt.docx`
- **Reason**: Outdated binary Word document at repository root.
- **Git deletion**: `git rm "RiderVoice Offline Mesh v2.0 - Independent Audit, Risk Register & Fix Prompt.docx"`
- **Grep verification**: Zero active references across entire repository.

---

### 1.5 Stale Root Document: `Rider_APP Offline Mesh — Plan Conformance Audit.md`
- **Reason**: Tier 1 audit document superseded by `POST_REMEDIATION_PLAN_CONFORMANCE_REPORT.md` and subsequent WP implementations.
- **Git deletion**: `git rm "Rider_APP Offline Mesh — Plan Conformance Audit.md"`
- **Grep verification**: Zero references across entire repository.

---

## 2. Archived Reports

The following legacy WP reports were moved to `docs/implementation/mesh/archive/` and prepended with a `> SUPERSEDED` banner per WP-41 instructions:
- `WP-25a.md`
- `WP-25b-RED.txt`
- `WP-25c.md`
- `WP-26.md`
- `WP-27.md`
- `WP-28.md`
- `WP-29.md`
- `WP-30.md`
- `WP-31.md`
- `WP-32.md`

Reconstructed report `docs/implementation/mesh/WP_REPORTS/WP-33.md` was created from commit `afaf414`.

---

## 3. Reviewed and Retained Items

| Item | Location | Status | Justification |
| :--- | :--- | :--- | :--- |
| **Stage 4 Continuity Stubs** | `com.ridervoice.mesh.continuity.*` (`ContinuityConfig.kt`, `ContinuityLeaseClient.kt`, `ContinuityModels.kt`) | **RETAINED (Labeled)** | Owner Decision Q9 / Rule R9(b). Labeled with KDoc: `/** NOT IMPLEMENTED - client stubs only, no backend, unreferenced in production (Stage 4 / Q9). */`. Decoupled from DI. |
| **`LocalHealthSignalsProvider.startDriving`** | `mobile-app/app/src/main/java/com/ridervoice/mesh/switching/LocalHealthSignalsProvider.kt` | **RETAINED** | Retained as a test harness driving seam for isolated component testing without requiring full `MeshAutoSwitchManager` lifecycle orchestration. Explicitly used in `WiringTest.kt`. Documented in KDoc. |
| **`MeshAutoSwitchManager` Constructor Parameters** | `mobile-app/app/src/main/java/com/ridervoice/mesh/switching/MeshAutoSwitchManager.kt` | **RETAINED** | All 8 parameters (`activeRideRepository`, `liveKitManager`, `networkObserver`, `coordinator`, `meshTransport`, `identityRepo`, `healthSignalsProvider`, `secretProvider`) are required by DI (`MeshModule.kt`), unit tests (`MeshAutoSwitchManagerTest`, `WiringTest`), and runtime coordination logic. Constrained by `ProductionWiringTest`. |
| **`FeatureFlags.localKillSwitch`** | `mobile-app/app/src/main/java/com/ridervoice/mesh/FeatureFlags.kt` | **RETAINED (Doc Patched)** | Kill switch is functional and fail-closed. KDoc was corrected to remove misleading "fail-open" statement (A1). |
