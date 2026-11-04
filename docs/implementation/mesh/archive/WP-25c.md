> SUPERSEDED: contains claims later disproved; see DEFECT_REGISTER.md

# Work Package 25c Verification Report

## 1. Summary
- **Task:** Turn `ProductionWiringTest` green, remove room-derived offline secrets, clean `MeshModule` `@Provides` signatures, implement `AudioManagerPort` / `HardwarePttPort` abstractions, wire Wi-Fi socket binding, implement gate-checked auto-switching startup and identity provider.
- **Result:** `ProductionWiringTest` 7/7 PASSED, `SessionKdfTest` 7/7 PASSED, `assembleDebug` BUILD SUCCESSFUL, `lintDebug` BUILD SUCCESSFUL, `assembleRelease` BUILD SUCCESSFUL.
- **Frozen File Invariant:** Exactly 0 lines changed across all frozen paths against `a37ef09`.

---

## 2. Production Call Chains

### 2.1 Audio Route Controller
- `App.onCreate` (mobile-app/app/src/main/java/com/ridervoice/App.kt:49)
  -> `MeshModule.provideMeshAudioRouteController` (mobile-app/app/src/main/java/com/ridervoice/di/MeshModule.kt:101)
  -> `MeshModule.provideOfflineMeshVoiceTransport` (MeshModule.kt:120)
  -> `OfflineMeshVoiceTransport` constructor receives non-null `MeshAudioRouteController`
  -> `OfflineMeshVoiceTransport.startAudio()` (OfflineMeshVoiceTransport.kt:132) calls `routeController?.acquireCommunicationMode()`
  -> `MeshAudioRouteController.acquireCommunicationMode()` checks `arbiter.owner.value == AudioOwner.OFFLINE_MESH` and configures `AudioManagerPort`
  -> `OfflineMeshVoiceTransport.stopAudio()` calls `routeController?.releaseCommunicationMode()` restoring prior mode and speakerphone state.

### 2.2 Wi-Fi Socket Binding
- `MeshModule.provideOfflineMeshVoiceTransport` (MeshModule.kt:135)
  -> passes `linkFactory = { UdpMeshLink(networkBinder = { socket -> locks.bindSocketToWifi(socket) }) }`
  -> `UdpMeshLink.start()` (UdpMeshLink.kt:64) calls `networkBinder(this)` on newly created `DatagramSocket`
  -> `AndroidNetworkLocks.bindSocketToWifi` binds socket to Wi-Fi transport, or logs and continues unbound if hotspot host.

### 2.3 Hardware PTT Port
- `MeshModule.provideHardwarePttPort` (MeshModule.kt:96)
  -> `AndroidHardwarePttPort(hardwarePTTManager)` wraps `HardwarePTTManager.onMicToggleRequest`
  -> `MeshModule.provideOfflineMeshVoiceTransport` injects `hardwarePttPort` into `OfflineMeshVoiceTransport`
  -> `MeshVoxBridge` attaches to `hardwarePttPort.onMicToggleRequest`, flips `isMicToggledOn`, preserves previous callback, and restores on `close()`.

### 2.4 Offline Mesh Gate & Auto-Switch Startup
- `App.onCreate` (App.kt:51)
  -> calls `OfflineMeshGate.install(PrefsMeshRuntimeSettings(this))`
  -> checks `if (OfflineMeshGate.decision() == GateDecision.ENABLED)`
  -> calls `meshAutoSwitchManager.start()`
  -> `MeshAutoSwitchManager.start()` (MeshAutoSwitchManager.kt:93) verifies `OfflineMeshGate.decision() == GateDecision.ENABLED` at top before launching coroutine.

### 2.5 Rider Name Provider & Identity
- `MeshModule.provideRiderNameProvider` (MeshModule.kt:71)
  -> reads from `FirebaseAuth` or secure user preferences (fallback `"Rider"`, never `Build.MODEL`)
  -> `MeshModule.provideMeshIdentityRepository` injects `RiderNameProvider` into `MeshIdentityRepository`
  -> `MeshIdentityRepository.get()` queries `riderNameProvider.currentName()` at session start
  -> `MeshAutoSwitchManager` does not overwrite identity with `ride.displayName`.

### 2.6 Offline Secret & Availability (Q1 Resolution)
- `MeshModule.provideOfflineSecretProvider` (MeshModule.kt:110)
  -> binds `NullOfflineSecretProvider`
  -> injected into `VoiceSessionCoordinator` and `MeshAutoSwitchManager`
  -> `OfflineSession.fromOnlineRoom` and `BOOTSTRAP_KEY` deleted
  -> `VoiceSessionCoordinator.offlineAvailability` emits `OfflineAvailability.NO_SHARED_SECRET`
  -> fallback to mesh is prevented when no shared secret is provisioned.

---

## 3. Raw Evidence

### 3.1 `ProductionWiringTest` Output
```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 14s
37 actionable tasks: 2 executed, 35 up-to-date
```
JUnit XML (`mobile-app/app/build/test-results/testDebugUnitTest/TEST-com.ridervoice.mesh.ProductionWiringTest.xml`):
```xml
<testsuite name="com.ridervoice.mesh.ProductionWiringTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-11-02T14:46:50" hostname="DESKTOP-DDOAUF4" time="0.036">
  <properties/>
  <testcase name="udpMeshLink_networkBinder_invokesBindSocketToWifi" classname="com.ridervoice.mesh.ProductionWiringTest" time="0.02"/>
  <testcase name="meshAudioRouteController_isProvidedAndInjectedIntoTransport" classname="com.ridervoice.mesh.ProductionWiringTest" time="0.007"/>
  <testcase name="meshIdentity_displayNameComesFromRiderNotBuildModelNorConvoyName" classname="com.ridervoice.mesh.ProductionWiringTest" time="0.001"/>
  <testcase name="autoSwitchManager_start_doesNothingWhenGateClosed" classname="com.ridervoice.mesh.ProductionWiringTest" time="0.002"/>
  <testcase name="nullSecretProvider_yieldsNoSharedSecret_andFallbackNeverStarts" classname="com.ridervoice.mesh.ProductionWiringTest" time="0.0"/>
  <testcase name="meshVoxBridge_receivesHardwarePttPort" classname="com.ridervoice.mesh.ProductionWiringTest" time="0.0"/>
  <testcase name="meshModule_providesHaveNoNullableOrDefaultedParameters" classname="com.ridervoice.mesh.ProductionWiringTest" time="0.003"/>
</testsuite>
```

### 3.2 `SessionKdfTest` Output
```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 14s
37 actionable tasks: 2 executed, 35 up-to-date
```
JUnit XML (`mobile-app/app/build/test-results/testDebugUnitTest/TEST-com.ridervoice.mesh.audit.SessionKdfTest.xml`):
```xml
<testsuite name="com.ridervoice.mesh.audit.SessionKdfTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-11-02T14:47:21" hostname="DESKTOP-DDOAUF4" time="0.081">
  <testcase name="oldOracleIsGone_sessionIdIsNotMd5OfCode" classname="com.ridervoice.mesh.audit.SessionKdfTest" time="0.047"/>
  <testcase name="sameCodeProducesIdenticalSessionIdAndKeys" classname="com.ridervoice.mesh.audit.SessionKdfTest" time="0.009"/>
  <testcase name="sessionToStringNeverLeaksCodeOrKeyBytes" classname="com.ridervoice.mesh.audit.SessionKdfTest" time="0.013"/>
  <testcase name="wrongSessionKeyRejectsPacketEvenIfSessionIdMatches" classname="com.ridervoice.mesh.audit.SessionKdfTest" time="0.007"/>
  <testcase name="normalizationHandlesCaseDashesSpacesAndConfusableCharacters" classname="com.ridervoice.mesh.audit.SessionKdfTest" time="0.0"/>
  <testcase name="oneSymbolDifferenceProducesCompletelyDifferentSession" classname="com.ridervoice.mesh.audit.SessionKdfTest" time="0.002"/>
  <testcase name="nullOfflineSecretProviderReturnsNullAndOnlineRoomDerivationIsAbsent" classname="com.ridervoice.mesh.audit.SessionKdfTest" time="0.0"/>
</testsuite>
```

### 3.3 `assembleDebug` Output
```
BUILD SUCCESSFUL in 44s
45 actionable tasks: 4 executed, 41 up-to-date
```

### 3.4 `lintDebug` Output
```
> Task :app:lintReportDebug
Wrote HTML report to file:///D:/Projects/Rider_APP-main/mobile-app/app/build/reports/lint-results-debug.html

> Task :app:lintDebug

BUILD SUCCESSFUL in 1m 16s
31 actionable tasks: 5 executed, 26 up-to-date
```

### 3.5 `assembleRelease` Output
```
BUILD SUCCESSFUL in 4m 20s
58 actionable tasks: 25 executed, 33 up-to-date
```

### 3.6 Frozen Files Check
```
git diff --stat a37ef09 -- mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt mobile-app/app/src/main/java/com/ridervoice/network/ConnectionState.kt mobile-app/app/src/main/java/com/ridervoice/network/NetworkResilienceManager.kt mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt mobile-app/app/src/main/java/com/ridervoice/state/ActiveRideRepository.kt mobile-app/app/src/main/java/com/ridervoice/state/RoomFault.kt mobile-app/app/src/main/java/com/ridervoice/state/AppEvents.kt mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt mobile-app/app/src/main/java/com/ridervoice/audio/ mobile-app/app/src/main/java/com/ridervoice/diagnostics/ mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt mobile-app/app/src/main/java/com/ridervoice/ui/screens/room/ backend/ web-app/ livekit-server/
Output: 0 files changed
```
