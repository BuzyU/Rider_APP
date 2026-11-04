# RiderVoice Offline Mesh — Defect Register (WP-34 through WP-42)

Columns: `ID | Severity | Status | File:Line | Quoted Snippet / Description`

| ID | Severity | Status | File:Line | Quoted Snippet / Description |
|---|---|---|---|---|
| **A1** | CRITICAL | OPEN | `MeshConfig.kt:24` vs `docs/protocol/mesh-v2.md:81` | Code is `const val DEFAULT_TTL = 5` but docs claim `Default = 7 (TtlPolicy.DEFAULT_TTL)`. |
| **A2** | CRITICAL | OPEN | `MeshAutoSwitchManager.kt:135`, `MeshModule.kt:156` | Stage 3 unreachable: `val companionSession: OfflineSession? = null`, bound `NullOfflineSecretProvider`. |
| **A3** | CRITICAL | FIXED | `LocalHealthSignalsProvider.kt:78`, `ValidatedInternetSource.kt` | WP-35 / `ValidatedInternetAndStandingRulesTest` (`wifiConnectedButNotValidated_isNotValidated`) | Added ValidatedInternetSource checking NET_CAPABILITY_VALIDATED; removed blind isConnected fallback |
| **A4** | HIGH | FIXED | `OfflineRoomScreen.kt`, `MicController.kt` | WP-36 / `MicAudioControlPathTest` | UI routes via MicController; PTT release never closes toggle mic. |
| **A5** | HIGH | FIXED | `OfflineMeshVoiceTransport.kt`, `MicRules.kt` | WP-36 / `MicAudioControlPathTest` | AudioRecord capture runs whenever audioEnabled && (micToggleOn || pttHeld || voxEnabled). |
| **A6** | HIGH | FIXED | `MeshVoxBridge.kt` | WP-36 / `WP34RedTests`, `MicAudioControlPathTest` | Removed listener registration in init; attach/detach lifecycle with reference identity check. |
| **A7** | HIGH | FIXED | `LiveKitVoiceTransport.kt` | WP-36 / `MicAudioControlPathTest` | isVoxRunning tracks active connection state and VOX stopped state faithfully. |
| **A8** | HIGH | OPEN | `VoiceSessionCoordinator.kt:247` | `mesh.disableAudio()` called before waiting up to 15s for LiveKit CONNECTED; creates up to 15s dead air during recovery. |
| **A9** | HIGH | FIXED | `VoiceSessionCoordinator.kt:175` | WP-36 / `VoiceSessionCoordinatorTest`, `AudioHandoffRobustnessTest` | Removed liveKitTransport.stop() from fallbackToMesh(); LiveKit remains paused. |
| **A10** | HIGH | OPEN | `NavGraph.kt:371-373` | `OfflineSession.fromCode(sessionCode, ...) ?: OfflineSession.create(...)` derives on UI thread and creates fallback session on invalid code. |
| **A11** | HIGH | OPEN | `NavGraph.kt:420` | `DisposableEffect(session.sessionId) { onDispose { viewModel.stopSession() } }` terminates offline session on screen rotation. |
| **A12** | MEDIUM | OPEN | `App.kt:52` | `if (OfflineMeshGate.decision() == GateDecision.ENABLED) meshAutoSwitchManager.start()` — enabling at runtime does nothing if disabled at startup. |
| **A13** | MEDIUM | OPEN | `MeshModule.kt:146` | `identity = identityRepo.get()` evaluated once at DI composition time instead of per-session start. |
| **A14** | MEDIUM | OPEN | `UdpMeshLink.kt:130` | `endpoints()` returns empty set because `registerEndpoint()` has no production caller. |
| **A15** | MEDIUM | OPEN | `MeshForegroundService.kt:103`, `AutomaticSwitchStateMachine.kt:187` | `catch (_: Throwable)` swallows CancellationException and logs nothing; CoroutineExceptionHandlers silent. |
| **A16** | MEDIUM | OPEN | `OfflineSessionHolder.kt:9`, `OfflineSession.kt:15` | Plain object not Hilt singleton, no session zeroing or `.close()`; PBKDF2 `out` buffer not zeroed. |
| **A17** | MEDIUM | OPEN | `MeshGraphRepository.kt:85` | `val isStale = sequence != 0 && r.sequence != 0 && (sequence - r.sequence) < 0` ignores epoch; restarted peer ignored until timeout. |
| **A18** | LOW | OPEN | `ForwardingEngine.kt:348` | `val fallbackNonce = PayloadCrypto.nonce(decoded.epoch, decoded.sequence, decoded.type.code)` accepted without source binding. |
| **A19** | LOW | OPEN | `AndroidManifest.xml:6, 9` | `CHANGE_WIFI_STATE` and `NEARBY_WIFI_DEVICES` declared without recorded architectural decision. |
| **A20** | LOW | OPEN | `MeshDebugReceiver.kt:24`, `debug/AndroidManifest.xml:4` | Exported receiver without permission guard or peer filter. |
| **A21** | LOW | OPEN | `docs/implementation/mesh/AUDIT_FACTS.md`, root audit docs | Stale documents and overclaiming documentation. |
| **A22** | LOW | CLOSED | `LocalHealthSignalsProvider.kt:45` | WP-35 / DECISIONS.md | Design limit: `LiveKitManager.reconnectAttempts` is private (NO PUBLIC SOURCE); lambda left at 0; no loss/RTT sampling added per instruction |
| **A23** | LOW | OPEN | `MeshForegroundService.kt:57`, `Routes.kt:27` | Unused `sessionCode` overloads in service and routes. |
| **A24** | LOW | OPEN | `SwitchingModels.kt:72-73` | `if (meshCount > 0 \|\| offlineOrGhostCount > 0) GroupConnectivityStatus.MIXED` treats left LiveKit participant as MIXED. |
| **A25** | HIGH | FIXED | `AutomaticSwitchStateMachine.kt:95-102` | WP-36 / `WP34RedTests` | offlineAvailable check restricted to entry states (ONLINE, ONLINE_SUSPECT, FALLBACK_UNAVAILABLE). |
| **A26** | CRITICAL | FIXED | `AutomaticSwitchStateMachine.kt:115, 136` | WP-35 / `ValidatedInternetAndStandingRulesTest` (`partialRecovery_resetsSuspectTimer`, `internetBackLiveKitReconnecting_staysOnline`) | Suspect timer resets whenever !isTotalLoss(); FALLBACK_UNAVAILABLE requires isTotalLoss() |
| **A27** | HIGH | FIXED | `AutomaticSwitchStateMachine.kt` | WP-36 / `WP34RedTests` | Non-blocking handoff with generation ticket per CODE-2; endSession returns promptly. |
| **A28** | HIGH | FIXED | `LiveKitVoiceTransport.kt` | WP-36 / `MicAudioControlPathTest` | restartVoxIfPreviouslyRunning() only starts VOX when connectionState == CONNECTED. |
| **A29** | MEDIUM | OPEN | `MeshDebugReceiver.kt:33` | `Log.i(TAG, "... ActiveSession=${session?.pairingCode?.take(4)}****")` leaks secret pairing code prefix into logcat. |
| **A30** | MEDIUM | OPEN | `MeshForegroundService.kt:96-103` | No-mic path on API>=29 calls `startForeground` with no type, throwing SecurityException against manifest microphone type. |
| **A31** | MEDIUM | OPEN | `ContinuityLeaseClient.kt:35, 46` | `catch (t: Throwable)` and `catch (_: Throwable)` swallow `CancellationException`. |
| **A32** | LOW | OPEN | `SwitchingModels.kt:40`, `MeshConfig.kt:6` | Dead code `PartialMeshPeer` and `AUDIO_PORT` redundant alias. |
| **A33** | MEDIUM | OPEN | `VoiceSessionCoordinator.kt:55-61` | `computeAvailability()` not reactive to secretProvider flow or backup session expiry. |
| **A34** | LOW | OPEN | `values/strings.xml`, `values-hi`, `values-mr` | `mesh_invalid_code` and `mesh_use_as_ride_backup` string resources missing. |
| **A35** | MEDIUM | OPEN | `NavGraph.kt:418`, `OfflineMeshVoiceTransport.kt:119` | Activity rotation re-triggers LaunchedEffect; `startSession`/`transport.start` must be idempotent for same sessionId. |
| **A36** | LOW | OPEN | `.github/workflows/build.yml` | CI test-census floor set to outdated 290 and lacks mesh invariant checks. |
| **A37** | LOW | OPEN | `app/build.gradle:14` vs `README.md:1` | Version drift `V0.0.3.19` vs `v0.0.3.20`. |
| **A38** | LOW | OPEN | `MeshConfig.kt:26` | `HELLO_TTL = 4 < DEFAULT_TTL = 5` topology learning in multi-node chains. |
| **A39** | HIGH | FIXED | `OfflineMeshVoiceTransport.kt:460` | WP-36 / `WP34RedTests` | stop() resets micController, micWanted, and paused flags. |
| **A40** | HIGH | FIXED | `OfflineMeshVoiceTransport.kt:294` | WP-36 / `WP34RedTests` | failEnable() cleanly rolls back playback, locks, and ownership. |
| **A41** | MEDIUM | FIXED | `OfflineMeshVoiceTransport.kt:154` | WP-36 / `WP34RedTests` | Replaces different session ID; updates projected peers synchronously. |
| **A42** | HIGH | FIXED | `OfflineAudioCapture.kt:175` | WP-36 / `WP34RedTests` | Preserves frame offset on read == 0; MAX_EMPTY_READS = 50 breaks cleanly. |
| **A43** | MEDIUM | OPEN | `OfflineMeshLobbyScreen.kt:233`, `NavGraph.kt:371` | Pairing code passed via UI callback `onEnterSession(session.pairingCode)`. |
| **A44** | INFO | OPEN | `ProductionWiringTest.kt`, `ImportIsolationTest.kt` | Guard tests constrain refactoring signatures and imports. |
| **A45** | MEDIUM | OPEN | `App.kt:51-52` | Ordering dependency: `OfflineMeshGate.install` must precede `meshAutoSwitchManager.start()`. |
| **A46** | LOW | FIXED | `OfflineMeshVoiceTransport.kt`, `OfflineRoomScreen.kt` | WP-36 / `MicAudioControlPathTest` | Transport exposes StateFlow<Boolean> deafened; default voxEnabled is false. |
| **A47** | LOW | FIXED | `MicController.kt` | WP-36 / `MicAudioControlPathTest` | Channel(Channel.CONFLATED) in MicController preserves order and collapses rapid states. |
