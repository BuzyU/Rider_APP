package com.ridervoice.mesh.switching

import com.ridervoice.mesh.convoy.ConvoyHash
import com.ridervoice.mesh.convoy.ConvoyMember
import com.ridervoice.mesh.convoy.ConvoyMembershipSnapshot
import com.ridervoice.mesh.convoy.ConvoyModeState
import com.ridervoice.mesh.convoy.CoordinatorOutput
import com.ridervoice.mesh.convoy.DecisionPhase
import com.ridervoice.mesh.convoy.LiveKitReadiness
import com.ridervoice.mesh.convoy.LocalTransportState
import com.ridervoice.mesh.convoy.MemberObservation
import com.ridervoice.mesh.convoy.MemberRole
import com.ridervoice.mesh.convoy.MeshReadiness
import com.ridervoice.mesh.convoy.NetState
import com.ridervoice.mesh.convoy.ReadinessSource
import com.ridervoice.mesh.convoy.TransportDecision
import com.ridervoice.mesh.convoy.TransportMode
import com.ridervoice.mesh.link.WifiDirectGroupController
import com.ridervoice.mesh.link.WifiDirectStatus
import com.ridervoice.mesh.notification.MeshStatusEvent
import com.ridervoice.mesh.notification.MeshStatusNotifier
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.session.HolderOfflineSecretProvider
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.session.OfflineSessionHolder
import com.ridervoice.mesh.switching.LocalHealthSignals
import com.ridervoice.mesh.topology.MeshControlExtension
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.LiveKitVoiceTransport
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.VoiceSessionCoordinator
import com.ridervoice.network.LiveKitManager
import com.ridervoice.state.ActiveRideRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MeshAutoSwitchManagerQueueTest {

    private fun createManager(
        clockNow: () -> Long = { 10_000L },
        meshTransport: OfflineMeshVoiceTransport = mockk(relaxed = true)
    ): MeshAutoSwitchManager {
        val arbiter = AudioOwnershipArbiter()
        val coordinator = VoiceSessionCoordinator(
            liveKitTransport = mockk<LiveKitVoiceTransport>(relaxed = true),
            meshTransport = meshTransport,
            audioOwnershipArbiter = arbiter
        )
        return MeshAutoSwitchManager(
            activeRideRepository = mockk<ActiveRideRepository>(relaxed = true),
            liveKitManager = mockk<LiveKitManager>(relaxed = true),
            coordinator = coordinator,
            meshTransport = meshTransport,
            healthSignalsProvider = mockk(relaxed = true),
            clock = Clock { clockNow() }
        )
    }

    @Test
    fun decisionQueue_dropsOldestWhenExceeding18() {
        val manager = createManager()
        val sid = UUID.randomUUID()
        val tag = ConvoyHash.computeProposerTag(sid, "u-1")
        for (i in 1..25) {
            val decId = ConvoyHash.computeOfflineDecisionId(sid, i.toLong(), tag)
            manager.enqueueBoundedDecision(
                TransportDecision(i.toLong(), TransportMode.OFFLINE, DecisionPhase.ADOPTED, tag, decisionId = decId)
            )
        }
        val drained = manager.drainBoundedDecisions()
        assertEquals(MeshAutoSwitchManager.MAX_DECISION_QUEUE_SIZE, drained.size)
        assertEquals(18, drained.size)
        assertEquals(8L, drained.first().generation)
        assertEquals(25L, drained.last().generation)
    }

    @Test
    fun observationQueue_dropsOldestWhenExceeding30() {
        val manager = createManager()
        for (i in 1..40) {
            manager.enqueueBoundedObservation(
                MemberObservation(
                    memberId = "rider-${i % 6}",
                    lastStateAtMs = 10_000L,
                    netState = NetState.HEALTHY,
                    livekitReadiness = LiveKitReadiness.READY,
                    recoveryEligible = true,
                    meshReadiness = MeshReadiness.ROUTE_AVAILABLE,
                    localExecutionState = LocalTransportState.ONLINE_ACTIVE,
                    readinessSeq = i,
                    senderIncarnation = i.toLong(),
                    baseGen = 0L,
                    ackFor = null,
                    ageMs = 0,
                    hops = 0,
                    readinessSource = ReadinessSource.AUTO_PROBE
                )
            )
        }
        val drained = manager.drainBoundedObservations()
        assertEquals(MeshAutoSwitchManager.MAX_OBSERVATION_QUEUE_SIZE, drained.size)
        assertEquals(30, drained.size)
        assertEquals(11L, drained.first().senderIncarnation)
        assertEquals(40L, drained.last().senderIncarnation)
    }

    @Test
    fun pendingControlBurst_rebuildsFreshPayloadWhenLinkRecovers() {
        val meshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { meshTransport.sendControlFrame(any()) } returns true
        val sid = UUID.randomUUID()
        val membership = ConvoyMembershipSnapshot.create(
            sid,
            listOf(
                ConvoyMember("u-1", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-2", MemberRole.MESH_PARTICIPANT)
            )
        )
        val initMethod = MeshAutoSwitchManager::class.java.getDeclaredMethod(
            "initConvoySessionState",
            ActiveRideRepository.ActiveRide::class.java
        ).apply { isAccessible = true }
        val mgrWithId = MeshAutoSwitchManager(
            activeRideRepository = mockk(relaxed = true),
            liveKitManager = mockk(relaxed = true),
            coordinator = VoiceSessionCoordinator(mockk(relaxed = true), meshTransport, AudioOwnershipArbiter()),
            meshTransport = meshTransport,
            healthSignalsProvider = mockk(relaxed = true),
            localOnlineIdentity = { "u-1" }
        )
        initMethod.invoke(
            mgrWithId,
            ActiveRideRepository.ActiveRide(
                roomId = sid.toString(),
                displayName = "Test",
                isHost = true,
                phase = ActiveRideRepository.Phase.LIVE,
                convoyMembershipSnapshot = membership,
                hostUserId = "u-1"
            )
        )

        val tag = ConvoyHash.computeProposerTag(sid, "u-1")
        val decId = ConvoyHash.computeOfflineDecisionId(sid, 1L, tag)
        val decision = TransportDecision(1L, TransportMode.OFFLINE, DecisionPhase.ADOPTED, tag, decisionId = decId)
        val out = CoordinatorOutput(
            acceptedDecision = decision,
            decisionToAdvertise = decision,
            convoyModeState = ConvoyModeState.OFFLINE,
            actions = emptyList(),
            generationToReserve = null,
            generationHighWater = 1L,
            generationExhausted = false,
            generationUnknown = false,
            ackFor = null,
            localRecoveryEligible = false,
            readinessSource = ReadinessSource.AUTO_PROBE,
            baseOfflineGen = 1L,
            diagnostics = emptyList()
        )
        val deadLink = LocalHealthSignals(
            isValidatedInternet = false,
            isLiveKitConnected = false,
            isLiveKitReconnecting = false,
            liveKitReconnectAttempts = 0,
            isHealthProbeSuccessful = false,
            hasValidMeshRoute = false,
            isMeshTransportAlive = false
        )
        assertFalse(mgrWithId.flushControlBurstIfAlive(membership, out, deadLink, "u-1", needsBurst = true))
        assertTrue(mgrWithId.pendingControlBurst)
        verify(exactly = 0) { meshTransport.sendControlFrame(any()) }

        val aliveLink = deadLink.copy(isMeshTransportAlive = true, hasValidMeshRoute = true)
        assertTrue(mgrWithId.flushControlBurstIfAlive(membership, out, aliveLink, "u-1", needsBurst = false))
        assertFalse(mgrWithId.pendingControlBurst)
        verify(exactly = 1) { meshTransport.sendControlFrame(any()) }
    }

    @Test
    fun inboundDecision_wakesLoopViaSignalBefore1sTick() = runTest {
        val manager = createManager()
        val waiter = async { manager.awaitNextTickOrWakeForTest(tickDelayMs = 1_000L) }
        val sid = UUID.randomUUID()
        val tag = ConvoyHash.computeProposerTag(sid, "u-2")
        val decId = ConvoyHash.computeOfflineDecisionId(sid, 1L, tag)
        manager.enqueueBoundedDecision(
            TransportDecision(1L, TransportMode.OFFLINE, DecisionPhase.ADOPTED, tag, decisionId = decId)
        )
        assertTrue("Wakeup signal must unblock loop immediately", waiter.await())
    }

    @Test
    fun onlineWarmStandby_goFailureTriggersFailoverAndResetsCooldown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var now = 500_000L
        val sid = UUID.randomUUID()
        val membership = ConvoyMembershipSnapshot.create(
            sid,
            listOf(
                ConvoyMember("u-host", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-r2", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-r3", MemberRole.MESH_PARTICIPANT)
            )
        )
        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        every { rideRepo.state } returns MutableStateFlow(
            ActiveRideRepository.ActiveRide(
                roomId = sid.toString(),
                displayName = "Failover Ride",
                isHost = false,
                phase = ActiveRideRepository.Phase.LIVE,
                convoyMembershipSnapshot = membership,
                hostUserId = "u-host"
            )
        )
        val holder = OfflineSessionHolder()
        holder.setBackup(OfflineSession.create(now))

        val p2pStatus = MutableStateFlow(WifiDirectStatus.FAILED)
        val p2pConnected = MutableStateFlow(false)
        val controller = mockk<WifiDirectGroupController>(relaxed = true)
        every { controller.status } returns p2pStatus
        every { controller.isGroupConnected } returns p2pConnected
        every { controller.p2pOwnerAddress } returns MutableStateFlow(null)

        val notifier = mockk<MeshStatusNotifier>(relaxed = true)
        val meshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { meshTransport.participants } returns MutableStateFlow(emptyList())
        every { meshTransport.deafened } returns MutableStateFlow(false)
        every { meshTransport.isLinkAlive } returns MutableStateFlow(false)

        val mgr = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = mockk(relaxed = true),
            coordinator = VoiceSessionCoordinator(mockk(relaxed = true), meshTransport, AudioOwnershipArbiter()),
            meshTransport = meshTransport,
            healthSignalsProvider = mockk(relaxed = true),
            secretProvider = HolderOfflineSecretProvider(holder) { now },
            clock = Clock { now },
            dispatcher = dispatcher,
            localOnlineIdentity = { "u-r2" },
            wifiDirectGroupController = controller,
            statusNotifier = notifier
        )

        val initMethod = MeshAutoSwitchManager::class.java.getDeclaredMethod(
            "initConvoySessionState",
            ActiveRideRepository.ActiveRide::class.java
        ).apply { isAccessible = true }
        val ensureMethod = MeshAutoSwitchManager::class.java.getDeclaredMethod(
            "ensureP2pGroupWithSelector",
            ActiveRideRepository.ActiveRide::class.java,
            ConvoyMembershipSnapshot::class.java,
            OfflineSession::class.java,
            String::class.java,
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val ride = rideRepo.state.value!!
        initMethod.invoke(mgr, ride)
        // First call while online selects u-host (client sees isHost = false)
        ensureMethod.invoke(mgr, ride, membership, holder.backup.value!!, "u-r2", now)
        verify(atLeast = 1) { controller.ensureActive(any(), isHost = false) }

        // Even while ONLINE, GO FAILED status forces immediate failover to canonical u-r2 and resets cooldown
        now += 1_000L
        ensureMethod.invoke(mgr, ride, membership, holder.backup.value!!, "u-r2", now)
        verify(atLeast = 1) { controller.resetFailureCooldown() }
        verify(atLeast = 1) { controller.ensureActive(any(), isHost = true) }

        // Test STARTING_OFFLINE alert fires before handoff completes and close releases notifier
        val emitMethod = MeshAutoSwitchManager::class.java.getDeclaredMethod("emitEdgeStatusNotifications").apply {
            isAccessible = true
        }
        val stateField = MeshAutoSwitchManager::class.java.getDeclaredField("_localTransportState").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        (stateField.get(mgr) as kotlinx.coroutines.flow.MutableStateFlow<LocalTransportState>).value =
            LocalTransportState.STARTING_OFFLINE
        emitMethod.invoke(mgr)
        verify(exactly = 1) { notifier.notify(MeshStatusEvent.STARTING_OFFLINE, false, 0) }

        mgr.close()
        verify(exactly = 1) { notifier.release() }
    }

    @Test
    fun onlineInitialConnect_doesNotPrematurelyFailoverAt10sWhileConnecting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var now = 600_000L
        val sid = UUID.randomUUID()
        val membership = ConvoyMembershipSnapshot.create(
            sid,
            listOf(
                ConvoyMember("u-host", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-r2", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-r3", MemberRole.MESH_PARTICIPANT)
            )
        )
        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        every { rideRepo.state } returns MutableStateFlow(
            ActiveRideRepository.ActiveRide(
                roomId = sid.toString(),
                displayName = "Startup Test",
                isHost = false,
                phase = ActiveRideRepository.Phase.LIVE,
                convoyMembershipSnapshot = membership,
                hostUserId = "u-host"
            )
        )
        val holder = OfflineSessionHolder()
        holder.setBackup(OfflineSession.create(now))

        val p2pStatus = MutableStateFlow(WifiDirectStatus.CONNECTING)
        val controller = mockk<WifiDirectGroupController>(relaxed = true)
        every { controller.status } returns p2pStatus
        every { controller.isGroupConnected } returns MutableStateFlow(false)
        every { controller.p2pOwnerAddress } returns MutableStateFlow(null)

        val meshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { meshTransport.participants } returns MutableStateFlow(emptyList())
        every { meshTransport.isLinkAlive } returns MutableStateFlow(false)

        val mgr = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = mockk(relaxed = true),
            coordinator = VoiceSessionCoordinator(mockk(relaxed = true), meshTransport, AudioOwnershipArbiter()),
            meshTransport = meshTransport,
            healthSignalsProvider = mockk(relaxed = true),
            secretProvider = HolderOfflineSecretProvider(holder) { now },
            clock = Clock { now },
            dispatcher = dispatcher,
            localOnlineIdentity = { "u-r2" },
            wifiDirectGroupController = controller
        )

        val initMethod = MeshAutoSwitchManager::class.java.getDeclaredMethod(
            "initConvoySessionState",
            ActiveRideRepository.ActiveRide::class.java
        ).apply { isAccessible = true }
        val ensureMethod = MeshAutoSwitchManager::class.java.getDeclaredMethod(
            "ensureP2pGroupWithSelector",
            ActiveRideRepository.ActiveRide::class.java,
            ConvoyMembershipSnapshot::class.java,
            OfflineSession::class.java,
            String::class.java,
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val ride = rideRepo.state.value!!
        initMethod.invoke(mgr, ride)
        ensureMethod.invoke(mgr, ride, membership, holder.backup.value!!, "u-r2", now)

        // Advance 15 seconds while still CONNECTING: must NOT trigger split-brain GO promotion
        now += 15_000L
        ensureMethod.invoke(mgr, ride, membership, holder.backup.value!!, "u-r2", now)
        verify(exactly = 0) { controller.resetFailureCooldown() }
        verify(exactly = 0) { controller.ensureActive(any(), isHost = true) }

        // Only if controller actually hits FAILED does failover promote u-r2
        p2pStatus.value = WifiDirectStatus.FAILED
        now += 1_000L
        ensureMethod.invoke(mgr, ride, membership, holder.backup.value!!, "u-r2", now)
        verify(exactly = 1) { controller.resetFailureCooldown() }
        verify(exactly = 1) { controller.ensureActive(any(), isHost = true) }
        mgr.close()
    }

    @Test
    fun goAnnounce_receivedHigherEpoch_adoptedByPeerForGoSelection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val now = 700_000L
        val sid = ConvoyHash.stableSessionUuid("room-go-announce")
        val membership = ConvoyMembershipSnapshot.create(
            sid,
            listOf(
                ConvoyMember("u-host", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-r2", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("u-r3", MemberRole.MESH_PARTICIPANT)
            )
        )
        val rideRepo = mockk<ActiveRideRepository>(relaxed = true)
        every { rideRepo.state } returns MutableStateFlow(
            ActiveRideRepository.ActiveRide(
                roomId = "room-go-announce",
                displayName = "GoAnnounce",
                isHost = false,
                phase = ActiveRideRepository.Phase.LIVE,
                convoyMembershipSnapshot = membership,
                hostUserId = "u-host"
            )
        )
        val holder = OfflineSessionHolder()
        holder.setBackup(OfflineSession.create(now))

        val controller = mockk<WifiDirectGroupController>(relaxed = true)
        every { controller.status } returns MutableStateFlow(WifiDirectStatus.IDLE)
        every { controller.isGroupConnected } returns MutableStateFlow(false)
        every { controller.p2pOwnerAddress } returns MutableStateFlow(null)

        val meshTransport = mockk<OfflineMeshVoiceTransport>(relaxed = true)
        every { meshTransport.participants } returns MutableStateFlow(emptyList())
        every { meshTransport.isLinkAlive } returns MutableStateFlow(true)
        var capturedHandler: ((MeshPacket) -> Unit)? = null
        every { meshTransport.onControlPacketReceived = any() } answers {
            capturedHandler = firstArg()
        }

        val mgr = MeshAutoSwitchManager(
            activeRideRepository = rideRepo,
            liveKitManager = mockk(relaxed = true),
            coordinator = VoiceSessionCoordinator(mockk(relaxed = true), meshTransport, AudioOwnershipArbiter()),
            meshTransport = meshTransport,
            healthSignalsProvider = mockk(relaxed = true),
            secretProvider = HolderOfflineSecretProvider(holder) { now },
            clock = Clock { now },
            dispatcher = dispatcher,
            localOnlineIdentity = { "u-r3" },
            wifiDirectGroupController = controller
        )

        val initMethod = MeshAutoSwitchManager::class.java.getDeclaredMethod(
            "initConvoySessionState",
            ActiveRideRepository.ActiveRide::class.java
        ).apply { isAccessible = true }
        val ensureMethod = MeshAutoSwitchManager::class.java.getDeclaredMethod(
            "ensureP2pGroupWithSelector",
            ActiveRideRepository.ActiveRide::class.java,
            ConvoyMembershipSnapshot::class.java,
            OfflineSession::class.java,
            String::class.java,
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val ride = rideRepo.state.value!!
        initMethod.invoke(mgr, ride)

        val r3Idx = membership.indexOfMeshParticipant("u-r3")
        val announceBytes = MeshControlExtension.encodeGoAnnounce(
            MeshControlExtension.GoAnnounce(
                electionEpoch = 2,
                electedGoTag = ConvoyHash.computeProposerTag(membership.sessionId, "u-r3"),
                electedRosterIdx = r3Idx
            )
        )
        capturedHandler!!.invoke(
            MeshPacket(
                type = MeshPacketType.CONTROL,
                sourceId = "u-host",
                previousHopId = "u-host",
                sequence = 1,
                ttl = 5,
                hopCount = 0,
                audioFormat = AudioFormatId.NONE,
                epoch = 1,
                payload = announceBytes
            )
        )

        ensureMethod.invoke(mgr, ride, membership, holder.backup.value!!, "u-r3", now)
        verify(atLeast = 1) { controller.ensureActive(any(), isHost = true) }
        mgr.close()
    }

    @Test
    fun observationWithRecoveryEligible_wakesLoopImmediately() = runTest {
        val manager = createManager()
        val waiter = async { manager.awaitNextTickOrWakeForTest(tickDelayMs = 1_000L) }
        manager.enqueueBoundedObservation(
            MemberObservation(
                memberId = "u-2",
                lastStateAtMs = 10_000L,
                netState = NetState.HEALTHY,
                livekitReadiness = LiveKitReadiness.READY,
                recoveryEligible = true,
                meshReadiness = MeshReadiness.ROUTE_AVAILABLE,
                localExecutionState = LocalTransportState.OFFLINE_ACTIVE,
                readinessSeq = 1,
                senderIncarnation = 1L,
                baseGen = 1L,
                ackFor = null,
                ageMs = 0,
                hops = 0,
                readinessSource = ReadinessSource.AUTO_PROBE
            )
        )
        assertTrue("Recovery-eligible observation must wake loop before 1s tick", waiter.await())
    }
}
