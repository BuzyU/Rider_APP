# RiderVoice Offline Mesh — Defect Register (WP-34 through WP-42)

Columns: `ID | Severity | Status | File:Line | Quoted Snippet / Description`

| ID | Severity | Status | File:Line | Quoted Snippet / Description |
|---|---|---|---|---|
| **A1** | CRITICAL | OPEN | `MeshConfig.kt:24` vs `docs/protocol/mesh-v2.md:81` | Code is `const val DEFAULT_TTL = 5` but docs claim `Default = 7 (TtlPolicy.DEFAULT_TTL)`. |
| **A2** | CRITICAL | OPEN | `MeshAutoSwitchManager.kt:135`, `MeshModule.kt:156` | Stage 3 unreachable: `val companionSession: OfflineSession? = null`, bound `NullOfflineSecretProvider`. |
| **A3** | CRITICAL | OPEN | `LocalHealthSignalsProvider.kt:78-79` | `val validatedInternet = isInternetValidated?.invoke() ?: (networkObserver?.isConnected() ?: false)` — fallback checks only NET_CAPABILITY_INTERNET, blind to dead hotspots. |
| **A4** | HIGH | OPEN | `OfflineRoomScreen.kt:126`, `MeshVoxBridge.kt:80` | `transport.setMicrophoneEnabled(next)` bypasses MeshVoxBridge; PTT release closes toggle mic. |
| **A5** | HIGH | OPEN | `OfflineMeshVoiceTransport.kt:297` | `if (micWanted) { aCapture.start() }` — AudioRecord capture never starts when micWanted=false; hands-free VOX cannot hear speech to trigger. |
| **A6** | HIGH | OPEN | `MeshVoxBridge.kt:67, 180` | `attachListeners()` in init clobbers RoomViewModel's `onMicToggleRequest`; `close()` sets it to null and cancels scope permanently. |
| **A7** | HIGH | OPEN | `LiveKitVoiceTransport.kt:29`, `MeshModule.kt:135` | `override val isVoxRunning: Boolean get() = voxRunningCheck()` defaults to `{ false }`; VOX state never restored after pause. |
| **A8** | HIGH | OPEN | `VoiceSessionCoordinator.kt:247` | `mesh.disableAudio()` called before waiting up to 15s for LiveKit CONNECTED; creates up to 15s dead air during recovery. |
| **A9** | HIGH | OPEN | `VoiceSessionCoordinator.kt:175` | `liveKitTransport.stop()` called on hardware-busy branch in `fallbackToMesh()`, disconnecting LiveKit. |
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
| **A22** | LOW | OPEN | `LocalHealthSignalsProvider.kt:45` | `liveKitReconnectAttempts: () -> Int = { 0 }` (NO PUBLIC SOURCE on LiveKitManager). |
| **A23** | LOW | OPEN | `MeshForegroundService.kt:57`, `Routes.kt:27` | Unused `sessionCode` overloads in service and routes. |
| **A24** | LOW | OPEN | `SwitchingModels.kt:72-73` | `if (meshCount > 0 \|\| offlineOrGhostCount > 0) GroupConnectivityStatus.MIXED` treats left LiveKit participant as MIXED. |
| **A25** | HIGH | OPEN | `AutomaticSwitchStateMachine.kt:95-102` | `if (!offlineAvailable) ... return` returns before state switch; in OFFLINE_ACTIVE never hands back if backup secret expires. |
| **A26** | CRITICAL | OPEN | `AutomaticSwitchStateMachine.kt:115, 136` | `isOnlineHealthy()` required to leave SUSPECT (falls back if internet returns but LiveKit reconnecting); FALLBACK_UNAVAILABLE lacks isTotalLoss check. |
| **A27** | HIGH | OPEN | `AutomaticSwitchStateMachine.kt:88, 163, 171` | `mutex.withLock` held across handoff; `> 15_000L` branches unreachable; `endSession()` blocks behind handoff. |
| **A28** | HIGH | OPEN | `LiveKitManagerPort.kt`, `MeshModule.kt:135` | `voxRestarter = { voxEngine.start() }` restarts VOX unconditionally while LiveKit is disconnected. |
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
| **A39** | HIGH | OPEN | `OfflineMeshVoiceTransport.kt:397-398` | `micWanted` and `paused` never reset in `stop()` — causes open-mic privacy leak in subsequent session. |
| **A40** | HIGH | OPEN | `OfflineMeshVoiceTransport.kt:307-319` | `enableAudio()` does not roll back playback, locks, or ownership on partial capture failure; retry falsely returns Success. |
| **A41** | MEDIUM | OPEN | `OfflineMeshVoiceTransport.kt:119` | `startDiscoveryOnly` returns early on new session if already CONNECTED or STARTING. |
| **A42** | HIGH | OPEN | `OfflineAudioCapture.kt:172-173` | Partial-frame drop on `read == 0` (`delay(10L); break` restarts outer loop with offset 0). |
| **A43** | MEDIUM | OPEN | `OfflineMeshLobbyScreen.kt:233`, `NavGraph.kt:371` | Pairing code passed via UI callback `onEnterSession(session.pairingCode)`. |
| **A44** | INFO | OPEN | `ProductionWiringTest.kt`, `ImportIsolationTest.kt` | Guard tests constrain refactoring signatures and imports. |
| **A45** | MEDIUM | OPEN | `App.kt:51-52` | Ordering dependency: `OfflineMeshGate.install` must precede `meshAutoSwitchManager.start()`. |
| **A46** | LOW | OPEN | `OfflineRoomScreen.kt:96` | `isDeafened` stored in UI `remember` mutable state instead of transport StateFlow. |
| **A47** | LOW | OPEN | `MeshVoxBridge.kt:102, 112` | Unordered `scope.launch { mutex.withLock }` creates PTT press/release race condition. |
