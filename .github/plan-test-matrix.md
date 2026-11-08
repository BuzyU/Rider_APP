# RiderVoice Offline Mesh — Plan Requirements to Test Mapping Matrix

This table maps the explicit test verification requirements from `docs/plan/OFFLINE_MESH_PLAN.md` (Sections 1.11, 2.6, and 5) directly to the test methods in the test suite that prove each behavior.

| Plan Section | Requirement Description | Proving Test (Class#Method) |
|---|---|---|
| **1.11** | Device identity generation and persistence | `ProductionWiringTest#meshIdentity_displayNameComesFromRiderNotBuildModelNorConvoyName`, `MeshStage1SimulationTest#identity persists across restarts and survives display name changes` |
| **1.11** | Discovery encoding/decoding and spec vectors | `ProtocolSpecVectorTest#protocolConstantsMatchSpec`, `MeshStage1SimulationTest#hello codec and packet codec round-trip and reject invalid or tampered frames` |
| **1.11** | Malformed packet rejection & signature validation | `ProtocolHardeningTest#legacyNonce_rejectedByDefault` |
| **1.11** | Graph merge and direct neighbor discovery | `MeshStage1SimulationTest#graph updates endpoint on IP change without duplicating vertex and recalculates BFS routes`, `AdversarialTest#relayedHelloArrivingFirstMustNotHideTheDirectNeighbor` |
| **1.11** | Neighbor timeout and stale edge eviction | `Stage3ReachabilityTest#endpointExpires`, `ScheduledDeliveryScenarioTest#scenario4_lateGraphUpdateDoesNotResurrectDeadEdge` |
| **1.11** | Route calculation and tie-breaking | `ScheduledDeliveryScenarioTest#routeCalculatorDeterministicTieBreak` |
| **1.11** | Direct route endpoint filtering | `DirectRouteEndpointTest#edgeIncidentToSelf_withNullEndpoint_neverConsideredDirectRoute` |
| **1.11** | TTL bounds (default 5, max hops 5) | `ProtocolConstantsDocSyncTest#ttlConstants_are5` |
| **1.11** | Duplicate packet cache suppression | `ForwardingHonestyTest#sixFullyConnectedNodes_singleVoiceFrame_boundedDatagrams` |
| **1.11** | Jitter buffer sequence wrap-around ordering | `AudioHandoffRobustnessTest#jitterWrapAround` |
| **1.11** | 6-node linear chain full topology learning | `ScheduledDeliveryScenarioTest#sixChain_everyNodeLearnsFullTopology` |
| **1.11** | Joint admission cap (rejection beyond 6 devices) | `MeshSubsystemInvariantsTest#sessionManager_canAdmitAll_rejectsWhenAdmittingSourceAndPreviousHopExceedsCap` |
| **2.6** | Online mode isolation (zero mesh sockets / collectors) | `FlagOffIsolationTest#meshAutoSwitchLauncher_neverCallsGet_whenBuildGateClosed`, `FlagOffIsolationTest#localHealthSignalsProvider_doesNotCollectInInit_untilStartWatchingCalled` |
| **2.6** | Offline mode runs independently without LiveKit | `Stage2IntegrationTest#voice session coordinator manages atomic handoff between transports`, `Stage3ReachabilityTest#noSocketsWhileGateClosed` |
| **2.6** | Exactly one audio capture owner enforced | `AudioOwnershipArbiterTest#claimLiveKit_whenStopOfflineCallbackFails_returnsFalseAndPreservesOwner`, `AudioOwnershipArbiterTest#mutualExclusionBasicTransitions` |
| **2.6** | Audio route controller preserves Bluetooth SCO | `MeshSubsystemInvariantsTest#meshAudioRouteController_doesNotReleaseBluetoothScoWhenLiveKitOwnsAudio` |
| **2.6** | Headset MediaSession deactivation safety | `MeshSubsystemInvariantsTest#meshVoxBridge_doesNotDeactivateMediaSessionWhenRoomOwnsAudio` |
| **2.6** | Monotonic 20ms playback pacing (0 false underruns) | `MeshSubsystemInvariantsTest#offlineAudioPlayback_monotonicTickPacing_doesNotCountIdleSilenceAsUnderrun` |
| **2.6** | Online RoomScreen decoupled from mesh DI | `OnlineRoomDiIsolationTest#selectRoomHost_returnsOfflineAware_onlyWhenGateEnabled`, `OnlineRoomDiIsolationTest#selectRoomHost_returnsPlainOnline_whenDisabledByBuild` |
| **5.0** | Daisy-hop multi-hop playout and forwarding chain | `Stage5AcceptanceTest#daisyHopAcceptance_multiNodeAudioFlowAndSourceIntegrity` |
| **5.0** | Daisy-hop source-ID preservation across relays | `Stage5AcceptanceTest#daisyHopAcceptance_multiNodeAudioFlowAndSourceIntegrity` |
| **5.0** | Relay disappearance and dynamic route recovery | `MeshStage1SimulationTest#daisy-hop 4 nodes adapts dynamically from A-B-C-D to A-C-B-D without restart` |
| **5.0** | Automatic fallback on validated internet loss | `Stage3AutomaticSwitchingTest#confirmed online failure falls back to mesh if route exists` |
| **5.0** | Temporary network glitch does not trigger fallback | `Stage3AutomaticSwitchingTest#short network interruption does not trigger fallback` |
| **5.0** | Automatic fallback requires valid mesh path | `Stage3AutomaticSwitchingTest#confirmed failure transitions to FALLBACK_UNAVAILABLE when no mesh route exists` |
| **5.0** | Non-disruptive recovery requires LiveKit stability | `Stage3AutomaticSwitchingTest#recovery requires 8-second stability window and respects 20-second dwell time`, `RecoveryPlanComplianceTest#deadMesh_onlineNotRecovered_staysPutInOfflineActive` |
| **5.0** | Cancellation-safe audio rollback on aborted switch | `CancellationSafeHandoffTest#fallbackToMesh_cancelledMidHandoff_rollsBackToLiveKitCleanly`, `CancellationSafeHandoffTest#recoverToLiveKit_cancelledMidHandoff_rollsBackToMeshCleanly` |
