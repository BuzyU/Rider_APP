package com.ridervoice.mesh.convoy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ConvoyVectorsAndCoordinatorTest {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    @Test
    fun phase1_publishedTestVectors_matchByteForByte() {
        val sessionId = UUID.fromString("11223344-5566-4777-8899-aabbccddeeff")
        val snapshot = ConvoyMembershipSnapshot.create(
            sessionId = sessionId,
            rawMembers = listOf(
                ConvoyMember("rider-uid-charlie", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("rider-uid-alpha", MemberRole.MESH_PARTICIPANT),
                ConvoyMember("rider-uid-bravo", MemberRole.NON_MESH_PARTICIPANT)
            ),
            localRevision = 42L
        )

        assertEquals(listOf("rider-uid-alpha", "rider-uid-charlie"), snapshot.meshParticipants)
        assertEquals("bc828197b52e28bfb7a0ae2f41da7208eee20220c9551889b0b70a4c0538311d", toHex(snapshot.rosterHash))
        assertEquals("bc828197b52e28bf", toHex(snapshot.rosterHash8))

        val tagAlpha = ConvoyHash.computeProposerTag(sessionId, "rider-uid-alpha")
        val tagCharlie = ConvoyHash.computeProposerTag(sessionId, "rider-uid-charlie")
        assertEquals("fc6357361240605c", toHex(tagAlpha))
        assertEquals("6111afa96c165867", toHex(tagCharlie))

        val offlineId = ConvoyHash.computeOfflineDecisionId(sessionId, 1L, tagAlpha)
        assertEquals("c9a3dde6bbd38ddf", toHex(offlineId))

        val propId = ConvoyHash.computeOnlineProposedId(
            sessionId = sessionId,
            generation = 2L,
            proposerTag = tagCharlie,
            rosterHash8 = snapshot.rosterHash8,
            baseOfflineId = offlineId,
            excludedBitmap = 0x00,
            manualExclusion = false
        )
        assertEquals("dd6cc707da420d5d", toHex(propId))

        val ackSeqs = listOf(105, 210)
        val commitId = ConvoyHash.computeOnlineCommittedId(
            sessionId = sessionId,
            generation = 2L,
            proposerTag = tagCharlie,
            rosterHash8 = snapshot.rosterHash8,
            baseOfflineId = offlineId,
            excludedBitmap = 0x00,
            manualExclusion = false,
            readyBitmap = 0x03,
            ackSeqs = ackSeqs
        )
        assertEquals("ff8081a78e3f2b45", toHex(commitId))

        val commit = TransportDecision(
            generation = 2L,
            mode = TransportMode.ONLINE,
            phase = DecisionPhase.ONLINE_COMMITTED,
            proposerTag = tagCharlie,
            rosterHash8 = snapshot.rosterHash8,
            baseOfflineId = offlineId,
            excludedBitmap = 0x00,
            readyBitmap = 0x03,
            ackSeqs = ackSeqs,
            manualExclusion = false,
            decisionId = commitId
        )

        // Recompute proposalId solely from commit fields
        assertArrayEquals(propId, commit.proposalId(sessionId))
        // Validate disjoint/complete bitmaps and canonical ackSeqs ordering
        assertEquals(CommitValidationResult.VALID, OnlineCommitValidator.validate(commit, snapshot, ConvoyProtocolConfig()))
    }

    @Test
    fun phase1_snapshotNfcAndDuplicateRejection_andLocalRevisionExclusion() {
        val sid = UUID.randomUUID()
        val s1 = ConvoyMembershipSnapshot.create(
            sid,
            listOf(ConvoyMember("caf\u00e9", MemberRole.MESH_PARTICIPANT), ConvoyMember("bob", MemberRole.MESH_PARTICIPANT)),
            localRevision = 1L
        )
        val s2 = ConvoyMembershipSnapshot.create(
            sid,
            listOf(ConvoyMember("bob", MemberRole.MESH_PARTICIPANT), ConvoyMember("cafe\u0301", MemberRole.MESH_PARTICIPANT)),
            localRevision = 999L
        )
        assertArrayEquals(s1.rosterHash, s2.rosterHash)

        var threw = false
        try {
            ConvoyMembershipSnapshot.create(
                sid,
                listOf(ConvoyMember("caf\u00e9", MemberRole.MESH_PARTICIPANT), ConvoyMember("cafe\u0301", MemberRole.NON_MESH_PARTICIPANT))
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Duplicate NFC member ID must be rejected", threw)
    }

    @Test
    fun phase4_codecRoundTrip_andWorstCaseSixParticipantSize() {
        val sid = UUID.randomUUID()
        val members = (1..6).map { ConvoyMember("rider-$it", MemberRole.MESH_PARTICIPANT) }
        val snap = ConvoyMembershipSnapshot.create(sid, members)
        val tag = ConvoyHash.computeProposerTag(sid, "rider-1")
        val baseOff = ConvoyHash.computeOfflineDecisionId(sid, 10L, tag)
        val ackSeqs = listOf(11, 22, 33, 44, 55, 66)
        val commitId = ConvoyHash.computeOnlineCommittedId(
            sid, 11L, tag, snap.rosterHash8, baseOff, 0, false, 0b111111, ackSeqs
        )
        val commit = TransportDecision(
            generation = 11L,
            mode = TransportMode.ONLINE,
            phase = DecisionPhase.ONLINE_COMMITTED,
            proposerTag = tag,
            rosterHash8 = snap.rosterHash8,
            baseOfflineId = baseOff,
            excludedBitmap = 0,
            readyBitmap = 0b111111,
            ackSeqs = ackSeqs,
            manualExclusion = false,
            decisionId = commitId
        )
        val records = (0..5).map { idx ->
            ConvoyReadinessWireRecord(
                rosterIdx = idx,
                senderIncarnation = 0x1122334455667788L + idx,
                seq = 100 + idx,
                livekitReady = true,
                recoveryEligible = true,
                meshReady = true,
                netLost = false,
                localMode = LocalModeWire.OFFLINE_ACTIVE,
                readinessSource = ReadinessSource.AUTO_PROBE,
                baseGen = 10L,
                ackFor = commitId,
                ageTenthsSec = idx * 5,
                hops = idx
            )
        }
        val wire = ConvoyStateCodec.encode(ConvoyStateWirePayload(decision = commit, records = records))
        assertEquals(218, wire.size) // Exact worst-case 6-participant budget
        assertTrue(wire.size <= 400)

        val decoded = ConvoyStateCodec.decode(wire, meshParticipantCount = 6)
        assertTrue(decoded is ConvoyStateDecodeResult.Ok)
        val ok = (decoded as ConvoyStateDecodeResult.Ok).payload
        assertEquals(commit, ok.decision)
        assertEquals(records, ok.records)

        // Truncated / unknown version / trailing garbage never throw
        assertTrue(ConvoyStateCodec.decode(wire.copyOf(wire.size - 1), 6) is ConvoyStateDecodeResult.Malformed)
        assertTrue(ConvoyStateCodec.decode(wire + byteArrayOf(0x00), 6) is ConvoyStateDecodeResult.Malformed)
        val badVer = wire.copyOf().also { it[0] = 0x7F }
        assertTrue(ConvoyStateCodec.decode(badVer, 6) is ConvoyStateDecodeResult.UnsupportedVersion)
    }

    @Test
    fun phase2_generationUnknown_andExhausted_andPlausibilityBounds() {
        val sid = UUID.randomUUID()
        val snap = ConvoyMembershipSnapshot.create(
            sid,
            listOf(ConvoyMember("a", MemberRole.MESH_PARTICIPANT), ConvoyMember("b", MemberRole.MESH_PARTICIPANT))
        )
        var now = 10_000L
        val coord = ConvoyTransportCoordinator("a", MonotonicClock { now })
        coord.markGenerationUnknown()

        // While GENERATION_UNKNOWN, local total loss must NOT propose
        val outUnknown = coord.evaluate(
            CoordinatorInput(
                localMemberId = "a",
                membership = snap,
                currentDecision = null,
                observations = emptyList(),
                localTotalLossConfirmed = true,
                localRecoveryStable = false,
                localMeshRouteAvailable = true,
                generationHighWater = 0L,
                generationUnknown = true
            )
        )
        assertNull(outUnknown.acceptedDecision)
        assertNull(outUnknown.generationToReserve)
        assertTrue(outUnknown.generationUnknown)

        // Implausible jump blocked when baseline exists
        val coord2 = ConvoyTransportCoordinator("a", MonotonicClock { now })
        coord2.setInitialStateForTest(null, highWater = 5L, unknown = false)
        val tagB = ConvoyHash.computeProposerTag(sid, "b")
        val hugeGen = 5L + ConvoyProtocolConfig.PLAUSIBILITY_JUMP_LIMIT + 1L
        val hugeOffline = TransportDecision(
            generation = hugeGen,
            mode = TransportMode.OFFLINE,
            phase = DecisionPhase.ADOPTED,
            proposerTag = tagB,
            decisionId = ConvoyHash.computeOfflineDecisionId(sid, hugeGen, tagB)
        )
        val outHuge = coord2.evaluate(
            CoordinatorInput(
                localMemberId = "a",
                membership = snap,
                currentDecision = null,
                observations = emptyList(),
                localTotalLossConfirmed = false,
                localRecoveryStable = false,
                localMeshRouteAvailable = true,
                generationHighWater = 5L,
                incomingDecisions = listOf(hugeOffline)
            )
        )
        assertNull(outHuge.acceptedDecision)
        assertEquals(5L, outHuge.generationHighWater)
        assertTrue(outHuge.diagnostics.any { it.code == "IMPLAUSIBLE_GENERATION" })

        // Generation exhaustion stops creating decisions
        val coord3 = ConvoyTransportCoordinator("a", MonotonicClock { now })
        coord3.setInitialStateForTest(null, highWater = ConvoyProtocolConfig.MAX_GENERATION, unknown = false)
        val outEx = coord3.evaluate(
            CoordinatorInput(
                localMemberId = "a",
                membership = snap,
                currentDecision = null,
                observations = emptyList(),
                localTotalLossConfirmed = true,
                localRecoveryStable = false,
                localMeshRouteAvailable = true,
                generationHighWater = ConvoyProtocolConfig.MAX_GENERATION
            )
        )
        assertTrue(outEx.generationExhausted)
        assertNull(outEx.generationToReserve)
        assertNull(outEx.acceptedDecision)
    }

    @Test
    fun phase2_commitValidation_rejectsOverlapOrUnreadyProposer_identicallyEverywhere() {
        val sid = UUID.randomUUID()
        val snap = ConvoyMembershipSnapshot.create(
            sid,
            listOf(ConvoyMember("a", MemberRole.MESH_PARTICIPANT), ConvoyMember("b", MemberRole.MESH_PARTICIPANT))
        )
        val tagA = ConvoyHash.computeProposerTag(sid, "a")
        val offId = ConvoyHash.computeOfflineDecisionId(sid, 1L, tagA)

        // Overlapping readyBitmap and excludedBitmap
        val badCommitId = ConvoyHash.computeOnlineCommittedId(
            sid, 2L, tagA, snap.rosterHash8, offId, 0b10, true, 0b11, listOf(1, 2)
        )
        val badCommit = TransportDecision(
            generation = 2L,
            mode = TransportMode.ONLINE,
            phase = DecisionPhase.ONLINE_COMMITTED,
            proposerTag = tagA,
            rosterHash8 = snap.rosterHash8,
            baseOfflineId = offId,
            excludedBitmap = 0b10,
            readyBitmap = 0b11,
            ackSeqs = listOf(1, 2),
            manualExclusion = true,
            decisionId = badCommitId
        )
        assertEquals(CommitValidationResult.INVALID_BITMAPS, OnlineCommitValidator.validate(badCommit, snap, ConvoyProtocolConfig()))

        // T1 forbids automatic exclusion (manualExclusion=false with excludedBitmap != 0)
        val autoExId = ConvoyHash.computeOnlineCommittedId(
            sid, 2L, tagA, snap.rosterHash8, offId, 0b10, false, 0b01, listOf(1, 0)
        )
        val autoEx = TransportDecision(
            generation = 2L,
            mode = TransportMode.ONLINE,
            phase = DecisionPhase.ONLINE_COMMITTED,
            proposerTag = tagA,
            rosterHash8 = snap.rosterHash8,
            baseOfflineId = offId,
            excludedBitmap = 0b10,
            readyBitmap = 0b01,
            ackSeqs = listOf(1, 0),
            manualExclusion = false,
            decisionId = autoExId
        )
        assertEquals(CommitValidationResult.EXCLUSION_POLICY_VIOLATION, OnlineCommitValidator.validate(autoEx, snap, ConvoyProtocolConfig()))
    }

    @Test
    fun phase2_singleFlightExecutor_neverOverlapsAndConvergesToLatestDesired() {
        val sid = UUID.randomUUID()
        val tagA = ConvoyHash.computeProposerTag(sid, "a")
        val off1 = TransportDecision(
            generation = 1L,
            mode = TransportMode.OFFLINE,
            phase = DecisionPhase.ADOPTED,
            proposerTag = tagA,
            decisionId = ConvoyHash.computeOfflineDecisionId(sid, 1L, tagA)
        )
        val snap = ConvoyMembershipSnapshot.create(sid, listOf(ConvoyMember("a", MemberRole.MESH_PARTICIPANT)))
        val com2 = TransportDecision(
            generation = 2L,
            mode = TransportMode.ONLINE,
            phase = DecisionPhase.ONLINE_COMMITTED,
            proposerTag = tagA,
            rosterHash8 = snap.rosterHash8,
            baseOfflineId = off1.decisionId,
            excludedBitmap = 0,
            readyBitmap = 1,
            ackSeqs = listOf(1),
            decisionId = ConvoyHash.computeOnlineCommittedId(sid, 2L, tagA, snap.rosterHash8, off1.decisionId, 0, false, 1, listOf(1))
        )

        val exec = SingleFlightHandoffExecutor()
        exec.onDesiredDecision(off1)
        val cmd1 = exec.pollNextCommand(nowMs = 1000L, canFallbackToMesh = true, canRecoverToLiveKit = true)
        assertTrue(cmd1 is SingleFlightHandoffExecutor.Command.RunFallback)
        assertTrue(exec.isInFlight())

        // While fallback is in flight, a newer ONLINE_COMMITTED arrives; must NOT start recovery concurrently
        exec.onDesiredDecision(com2)
        assertNull(exec.pollNextCommand(nowMs = 1100L, canFallbackToMesh = true, canRecoverToLiveKit = true))

        // Finish fallback -> actual is OFFLINE, desired is ONLINE -> starts recovery cleanly
        exec.onHandoffFinished(off1.decisionId, success = true, nowMs = 1500L)
        assertFalse(exec.isInFlight())
        val cmd2 = exec.pollNextCommand(nowMs = 1500L, canFallbackToMesh = true, canRecoverToLiveKit = true)
        assertTrue(cmd2 is SingleFlightHandoffExecutor.Command.RunRecovery)
        exec.onHandoffFinished(com2.decisionId, success = true, nowMs = 2000L)
        assertEquals(SingleFlightHandoffExecutor.ActualTransport.ONLINE, exec.currentActualTransport())
    }
}
