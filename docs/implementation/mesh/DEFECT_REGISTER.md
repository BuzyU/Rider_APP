# RiderVoice Offline Mesh — Defect Register

Columns: `ID | Description | File:Line | Severity | Status | Fixing WP | Evidence Snippet`

| ID | Description | File:Line | Severity | Status | Fixing WP | Evidence Snippet |
|---|---|---|---|---|---|---|
| **N1** | State machine lifecycle does not reset between rides | `mesh/switching/AutomaticSwitchStateMachine.kt:20`, `MeshAutoSwitchManager.kt:98` | HIGH | OPEN | WP-27 | `stateMachine.endSession()` does not clear suspect/recovery timers; no `beginSession()` |
| **N2** | Online ride secret derived from room name | `mesh/session/OfflineSession.kt:81-105` | CRITICAL | OPEN | WP-25c / WP-26 | `mac.init(SecretKeySpec(BOOTSTRAP_KEY, "HmacSHA256"))` on `canonicalRoomKey` |
| **N3** | Probes run continuously and key derivation repeated per tick | `mesh/switching/LocalHealthSignalsProvider.kt:65`, `MeshAutoSwitchManager.kt:116` | MEDIUM | OPEN | WP-27 | `OfflineSession.fromOnlineRoom` called inside evaluation loop on every tick |
| **N4** | Baseline compilation failure at `OfflineRoomScreen.kt:151` | `mesh/ui/OfflineRoomScreen.kt:151` | CRITICAL | FIXED | WP-25a | `voxBridge?.setPttHeld(held)` -> Unresolved reference: `setPttHeld` |
| **N5** | LiveKit handoff does not restore VOX state on recovery | `mesh/transport/LiveKitVoiceTransport.kt:105-125` | HIGH | OPEN | WP-28 | `stopVox()` invoked without recording running state; `resume()` does not restart VOX |
| **N6** | VOX bridge lifecycle terminal close and mic combination semantics | `mesh/audio/MeshVoxBridge.kt:90-115` | HIGH | OPEN | WP-28 | `close()` cancels supervisor scope permanently; PTT release forcibly closes mic |
| **N7** | Rider display name set to convoy name instead of rider's name | `mesh/switching/MeshAutoSwitchManager.kt:103` | MEDIUM | OPEN | WP-25c / WP-27 | `identityRepo.setDisplayName(ride.displayName)` uses convoy room name |
| **N8** | Session teardown in `onCleared()` runs in cancelled `viewModelScope` | `mesh/ui/OfflineMeshViewModel.kt:38` | MEDIUM | OPEN | WP-26 | `viewModelScope.launch` in `onCleared()` gets cancelled immediately, dropping BYE |
| **N9** | Secret pairing code leaked in route arguments, notifications, and intent extras | `navigation/Routes.kt:28`, `MeshForegroundService.kt:323` | HIGH | OPEN | WP-26 | `Routes.OFFLINE_MESH_HUD = "offline_mesh_hud/{sessionCode}"`; `EXTRA_SESSION_CODE` |
| **N10** | Foreground service startup crashes and broad `catch(Throwable)` | `services/MeshForegroundService.kt:84-109` | HIGH | OPEN | WP-26 | Missing `RECORD_AUDIO` check before `startForeground` with mic type on API 34 |
| **N11** | No single-capture guard verifying LiveKit is quiet before mesh starts | `mesh/transport/OfflineMeshVoiceTransport.kt:85`, `OfflineAudioCapture.kt:60` | HIGH | OPEN | WP-28 | Offline capture opens `AudioRecord` without confirming LiveKit capture has released |
| **N12** | Repeated PBKDF2 120,000-iteration key derivation on evaluation loop | `mesh/switching/MeshAutoSwitchManager.kt:79,117` | HIGH | OPEN | WP-26 / WP-27 | 120,000 iterations computed per second during active online ride |
| **N13** | `debugPeerFilter` not guarded by debug check | `mesh/MeshConfig.kt:46`, `mesh/forwarding/ForwardingEngine.kt:256` | MEDIUM | OPEN | WP-29 | `debugPeerFilter` active without `BuildConfig.DEBUG` check |
| **V1** | Mic state not carried across fallback (starts closed) | `mesh/transport/OfflineMeshVoiceTransport.kt:180` | HIGH | OPEN | WP-27 / WP-28 | `start()` called while `micEnabled == false`, returning `GateClosed` |
| **V2** | `RoutingTable` is not thread-safe under concurrent recomputes and reads | `mesh/routing/RouteCalculator.kt:24` | MEDIUM | OPEN | WP-29 | Mutable map mutated during compute while read from packet forwarding thread |
| **V3** | `MeshAudioRouteController` never provided or injected in production | `di/MeshModule.kt:85`, `OfflineMeshVoiceTransport.kt:69` | HIGH | OPEN | WP-25c / WP-28 | `routeController` defaults to null in `OfflineMeshVoiceTransport` constructor |
| **V4** | Missing `@Provides` dependencies and nullable parameters in `MeshModule.kt` | `di/MeshModule.kt:45-95` | MEDIUM | OPEN | WP-25c | Unqualified nullable parameters allow incomplete DI graphs to compile |
| **V5** | Automatic switching evaluates during `Phase.LOBBY` and `Phase.ENDING` | `mesh/switching/MeshAutoSwitchManager.kt:96` | MEDIUM | OPEN | WP-27 | Does not filter `ActiveRideRepository.Phase.LIVE` strictly |
| **V6** | `GroupConnectivityStatus` miscalculates mixed offline peer states | `mesh/switching/MeshAutoSwitchManager.kt:68` | LOW | OPEN | WP-27 | Does not map partial offline peers or LiveKit ghost participants correctly |
| **V7** | `LiveKitVoiceTransport` attaches collectors when offline mesh is disabled | `mesh/transport/LiveKitVoiceTransport.kt:87-155` | MEDIUM | OPEN | WP-25c / WP-27 | Coroutine collectors launched in `init` without checking `OfflineMeshGate` |
| **V8** | Wi-Fi lock holds low-latency mode during discovery; 500ms polling loop | `mesh/link/NetworkLocks.kt:35`, `OfflineMeshVoiceTransport.kt:200` | MEDIUM | OPEN | WP-28 / WP-29 | Discovery acquires `WIFI_MODE_FULL_LOW_LATENCY`; transport polls graph repository |
| **V9** | Helmet PTT (`HardwarePTTManager`) toggle event not handled properly | `mesh/audio/MeshVoxBridge.kt:31` | MEDIUM | OPEN | WP-25c / WP-28 | `HardwarePTTManager` media key is a toggle event, not press-and-hold |
| **V10** | Wire format nonce truncates source ID to 24 bits with legacy fallback | `mesh/protocol/PayloadCrypto.kt:45` | LOW | WONTFIX | WP-31 | Mandated owner decision Q5: retain current wire format, document in threat model |
| **V11** | `UdpMeshLink.endpoints()` returns empty list permanently | `mesh/link/UdpMeshLink.kt:42` | LOW | OPEN | WP-29 | `endpoints()` method returns `emptyList()` instead of active neighbors |
| **V12** | Stage 4 continuity client stubs are dead code | `mesh/continuity/ContinuityLeaseClient.kt:20` | LOW | WONTFIX | WP-31 | Mandated owner decision Q9: keep client stubs, document as unreferenced/unimplemented |
| **V13** | UDP socket not bound to Wi-Fi network before bind | `di/MeshModule.kt:62`, `mesh/link/NetworkLocks.kt:25` | HIGH | OPEN | WP-25c / WP-29 | `networkBinder` defaults to empty lambda in production module |
| **V14** | Missing `CoroutineExceptionHandler` on long-lived mesh coroutine scopes | `mesh/forwarding/ForwardingEngine.kt:45`, `OfflineMeshVoiceTransport.kt:55` | MEDIUM | OPEN | WP-27 / WP-29 | Uncaught exception kills forwarder and transport loops silently |
| **V15** | `enableAudio()` throws exception instead of returning result; silent catch in loops | `mesh/transport/OfflineMeshVoiceTransport.kt:175` | MEDIUM | OPEN | WP-27 / WP-28 | Exceptions thrown across coordinator boundaries; audio loops swallow Throwable |
| **V16** | `JitterBuffer` comments claim adaptive buffering but target is fixed 3 frames | `mesh/audio/JitterBuffer.kt:15` | LOW | OPEN | WP-28 | Fixed 3-frame target claimed as adaptive in doc comments |
| **W-DEGRADED** | Fallback triggered on high loss/RTT alone without total confirmed internet loss | `mesh/switching/LocalHealthSignalsProvider.kt:80` | HIGH | OPEN | WP-27 | Mandated owner rule: weak/degraded internet must never trigger offline mesh |
| **T-FAIL-1** | Baseline test failure: `fallbackToMeshRollsBackToLiveKitWhenMeshAudioFails` | `VoiceSessionCoordinatorTest.kt:134` | HIGH | OPEN | WP-25c / WP-27 | Coordinator rollback does not restart LiveKit when stopped by hardware-busy path |
| **T-FAIL-2** | Baseline test failure: `fallbackToMeshSucceedsWhenRouteExistsAndAudioEnables` | `VoiceSessionCoordinatorTest.kt:163` | HIGH | OPEN | WP-25c / WP-27 | Coordinator calls `pause()` but test fake asserts `stoppedCount == 1` |
| **T-FAIL-3** | Baseline test failure: `recoveryToLiveKitRollsBackToMeshWhenLiveKitFailsToConnect` | `VoiceSessionCoordinatorTest.kt:206` | HIGH | OPEN | WP-25c / WP-27 | Test fake `state` remains `CONNECTED` across pause, bypassing failure injection |

