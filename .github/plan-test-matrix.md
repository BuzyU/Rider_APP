# RiderVoice Offline Mesh — Plan Requirements to Test Mapping Matrix

This table maps the explicit test verification requirements from `docs/plan/OFFLINE_MESH_PLAN.md` (Sections 1.11, 2.6, and 5) directly to the test methods in the test suite that prove each behavior.

| Plan Section | Requirement Description | Proving Test (Class#Method) |
|---|---|---|
| **1.11** | Device identity generation and persistence | `ProductionWiringTest#meshIdentityRepository_generatesAndPersistsDeterministicId` |
| **1.11** | Discovery encoding/decoding and spec vectors | `ProtocolSpecVectorTest#helloCodecSpecVectors` |
| **1.11** | Malformed packet rejection & signature validation | `ProtocolHardeningTest#legacyNonce_rejectedByDefault` |
| **1.11** | Graph merge and direct neighbor discovery | `HonestStateTest#directNeighborLearnedFromHello` |
| **1.11** | Neighbor timeout and stale edge eviction | `HonestStateTest#neighborTimeoutExpiresStaleEdges` |
| **1.11** | Route calculation and tie-breaking | `ScheduledDeliveryScenarioTest#routeCalculatorDeterministicTieBreak` |
| **1.11** | Direct route endpoint filtering | `DirectRouteEndpointTest#indirectRoute_neverUsesIndirectEndpoint` |
| **1.11** | TTL bounds (default 5, max hops 5) | `ProtocolConstantsDocSyncTest#ttlConstants_are5` |
| **1.11** | Duplicate packet cache suppression | `ForwardingHonestyTest#cliqueControlledFlooding_honestyBound` |
| **1.11** | Jitter buffer sequence wrap-around ordering | `AudioHandoffRobustnessTest#jitterWrapAround` |
| **1.11** | 6-node linear chain full topology learning | `ScheduledDeliveryScenarioTest#sixChain_everyNodeLearnsFullTopology` |
| **1.11** | Joint admission cap (rejection beyond 6 devices) | `MeshSubsystemInvariantsTest#sessionManager_canAdmitAll_rejectsWhenAdmittingSourceAndPreviousHopExceedsCap` |
| **2.6** | Online mode isolation (zero mesh sockets / collectors) | `FlagOffIsolationTest#flagOff_onlineAppGraphHasZeroMeshInteractions` |
| **2.6** | Offline mode runs independently without LiveKit | `Stage2IntegrationTest#offlineMode_runsWithoutLiveKit` |
| **2.6** | Exactly one audio capture owner enforced | `AudioOwnershipArbiterTest#claimOfflineMesh_whenHeldByLiveKit_stopsLiveKitFirst` |
| **2.6** | Audio route controller preserves Bluetooth SCO | `MeshSubsystemInvariantsTest#meshAudioRouteController_doesNotReleaseBluetoothScoWhenLiveKitOwnsAudio` |
| **2.6** | Headset MediaSession deactivation safety | `MeshSubsystemInvariantsTest#meshVoxBridge_doesNotDeactivateMediaSessionWhenRoomOwnsAudio` |
| **2.6** | Monotonic 20ms playback pacing (0 false underruns) | `MeshSubsystemInvariantsTest#offlineAudioPlayback_monotonicTickPacing_doesNotCountIdleSilenceAsUnderrun` |
| **2.6** | Online RoomScreen decoupled from mesh DI | `OnlineRoomDiIsolationTest#gateClosed_composingRoomScreenDoesNotInstantiateMeshViewModel` |
| **5.0** | Daisy-hop multi-hop playout and forwarding chain | `Stage5AcceptanceTest#daisyHop_fullPlayoutAndForwardingChain` |
| **5.0** | Daisy-hop source-ID preservation across relays | `Stage5AcceptanceTest#daisyHop_sourceIdPreserved` |
| **5.0** | Relay disappearance and dynamic route recovery | `Stage5AcceptanceTest#relayLoss_recovers` |
| **5.0** | Automatic fallback on validated internet loss | `Stage3AutomaticSwitchingTest#fallbackToMesh_triggersOnValidatedInternetLoss` |
| **5.0** | Temporary network glitch does not trigger fallback | `Stage3AutomaticSwitchingTest#temporaryGlitch_doesNotTriggerFallback` |
| **5.0** | Automatic fallback requires valid mesh path | `Stage3AutomaticSwitchingTest#fallback_requiresValidMeshRoute` |
| **5.0** | Non-disruptive recovery requires LiveKit stability | `RecoveryPlanComplianceTest#recovery_dwellRestarts_ifLiveKitFlaps` |
| **5.0** | Cancellation-safe audio rollback on aborted switch | `CancellationSafeHandoffTest#fallbackToMesh_cancelledDuringHandoff_rollsBackToLiveKit` |
