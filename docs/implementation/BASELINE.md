# Baseline Implementation & Verification Metrics

- **Date:** 2026-11-01
- **Branch:** `fix/mesh-audit-r2`
- **Baseline Commit (HEAD):** `a37ef09e32f0e0bbead7d77a7922de21c89696cc`
- **Pre-Mesh Parent Commit:** `de53299d6c381f1c29b83c83523628a58578ebcb`
- **Frozen-File Reference Note:** `67257e5` is superseded for frozen-file comparison by `de53299`.

## Verification Results
- **Unit Tests:** 216 tests run, 0 failures, 0 ignored (`testDebugUnitTest` passed).
  - Breakdown: 31 existing mesh tests (7 in MeshStage1SimulationTest, 2 in OfflineMeshGateTest, 8 in Stage2IntegrationTest, 2 in ImportIsolationTest, 6 in Stage3AutomaticSwitchingTest, 6 in Stage5AcceptanceTest), 185 other tests.
- **Debug APK Artifact:** `app-debug.apk`
- **Debug APK Size:** 78,423,532 bytes (~74.8 MiB / 78.4 MB)
- **Assemble Build:** `assembleDebug` passed.
