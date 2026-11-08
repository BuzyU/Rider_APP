# RiderVoice Mesh Baseline (S-2)

## Baseline Verification
- **Baseline Commit:** `a37ef09`
- **Baseline Test Census:**
  - Unit Tests: **216** `@Test` methods passed (`0` failures, `0` ignored) in 4.93s (`testDebugUnitTest`).
  - Instrumented Tests: **0** (all mesh instrumented tests in `androidTest` were introduced in later mesh work packages).
- **Current Test Census (HEAD):**
  - Unit Tests: **550** `@Test` methods passed (`0` failures, `0` ignored).
  - Instrumented Tests: **10** (all marked `NOT RUN` in CI per environment constraint).

## Three-Level Rollback Plan

### Level 1: Runtime User Setting Off
- **Mechanism:** User toggle `FeatureFlags.OFFLINE_MESH_ENABLED = false` in Settings.
- **Effect:** All mesh sockets, background services, discovery broadcasts, audio capture/playback, and switching state machines remain completely idle and uninvoked. App operates purely as an online LiveKit client.

### Level 2: Compile-Time Build Gate Off
- **Mechanism:** Build gate `BuildConfig.OFFLINE_MESH_BUILD_ENABLED = false` (default for release builds via `mobile-app/app/build.gradle`).
- **Effect:** Mesh dependency graphs and service launchers are disabled at compile-time. Release builds cannot instantiate or execute mesh components.

### Level 3: Complete Source Revert
- **Mechanism:** Git revert of all mesh commits from baseline `a37ef09` to HEAD:
  ```bash
  git revert --no-commit a37ef09..HEAD
  ```
- **Effect:** Restores every source file, manifest, test, and document back to baseline `a37ef09`.
- **Dry-Run Drill Verification:**
  - **Drill Date:** 2026-11-08
  - **Procedure:** Revert command executed on branch `temp-rollback-drill`.
  - **Result:** Revert succeeded cleanly with zero merge conflicts.
  - **Build Verification:** `ALLOW_INSECURE_RELEASE_BUILD=true ./gradlew assembleRelease` completed with `BUILD SUCCESSFUL in 4m 22s` (58 actionable tasks: 37 executed, 21 up-to-date).
  - **Status:** Dry-run verified; physical device flashing drill marked `NOT EXECUTED` (owner-gated).

