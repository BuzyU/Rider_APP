# RiderVoice Offline Mesh — Defect Register

Format: `ID | Sev | Class | Status | Code evidence (file:line) | Test reference`

| ID | Sev | Class | Status | Code evidence (file:line) | Test reference |
|---|---|---|---|---|---|
| A1 | CRITICAL | Protocol | FIXED | `MeshConfig.kt:24` vs `docs/protocol/mesh-v2.md:81` | NetworkProtocolHardeningTest#ttlHopSumAbove_isDropped |
| A2 | CRITICAL | Switching | FIXED | `MeshAutoSwitchManager.kt:135` | Stage3ReachabilityTest#backupSessionReactiveAvailabilityAndExpiryTimer |
| A3 | CRITICAL | Connectivity | FIXED | `LocalHealthSignalsProvider.kt:78` | ValidatedInternetAndStandingRulesTest#wifiConnectedButNotValidated_isNotValidated |
| A4 | HIGH | Audio | FIXED | `OfflineRoomScreen.kt:151` | MicAudioControlPathTest#pttRelease_keepsToggleMic |
| A5 | HIGH | Audio | FIXED | `OfflineMeshVoiceTransport.kt:320` | MicAudioControlPathTest#truthTable32_exhaustive |
| A6 | HIGH | Audio | FIXED | `MeshVoxBridge.kt:85` | MicAudioControlPathTest#attach_detach_restoresRoomCallback |
| A7 | HIGH | Audio | FIXED | `LiveKitVoiceTransport.kt:120` | MicAudioControlPathTest#pause_remembersVoxWhenConnected |
| A8 | HIGH | Audio | FIXED | `VoiceSessionCoordinator.kt:195` | HandoffCorrectnessTest#recovery_noDeadAir |
| A9 | HIGH | Audio | FIXED | `VoiceSessionCoordinator.kt:175` | HandoffCorrectnessTest#hardwareBusy_neverDisconnects |
| A10 | HIGH | Session | FIXED | `NavGraph.kt:371` | SessionLifecycleTest#toString_hasNoSecret |
| A11 | HIGH | Session | FIXED | `NavGraph.kt:420` | SessionHygieneTest#offlineSessionHolder_retainsAndClearsSession |
| A12 | MEDIUM | Gate | FIXED | `App.kt:52` | OfflineMeshGateTest#installedSettingsControlGateWhenNoParameterPassed |
| A13 | MEDIUM | Identity | FIXED | `MeshModule.kt:146` | CompositionRootTest#noProductionFileOtherThanMeshModuleCallsSingleSourceConstructors |
| A14 | MEDIUM | Link | FIXED | `UdpMeshLink.kt:130` | Stage3ReachabilityTest#authenticatedDirect_registers |
| A15 | MEDIUM | Coroutines | REOPENED | `MeshForegroundService.kt:103` | None (pending WP-49) |
| A16 | MEDIUM | Session | PARTIAL | `OfflineSessionHolder.kt:9` | SessionLifecycleTest#close_zeroizesKeys |
| A17 | MEDIUM | Graph | FIXED | `MeshGraphRepository.kt:85` | ProtocolHardeningTest#restartedPeer_epochChange_acceptsNewReport |
| A18 | LOW | Security | FIXED | `ForwardingEngine.kt:348` | PayloadCryptoTest#aadTamperFailsDecryption |
| A19 | LOW | Permissions | FIXED | `AndroidManifest.xml:6` | ProductionWiringTest#autoSwitchManager_start_doesNothingWhenGateClosed |
| A20 | LOW | Security | FIXED | `MeshDebugReceiver.kt:24` | WiringTest#t10_flagOffOpensNoSocketAndPortStaysBindable |
| A21 | LOW | Docs | PARTIAL | `docs/implementation/mesh/` | None (pending WP-51) |
| A22 | LOW | Switching | REOPENED | `LocalHealthSignalsProvider.kt:45` | None (pending WP-46) |
| A23 | LOW | Navigation | FIXED | `MeshForegroundService.kt:57` | SessionHygieneTest#routes_offlineMeshHudRouteContainsNoArguments |
| A24 | LOW | Switching | FIXED | `SwitchingModels.kt:72` | Stage3ReachabilityTest#ghostLiveKit_treatedAsLeft_notMixed |
| A25 | HIGH | Switching | FIXED | `AutomaticSwitchStateMachine.kt:95` | StateMachineLifecycleTest#noSecret_neverLeavesOnline |
| A26 | CRITICAL | Switching | FIXED | `AutomaticSwitchStateMachine.kt:115` | ValidatedInternetAndStandingRulesTest#degradedOnly_neverTotalLoss |
| A27 | HIGH | Switching | FIXED | `AutomaticSwitchStateMachine.kt:140` | HandoffCorrectnessTest#endSession_duringHandoff_returnsPromptly |
| A28 | HIGH | Audio | FIXED | `LiveKitVoiceTransport.kt:135` | MicAudioControlPathTest#resume_whileDisconnected_doesNotStartVox |
| A29 | MEDIUM | Security | FIXED | `MeshDebugReceiver.kt:33` | SessionHygieneTest#offlineSession_toStringNeverLeaksCodeOrKeyBytes |
| A30 | MEDIUM | Service | FIXED | `MeshForegroundService.kt:96` | ProductionWiringTest#meshAudioRouteController_isProvidedAndInjectedIntoTransport |
| A31 | MEDIUM | Continuity | FIXED | `ContinuityLeaseClient.kt:35` | HonestStateTest#captureLoopDoesNotSpinOnNegativeReadAndReportsHardwareBusy |
| A32 | LOW | Protocol | FIXED | `SwitchingModels.kt:40` | NetworkProtocolHardeningTest#controlAndAudioShareTtlRule |
| A33 | MEDIUM | Session | FIXED | `VoiceSessionCoordinator.kt:55` | Stage3ReachabilityTest#backupSessionReactiveAvailabilityAndExpiryTimer |
| A34 | LOW | Resources | FIXED | `res/values/strings.xml:335` | StringsParityTest#meshStringKeysHaveExactParityAcrossEnglishHindiAndMarathi |
| A35 | MEDIUM | Navigation | FIXED | `NavGraph.kt:418` | HandoffCorrectnessTest#recovery_keepsMeshAudioUntilLiveKitConfirmed |
| A36 | LOW | CI | FIXED | `.github/workflows/build.yml:48` | ProductionWiringTest#meshVoxBridge_receivesHardwarePttPort |
| A37 | LOW | Docs | PARTIAL | `README.md:1` | None (pending WP-51) |
| A38 | LOW | Simulation | FIXED | `MeshConfig.kt:26` | MeshStage1SimulationTest#setChain |
| A39 | HIGH | Audio | FIXED | `OfflineMeshVoiceTransport.kt:460` | MicAudioControlPathTest#defaultVoxIsOff |
| A40 | HIGH | Audio | FIXED | `OfflineMeshVoiceTransport.kt:294` | AudioHandoffRobustnessTest#handoff_rollsBackOwner |
| A41 | MEDIUM | Transport | FIXED | `OfflineMeshVoiceTransport.kt:154` | HandoffCorrectnessTest#offlineActive_meshDied_handsBack |
| A42 | HIGH | Audio | FIXED | `OfflineAudioCapture.kt:175` | MicAudioControlPathTest#emptyReads50_reportHardwareBusy |
| A43 | MEDIUM | UI | FIXED | `OfflineMeshLobbyScreen.kt:233` | OfflineMeshViewModelTest#createNewSessionOffloadsPBKDF2AndPopulatesActiveSession |
| A44 | INFO | Architecture | VERIFIED | `mobile-app/app/build.gradle:1` | ImportIsolationTest#onlineCodeDoesNotImportMesh |
| A45 | MEDIUM | Startup | FIXED | `App.kt:51` | Stage3ReachabilityTest#startAfterInstall_usesInstalledSettings |
| A46 | LOW | Audio | FIXED | `OfflineMeshVoiceTransport.kt:80` | MicAudioControlPathTest#deafen_survivesRecreate |
| A47 | LOW | Audio | FIXED (WP-45) | `MicController.kt:95` | MicControllerOrderingTest#multiThreadedOrderingAndConcurrencyTest |
| B1 | HIGH | Link | OPEN | `UdpMeshLink.kt:33` | None (pending WP-48) |
| B2 | CRITICAL | UI | FIXED (WP-45) | `OfflineAwareRoomHost.kt:130` | FallbackControlsTest#offlineActive_overlayPttDrivesOfflineCapture_notLiveKit |
| B3 | CRITICAL | Switching | FIXED (WP-46) | `AutomaticSwitchStateMachine.kt:133` | FailedHandoffBackoffTest#failedHandoff_backsOffAndSuppressesFlapping_atMostFourAttemptsInTwoMinutes |
| B4 | CRITICAL | Audio | FIXED (WP-45) | `MicController.kt:95` | MicControllerOrderingTest#multiThreadedOrderingAndConcurrencyTest |
| B5 | HIGH | Settings | FIXED (WP-44) | `MeshRuntimeSettings.kt:40` | MeshSettingsViewModelTest#togglePropagatesToOfflineMeshGateThroughSingleInstance |
| B6 | HIGH | Protocol | OPEN | `MeshConfig.kt:36` | None (pending WP-49) |
| B7 | HIGH | Verification | FIXED | `DEFECT_REGISTER.md:1` | ImportIsolationTest#onlineCodeDoesNotImportMesh |
| B8 | MEDIUM | Docs | OPEN | `docs/implementation/mesh/` | None (pending WP-51) |
| B9 | HIGH | Transport | FIXED (WP-47) | `OfflineMeshVoiceTransport.kt:258` | LinkSupervisorTest#linkSupervisor_recoversFromTransientLinkFailure_togglesIsLinkAlive |
| B10 | HIGH | Link | FIXED (WP-47) | `WifiNetworkWatcher.kt:1` | WifiRebindTest#wifiChangeEvents_debouncedToSingleRestart |
| B11 | HIGH | Coroutines | OPEN | `VoiceSessionCoordinator.kt:130` | None (pending WP-49) |
| B12 | MEDIUM | Link | FIXED (WP-47) | `NetworkLocks.kt:25` | MulticastLockLifecycleTest#multicastLockAcquiredOnDiscoveryStart_releasedOnStop_wifiLockOnlyOnAudio |
| B13 | MEDIUM | Resources | FIXED (WP-44) | `SettingsScreen.kt:265` | StringsParityTest#allRequiredMeshSettingsKeysPresentAcrossLocales |
| B14 | MEDIUM | UI | FIXED (WP-44) | `SettingsScreen.kt:225` | OfflineAvailabilityUiTest#noSharedSecret_returnsNoBackupCodeState |
| B15 | LOW | Switching | FIXED (WP-46) | `AutomaticSwitchStateMachine.kt:35` | FailedHandoffBackoffTest#failedHandoff_realDispatcherConcurrencyTest |
| B16 | HIGH | Switching | FIXED (WP-46) | `LocalHealthSignalsProvider.kt:85` | WiringTest#localHealthSignalsProviderSamplesLocalSignalsAccurately |
| B17 | HIGH | Security | OPEN | `ForwardingEngine.kt:496` | None (pending WP-49) |
| B18 | HIGH | Security | OPEN | `FeatureFlags.kt:28` | None (pending WP-49) |
| B19 | MEDIUM | CI | OPEN | `.github/workflows/build.yml:45` | None (pending WP-51) |
| B20 | MEDIUM | Testing | OPEN | `ProtocolHardeningTest.kt:50` | None (pending WP-52) |
| B21 | LOW | Testing | OPEN | `PHYSICAL_TEST_CHECKLIST.md:1` | None (pending WP-48) |
| B22 | LOW | Settings | OPEN | `SETTINGS_CONTRACT.md:10` | None (pending WP-51) |
| B23 | MEDIUM | UI | FIXED (WP-44/WP-46) | `OfflineAwareRoomHost.kt:45` | MeshSettingsViewModelTest#togglePropagatesToOfflineMeshGateThroughSingleInstance |
| B24 | LOW | Transport | FIXED (WP-45) | `OfflineMeshVoiceTransport.kt:310` | FallbackControlsTest#hostState_overlayActiveStatesMatchPlanSpecification |
| B25 | LOW | Lint | OPEN | `mobile-app/app/lint-baseline.xml:135` | None (pending WP-51) |
| B26 | LOW | Security | OPEN | `docs/security/mesh-threat-model.md:60` | None (pending WP-49) |
| B27 | MEDIUM | Testing | OPEN | `mobile-app/app/src/test/` | None (pending WP-52) |
