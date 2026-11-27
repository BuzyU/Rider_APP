package com.ridervoice.mesh.convoy

import java.util.UUID

/**
 * Pure Kotlin, deterministic, leaderless Convoy-Wide Transport Coordinator (v7 T1).
 *
 * Guarantees:
 * - Zero Android dependencies; deterministic given identical inputs and [MonotonicClock].
 * - Keeps convoy decision state separate from local execution status.
 * - Never merges fields across decisions.
 * - Write-ahead reserves generation before sending proposals.
 * - Observation-independent deterministic commit validation.
 */
class ConvoyTransportCoordinator(
    private val localMemberId: String,
    private val clock: MonotonicClock,
    private val config: ConvoyProtocolConfig = ConvoyProtocolConfig(),
    private val persistence: ConvoyStatePersistence? = null
) {
    private val normalizedLocalId = ConvoyHash.normalizeNfcString(localMemberId)
    private val receiverStartedAtMs: Long = clock.nowMs()

    private class MemberReadinessState {
        var currentIncarnation: Long? = null
        val retiredIncarnations = ArrayDeque<Long>()
        var lastSeq: Int? = null
        var lastHeardAtMs: Long? = null
        var warmupStartMs: Long? = null
        var warmupCount: Int = 0
        var warmupIncarnation: Long? = null
        var leaseExpiresAtMs: Long = 0L
        var latestRecord: MemberObservation? = null
        var nonReadySinceMs: Long? = null
    }

    private data class LiveProposalState(
        val decision: TransportDecision,
        val createdOrFirstSeenAtMs: Long,
        var lastSeenAtMs: Long,
        val ackHoldStartMs: MutableMap<String, Long> = HashMap()
    )

    private val memberReadiness = HashMap<String, MemberReadinessState>()
    private val liveProposals = LinkedHashMap<String, LiveProposalState>()
    private val transitionHistoryMs = ArrayDeque<Long>()
    private val diagnosticsBuffer = ArrayList<ProtocolDiagnostic>()

    private var acceptedDecision: TransportDecision? = null
    private var generationHighWater: Long = 0L
    private var generationUnknown: Boolean = false
    private var lastOfflineDecision: TransportDecision? = null

    private var lastAudioTransitionAtMs: Long = Long.MIN_VALUE / 4
    private var proposedInCurrentLossEpisode: Boolean = false
    private var wasTotalLossConfirmed: Boolean = false
    private var lastSuspectBurstSent: Boolean = false
    private var localEligibleSinceMs: Long? = null
    private var readinessSpreadAnchorMs: Long? = null
    private var abandonedProposalsCount: Int = 0
    private var lastManualExclusionExcludedAtMs: Long? = null
    private var wasExcludedInLatestCommit: Boolean = false
    private var activeManualAssertionValid: Boolean = false
    private var decisionConflictActive: Boolean = false
    private var membershipMismatchActive: Boolean = false
    private var ownActiveProposalIdHex: String? = null
    private var ownProposalCreatedAtMs: Long = 0L

    private var ownReadinessSeq: Int = 0
    private var ownCurrentAckFor: ByteArray? = null

    fun restoreFromPersistence(sessionId: UUID): PersistedConvoyState? {
        val persisted = persistence?.load(sessionId) ?: return null
        generationHighWater = persisted.generationHighWater
        generationUnknown = persisted.generationUnknown
        acceptedDecision = persisted.acceptedDecision
        if (persisted.acceptedDecision?.mode == TransportMode.OFFLINE) {
            lastOfflineDecision = persisted.acceptedDecision
        }
        transitionHistoryMs.clear()
        transitionHistoryMs.addAll(persisted.transitionTimestampsMs)
        return persisted
    }

    fun recordActualAudioTransition(nowMs: Long = clock.nowMs()) {
        lastAudioTransitionAtMs = nowMs
        transitionHistoryMs.addLast(nowMs)
        pruneTransitions(nowMs)
    }

    fun markGenerationUnknown() {
        generationUnknown = true
    }

    fun setInitialStateForTest(
        accepted: TransportDecision?,
        highWater: Long,
        unknown: Boolean = false
    ) {
        acceptedDecision = accepted
        if (accepted?.mode == TransportMode.OFFLINE) {
            lastOfflineDecision = accepted
        }
        generationHighWater = highWater
        generationUnknown = unknown
    }

    fun currentAcceptedDecision(): TransportDecision? = acceptedDecision
    fun currentGenerationHighWater(): Long = generationHighWater
    fun isGenerationUnknown(): Boolean = generationUnknown
    fun isGenerationExhausted(): Boolean = generationHighWater >= ConvoyProtocolConfig.MAX_GENERATION

    fun getReAdvertisableObservations(nowMs: Long = clock.nowMs()): List<MemberObservation> {
        val result = ArrayList<MemberObservation>()
        for ((memberId, state) in memberReadiness) {
            if (memberId == normalizedLocalId) continue
            val rec = state.latestRecord ?: continue
            if (rec.hops >= config.maxHops) continue
            val receivedAt = state.lastHeardAtMs ?: continue
            val heldMs = (nowMs - receivedAt).coerceAtLeast(0L)
            val newAgeMs = rec.ageMs + heldMs + config.hopPenaltyMs
            if (newAgeMs >= config.readinessLeaseMs) continue
            result.add(
                rec.copy(
                    ageMs = newAgeMs.toInt(),
                    hops = rec.hops + 1
                )
            )
        }
        return result
    }

    fun nextOwnReadinessSeq(): Int {
        ownReadinessSeq = (ownReadinessSeq + 1) and 0xFFFF
        return ownReadinessSeq
    }

    fun evaluate(input: CoordinatorInput): CoordinatorOutput {
        val now = clock.nowMs()
        val actions = ArrayList<TransportAction>()
        diagnosticsBuffer.clear()
        var generationToReserve: Long? = null

        val membership = input.membership
        val sessionId = membership.sessionId
        val ownTag = ConvoyHash.computeProposerTag(sessionId, normalizedLocalId)
        val localMeshIdx = membership.indexOfMeshParticipant(normalizedLocalId)
        val isLocalMeshParticipant = localMeshIdx >= 0

        if (input.generationUnknown) {
            generationUnknown = true
        }
        if (input.generationHighWater > generationHighWater && !generationUnknown) {
            generationHighWater = input.generationHighWater
        }
        if (input.currentDecision != null && acceptedDecision == null) {
            acceptedDecision = input.currentDecision
            if (input.currentDecision.mode == TransportMode.OFFLINE) {
                lastOfflineDecision = input.currentDecision
            }
        }

        // Loss episode edge tracking
        if (!input.localTotalLossConfirmed && wasTotalLossConfirmed) {
            proposedInCurrentLossEpisode = false
        }
        wasTotalLossConfirmed = input.localTotalLossConfirmed

        // Protocol M assertion validity (lapses immediately on netLost or total loss)
        activeManualAssertionValid = input.manualReadyAsserted && !input.localNetLost && !input.localTotalLossConfirmed

        // Early warning SUSPECT burst (Section 7.2)
        if (input.localNetLost && !input.localTotalLossConfirmed && !lastSuspectBurstSent && membership.isGroupFallbackEligible) {
            lastSuspectBurstSent = true
            actions.add(TransportAction.SendControlBurst("NET_SUSPECT"))
        } else if (!input.localNetLost) {
            lastSuspectBurstSent = false
        }

        // More than 6 mesh participants disables group + per-device fallback (Section 1.4)
        if (membership.isMeshCapExceeded) {
            actions.add(TransportAction.PromptRider(PromptKind.MORE_THAN_SIX_PARTICIPANTS))
        }

        // 1. Ingest incoming readiness records
        ingestObservations(input.observations, now, membership)

        // 2. Expire stale live proposals
        expireProposals(now)

        // 3. Process incoming decisions
        var observedRosterMismatchOnOnline = false
        for (incoming in input.incomingDecisions) {
            val outcome = processIncomingDecision(incoming, membership, now)
            if (outcome == IncomingOutcome.ROSTER_MISMATCH) {
                observedRosterMismatchOnOnline = true
            } else if (outcome == IncomingOutcome.ADOPTED) {
                val adopted = acceptedDecision!!
                actions.add(TransportAction.AdvertiseDecision(adopted))
                actions.add(TransportAction.SendControlBurst("DECISION_ADOPTED"))
                if (adopted.mode == TransportMode.OFFLINE) {
                    if (input.localMeshRouteAvailable) {
                        actions.add(TransportAction.ExecuteFallback(adopted.decisionId))
                    } else {
                        actions.add(TransportAction.ReportFallbackUnavailable("NO_LOCAL_MESH_ROUTE"))
                    }
                } else if (adopted.mode == TransportMode.ONLINE && adopted.phase == DecisionPhase.ONLINE_COMMITTED) {
                    val excluded = localMeshIdx >= 0 && ((adopted.excludedBitmap and (1 shl localMeshIdx)) != 0)
                    if (excluded) {
                        wasExcludedInLatestCommit = true
                        lastManualExclusionExcludedAtMs = now
                    } else {
                        wasExcludedInLatestCommit = false
                    }
                    if (!isLocalMeshParticipant || !excluded) {
                        actions.add(TransportAction.ExecuteRecovery(adopted.decisionId))
                    } else if (input.localLiveKitReadiness == LiveKitReadiness.READY) {
                        // Excluded member with LiveKit ready connects on its own without pulling convoy offline (9.4)
                        actions.add(TransportAction.ExecuteRecovery(adopted.decisionId))
                    } else {
                        actions.add(TransportAction.ReportRecoveryFailed("EXCLUDED_FROM_ONLINE_COMMIT"))
                    }
                }
            }
        }
        membershipMismatchActive = observedRosterMismatchOnOnline

        // Check local recovery eligibility (Section 8.1 & 8.9)
        val localProbeEligible = !input.probingUnavailable &&
            input.localRecoveryStable &&
            input.localLiveKitReadiness == LiveKitReadiness.READY &&
            !input.localNetLost &&
            !input.localTotalLossConfirmed
        val localManualEligible = activeManualAssertionValid && !input.localTotalLossConfirmed
        val localRecoveryEligible = localProbeEligible || localManualEligible
        val readinessSource = if (!localProbeEligible && localManualEligible) {
            ReadinessSource.MANUAL_ASSERT
        } else {
            ReadinessSource.AUTO_PROBE
        }

        if (localRecoveryEligible) {
            if (localEligibleSinceMs == null) localEligibleSinceMs = now
        } else {
            localEligibleSinceMs = null
        }

        val currentAccepted = acceptedDecision
        val currentModeIsOffline = currentAccepted?.mode == TransportMode.OFFLINE

        // 4. Proposal creation: ONLINE -> OFFLINE (Section 7.1)
        val canPropose = !generationUnknown && !isGenerationExhausted()
        if (canPropose &&
            isLocalMeshParticipant &&
            membership.isGroupFallbackEligible &&
            !currentModeIsOffline &&
            input.localTotalLossConfirmed &&
            !proposedInCurrentLossEpisode
        ) {
            val cooldownElapsed = !wasExcludedInLatestCommit ||
                (lastManualExclusionExcludedAtMs?.let { now - it >= config.refallbackCooldownMs } ?: true)
            if (!input.localMeshRouteAvailable) {
                actions.add(TransportAction.ReportFallbackUnavailable("NO_LOCAL_MESH_ROUTE"))
            } else if (cooldownElapsed && canCreateProposalWithGuards(now)) {
                val nextGen = generationHighWater + 1
                if (nextGen <= ConvoyProtocolConfig.MAX_GENERATION) {
                    // Write-ahead reserve BEFORE creating/advertising proposal
                    generationHighWater = nextGen
                    generationToReserve = nextGen
                    persistence?.saveGenerationHighWaterWriteAhead(sessionId, nextGen)

                    val decId = ConvoyHash.computeOfflineDecisionId(sessionId, nextGen, ownTag)
                    val decision = TransportDecision(
                        generation = nextGen,
                        mode = TransportMode.OFFLINE,
                        phase = DecisionPhase.ADOPTED,
                        proposerTag = ownTag,
                        decisionId = decId
                    )
                    acceptedDecision = decision
                    lastOfflineDecision = decision
                    proposedInCurrentLossEpisode = true
                    liveProposals.clear()
                    ownActiveProposalIdHex = null
                    persistence?.saveAcceptedDecisionAtomically(sessionId, decision, generationHighWater)

                    actions.add(TransportAction.AdvertiseDecision(decision))
                    actions.add(TransportAction.SendControlBurst("PROPOSE_OFFLINE"))
                    actions.add(TransportAction.ExecuteFallback(decision.decisionId))
                }
            }
        }

        // 5. Recovery: OFFLINE -> ONLINE (Section 8)
        val activeOfflineBase = currentAccepted?.takeIf { it.mode == TransportMode.OFFLINE }
        if (activeOfflineBase != null && isLocalMeshParticipant && !membershipMismatchActive) {
            // 5a. ACK arbitration: acknowledge ONLY the highest-ranked live ONLINE_PROPOSED for current OFFLINE base
            val winningProposal = selectWinningLiveProposal(activeOfflineBase, membership)
            if (winningProposal != null && localRecoveryEligible) {
                val winId = winningProposal.decision.decisionId
                val prevAck = ownCurrentAckFor
                if (prevAck == null || !prevAck.contentEquals(winId)) {
                    ownCurrentAckFor = winId.copyOf()
                    nextOwnReadinessSeq()
                    actions.add(TransportAction.SendControlBurst("ACK_ONLINE_PROPOSAL"))
                }
                // Record our own local ACK hold for commit evaluation if we are the proposer
                val ownAckTime = winningProposal.ackHoldStartMs.putIfAbsent(normalizedLocalId, now)
                if (ownAckTime == null) {
                    winningProposal.ackHoldStartMs[normalizedLocalId] = now
                }
            } else {
                if (ownCurrentAckFor != null) {
                    ownCurrentAckFor = null
                    nextOwnReadinessSeq()
                    actions.add(TransportAction.SendControlBurst("WITHDRAW_ACK"))
                }
            }

            // If we had an own proposal that is NOT the winning one anymore, abandon it
            val ownPropHex = ownActiveProposalIdHex
            if (ownPropHex != null && (winningProposal == null || keyOf(winningProposal.decision.decisionId) != ownPropHex)) {
                ownActiveProposalIdHex = null
            }

            // 5b. Check if our own winning proposal can COMMIT (Section 8.5)
            val ownLiveEntry = ownActiveProposalIdHex?.let { liveProposals[it] }
            if (ownLiveEntry != null && winningProposal != null && keyOf(winningProposal.decision.decisionId) == ownActiveProposalIdHex) {
                if (now - ownProposalCreatedAtMs < config.proposalTtlMs) {
                    val maybeCommit = tryBuildAndValidateCommit(ownLiveEntry, activeOfflineBase, membership, now)
                    if (maybeCommit != null) {
                        acceptedDecision = maybeCommit
                        liveProposals.clear()
                        ownActiveProposalIdHex = null
                        ownCurrentAckFor = null
                        abandonedProposalsCount = 0
                        persistence?.saveAcceptedDecisionAtomically(sessionId, maybeCommit, generationHighWater)

                        actions.add(TransportAction.AdvertiseDecision(maybeCommit))
                        actions.add(TransportAction.SendControlBurst("ONLINE_COMMITTED"))
                        actions.add(TransportAction.ExecuteRecovery(maybeCommit.decisionId))
                    }
                } else {
                    // Own proposal TTL expired
                    liveProposals.remove(ownActiveProposalIdHex)
                    ownActiveProposalIdHex = null
                    abandonedProposalsCount++
                }
            }

            // 5c. Propose ONLINE if no winning live proposal is visible
            val manualReq = input.manualExclusionRequest
            if (canPropose &&
                acceptedDecision?.mode == TransportMode.OFFLINE &&
                winningProposal == null &&
                localRecoveryEligible &&
                canCreateProposalWithGuards(now)
            ) {
                val excludedBitmap = if (manualReq != null) {
                    computeValidManualExcludedBitmap(manualReq, membership, activeOfflineBase, now)
                } else {
                    0
                }
                val isManual = manualReq != null && excludedBitmap != null
                val effectiveExcluded = excludedBitmap ?: 0
                val allRequiredReady = areAllRequiredMembersReady(membership, effectiveExcluded, activeOfflineBase.generation, now)

                if (allRequiredReady && (manualReq == null || isManual)) {
                    if (readinessSpreadAnchorMs == null) {
                        readinessSpreadAnchorMs = now
                    }
                    val staggerDelay = if (isManual) 0L else localMeshIdx * config.proposalStaggerMs
                    if (now - (readinessSpreadAnchorMs ?: now) >= staggerDelay) {
                        val nextGen = generationHighWater + 1
                        if (nextGen <= ConvoyProtocolConfig.MAX_GENERATION) {
                            generationHighWater = nextGen
                            generationToReserve = nextGen
                            persistence?.saveGenerationHighWaterWriteAhead(sessionId, nextGen)

                            val propId = ConvoyHash.computeOnlineProposedId(
                                sessionId = sessionId,
                                generation = nextGen,
                                proposerTag = ownTag,
                                rosterHash8 = membership.rosterHash8,
                                baseOfflineId = activeOfflineBase.decisionId,
                                excludedBitmap = effectiveExcluded,
                                manualExclusion = isManual
                            )
                            val proposal = TransportDecision(
                                generation = nextGen,
                                mode = TransportMode.ONLINE,
                                phase = DecisionPhase.ONLINE_PROPOSED,
                                proposerTag = ownTag,
                                rosterHash8 = membership.rosterHash8,
                                baseOfflineId = activeOfflineBase.decisionId,
                                excludedBitmap = effectiveExcluded,
                                manualExclusion = isManual,
                                decisionId = propId
                            )
                            val key = keyOf(propId)
                            val entry = LiveProposalState(proposal, now, now)
                            entry.ackHoldStartMs[normalizedLocalId] = now
                            liveProposals[key] = entry
                            ownActiveProposalIdHex = key
                            ownProposalCreatedAtMs = now
                            ownCurrentAckFor = propId.copyOf()
                            nextOwnReadinessSeq()

                            actions.add(TransportAction.AdvertiseDecision(proposal))
                            actions.add(TransportAction.SendControlBurst("PROPOSE_ONLINE"))
                        }
                    }
                } else {
                    readinessSpreadAnchorMs = null
                }
            } else {
                readinessSpreadAnchorMs = null
            }

            // 5d. Stuck-offline prompt (Section 8.8)
            if (acceptedDecision?.mode == TransportMode.OFFLINE && localRecoveryEligible) {
                val nonReadyIds = findReachableOrKnownNonReadyMembers(membership, activeOfflineBase.generation, now)
                val stuckByTime = nonReadyIds.isNotEmpty() && nonReadyIds.any { id ->
                    val s = memberReadiness[id]
                    val since = s?.nonReadySinceMs ?: receiverStartedAtMs
                    now - since >= config.reachableNotReadyPromptMs
                }
                if (stuckByTime || abandonedProposalsCount >= 3) {
                    actions.add(TransportAction.PromptRider(PromptKind.STUCK_OFFLINE_EXCLUSION, nonReadyIds))
                }
            }
        } else {
            ownCurrentAckFor = null
            readinessSpreadAnchorMs = null
        }

        // Manual assertion prompt when probing is unavailable (Protocol M, Section 8.9)
        if (input.probingUnavailable &&
            acceptedDecision?.mode == TransportMode.OFFLINE &&
            !input.localNetLost &&
            !activeManualAssertionValid
        ) {
            actions.add(TransportAction.PromptRider(PromptKind.PROTOCOL_M_MANUAL_RETURN))
        }

        if (actions.isEmpty()) {
            actions.add(TransportAction.NoOp)
        }

        val decisionToAdvertise = ownActiveProposalIdHex
            ?.let { liveProposals[it]?.decision }
            ?: acceptedDecision

        return CoordinatorOutput(
            acceptedDecision = acceptedDecision,
            decisionToAdvertise = decisionToAdvertise,
            convoyModeState = computeConvoyModeState(),
            actions = actions,
            generationToReserve = generationToReserve,
            generationHighWater = generationHighWater,
            generationExhausted = isGenerationExhausted(),
            generationUnknown = generationUnknown,
            ackFor = ownCurrentAckFor?.copyOf(),
            localRecoveryEligible = localRecoveryEligible,
            readinessSource = readinessSource,
            baseOfflineGen = lastOfflineDecision?.generation ?: 0L,
            ownReadinessSeq = ownReadinessSeq and 0xFFFF,
            diagnostics = diagnosticsBuffer.toList()
        )
    }

    private fun ingestObservations(
        observations: List<MemberObservation>,
        now: Long,
        membership: ConvoyMembershipSnapshot
    ) {
        val observedIds = HashSet<String>()
        for (obs in observations) {
            val memberId = ConvoyHash.normalizeNfcString(obs.memberId)
            if (memberId == normalizedLocalId) continue
            if (!membership.isMeshParticipant(memberId)) continue

            val state = memberReadiness.getOrPut(memberId) { MemberReadinessState() }
            if (obs.hops > config.maxHops || obs.ageMs >= config.readinessLeaseMs) {
                continue
            }

            // Retire / reject old incarnations (Section 11.6)
            if (obs.senderIncarnation in state.retiredIncarnations) {
                continue
            }
            val curInc = state.currentIncarnation
            if (curInc == null) {
                state.currentIncarnation = obs.senderIncarnation
                state.warmupIncarnation = obs.senderIncarnation
                state.warmupStartMs = now
                state.warmupCount = 1
                state.lastSeq = obs.readinessSeq and 0xFFFF
            } else if (curInc != obs.senderIncarnation) {
                state.retiredIncarnations.addLast(curInc)
                while (state.retiredIncarnations.size > ConvoyProtocolConfig.MAX_RETIRED_INCARNATIONS) {
                    state.retiredIncarnations.removeFirst()
                }
                state.currentIncarnation = obs.senderIncarnation
                state.warmupIncarnation = obs.senderIncarnation
                state.warmupStartMs = now
                state.warmupCount = 1
                state.lastSeq = obs.readinessSeq and 0xFFFF
                state.leaseExpiresAtMs = 0L
            } else {
                // Same incarnation: check SEQ_RESYNC (10 min silence)
                val lastHeard = state.lastHeardAtMs
                if (lastHeard != null && (now - lastHeard) >= config.seqResyncMs) {
                    state.lastSeq = obs.readinessSeq and 0xFFFF
                    state.warmupIncarnation = obs.senderIncarnation
                    state.warmupStartMs = now
                    state.warmupCount = 1
                    state.leaseExpiresAtMs = 0L
                } else {
                    val prevSeq = state.lastSeq
                    val curSeq = obs.readinessSeq and 0xFFFF
                    val isNewer = prevSeq == null || isSeqNewer16(curSeq, prevSeq)
                    if (!isNewer) {
                        // Do NOT renew lease on duplicate or older sequence (Section 8.2)
                        continue
                    }
                    state.lastSeq = curSeq
                    if (state.warmupIncarnation == obs.senderIncarnation) {
                        state.warmupCount++
                    }
                }
            }

            val rxAt = obs.lastStateAtMs ?: now
            state.lastHeardAtMs = rxAt
            val penalty = if (obs.hops > 0) config.hopPenaltyMs else 0L
            val effectiveOriginTime = rxAt - obs.ageMs - penalty
            val existingRecord = state.latestRecord
            val takeDirectPrecedence = existingRecord == null || obs.hops == 0 || existingRecord.hops > 0 || obs.readinessSeq != existingRecord.readinessSeq
            if (takeDirectPrecedence) {
                state.latestRecord = obs
            }
            state.leaseExpiresAtMs = effectiveOriginTime + config.readinessLeaseMs
            observedIds.add(memberId)

            if (obs.recoveryEligible) {
                state.nonReadySinceMs = null
            } else if (state.nonReadySinceMs == null) {
                state.nonReadySinceMs = now
            }

            // Track ACK hold start for live proposals
            val ackHex = obs.ackFor?.let { keyOf(it) }
            for ((propKey, propEntry) in liveProposals) {
                val matchesAck = ackHex == propKey && obs.recoveryEligible
                if (matchesAck) {
                    propEntry.ackHoldStartMs.putIfAbsent(memberId, now)
                } else {
                    propEntry.ackHoldStartMs.remove(memberId)
                }
            }
        }

        // Clear ACK holds if lease expired
        for ((_, propEntry) in liveProposals) {
            val iter = propEntry.ackHoldStartMs.entries.iterator()
            while (iter.hasNext()) {
                val (mId, _) = iter.next()
                if (mId == normalizedLocalId) continue
                if (!isMemberLeaseFresh(mId, now)) {
                    iter.remove()
                }
            }
        }
    }

    private fun isMemberLeaseFresh(memberId: String, now: Long): Boolean {
        if (memberId == normalizedLocalId) return true
        // Receiver restart warm-up (Section 11.6)
        if (now - receiverStartedAtMs < config.epochWarmupMs) {
            val st = memberReadiness[memberId] ?: return false
            val wStart = st.warmupStartMs ?: return false
            val spanned = st.warmupCount >= 2 && ((st.lastHeardAtMs ?: wStart) - wStart) >= config.activeHelloIntervalMs
            if (!spanned) return false
        }
        val state = memberReadiness[memberId] ?: return false
        val rec = state.latestRecord ?: return false
        if (now >= state.leaseExpiresAtMs) return false
        // Check post-SEQ_RESYNC warm-up
        val wStart = state.warmupStartMs
        if (wStart != null && state.warmupCount < 2 && (now - wStart) < config.epochWarmupMs) {
            return false
        }
        return rec.hops <= config.maxHops
    }

    private fun processIncomingDecision(
        incoming: TransportDecision,
        membership: ConvoyMembershipSnapshot,
        now: Long
    ): IncomingOutcome {
        val sessionId = membership.sessionId
        if (incoming.extVersion != config.extVersion) {
            emitDiag("UNSUPPORTED_EXT_VERSION", "extVersion=${incoming.extVersion}", now)
            return IncomingOutcome.IGNORED
        }
        if (!ConvoyHash.verifyDecisionId(sessionId, incoming)) {
            emitDiag("INVALID_DECISION_ID", "gen=${incoming.generation}", now)
            return IncomingOutcome.IGNORED
        }

        // Plausibility bound when baseline exists (Section 5.3.7)
        if (!generationUnknown && incoming.generation > generationHighWater + ConvoyProtocolConfig.PLAUSIBILITY_JUMP_LIMIT) {
            emitDiag("IMPLAUSIBLE_GENERATION", "incoming=${incoming.generation} highWater=$generationHighWater", now)
            return IncomingOutcome.IGNORED
        }

        // Config version & roster mismatch gate for ONLINE decisions only (Section 4.4 & 11.1)
        if (incoming.mode == TransportMode.ONLINE) {
            if (incoming.configVersion != config.configVersion) {
                emitDiag("CONFIG_VERSION_MISMATCH", "incoming=${incoming.configVersion} local=${config.configVersion}", now)
                return IncomingOutcome.IGNORED
            }
            val rh = incoming.rosterHash8
            if (rh == null || !rh.contentEquals(membership.rosterHash8)) {
                emitDiag("MEMBERSHIP_MISMATCH", "ONLINE decision rosterHash mismatch", now)
                return IncomingOutcome.ROSTER_MISMATCH
            }
        }

        // Update high-water mark for well-formed authenticated decisions
        if (generationUnknown) {
            // Section 16.2: adopt peer's valid decision & high-water mark before proposing again
            if (incoming.mode == TransportMode.ONLINE && incoming.phase == DecisionPhase.ONLINE_COMMITTED) {
                val vr = OnlineCommitValidator.validate(incoming, membership, config)
                if (vr != CommitValidationResult.VALID) {
                    decisionConflictActive = true
                    emitDiag("COMMIT_VALIDATION_FAILED", vr.name, now)
                    return IncomingOutcome.IGNORED
                }
            }
            generationHighWater = maxOf(generationHighWater, incoming.generation)
            generationUnknown = false
            persistence?.saveGenerationHighWaterWriteAhead(sessionId, generationHighWater)
            if (incoming.phase != DecisionPhase.ONLINE_PROPOSED) {
                acceptedDecision = incoming
                if (incoming.mode == TransportMode.OFFLINE) lastOfflineDecision = incoming
                persistence?.saveAcceptedDecisionAtomically(sessionId, incoming, generationHighWater)
                return IncomingOutcome.ADOPTED
            }
        }

        if (incoming.generation > generationHighWater) {
            generationHighWater = incoming.generation
            persistence?.saveGenerationHighWaterWriteAhead(sessionId, generationHighWater)
        }

        // If ONLINE_COMMITTED, run deterministic observation-independent commit validator (Section 8.5)
        if (incoming.mode == TransportMode.ONLINE && incoming.phase == DecisionPhase.ONLINE_COMMITTED) {
            val vr = OnlineCommitValidator.validate(incoming, membership, config)
            if (vr != CommitValidationResult.VALID) {
                decisionConflictActive = true
                emitDiag("COMMIT_VALIDATION_FAILED", vr.name, now)
                return IncomingOutcome.IGNORED
            }
        }

        // Handle live ONLINE_PROPOSED proposals
        if (incoming.mode == TransportMode.ONLINE && incoming.phase == DecisionPhase.ONLINE_PROPOSED) {
            val cur = acceptedDecision
            val cmp = DecisionOrdering.compare(incoming, cur)
            if (cmp == DecisionComparison.Equivocation) {
                decisionConflictActive = true
                emitDiag("EQUIVOCATION", "Proposal equivocation at gen=${incoming.generation}", now)
                return IncomingOutcome.IGNORED
            }
            if (cmp != DecisionComparison.CandidateWins) {
                return IncomingOutcome.IGNORED
            }
            if (cur?.mode == TransportMode.OFFLINE && incoming.baseOfflineId?.contentEquals(cur.decisionId) != true) {
                return IncomingOutcome.IGNORED
            }
            val key = keyOf(incoming.decisionId)
            val existing = liveProposals[key]
            if (existing == null) {
                liveProposals[key] = LiveProposalState(incoming, now, now)
            } else {
                existing.lastSeenAtMs = now
            }
            return IncomingOutcome.PROPOSAL_TRACKED
        }

        // Compare against current accepted decision
        when (DecisionOrdering.compare(incoming, acceptedDecision)) {
            DecisionComparison.CandidateWins -> {
                decisionConflictActive = false
                acceptedDecision = incoming
                if (incoming.mode == TransportMode.OFFLINE) {
                    lastOfflineDecision = incoming
                    liveProposals.clear()
                    ownActiveProposalIdHex = null
                    ownCurrentAckFor = null
                } else if (incoming.phase == DecisionPhase.ONLINE_COMMITTED) {
                    liveProposals.clear()
                    ownActiveProposalIdHex = null
                    ownCurrentAckFor = null
                    abandonedProposalsCount = 0
                }
                persistence?.saveAcceptedDecisionAtomically(sessionId, incoming, generationHighWater)
                return IncomingOutcome.ADOPTED
            }
            DecisionComparison.Equivocation -> {
                decisionConflictActive = true
                emitDiag("EQUIVOCATION", "Decision equivocation at gen=${incoming.generation}", now)
                return IncomingOutcome.IGNORED
            }
            DecisionComparison.Identical, DecisionComparison.CurrentWins -> {
                return IncomingOutcome.IGNORED
            }
        }
    }

    private fun expireProposals(now: Long) {
        val iter = liveProposals.entries.iterator()
        while (iter.hasNext()) {
            val (key, entry) = iter.next()
            if (now - entry.lastSeenAtMs >= config.proposalTtlMs) {
                iter.remove()
                if (key == ownActiveProposalIdHex) {
                    ownActiveProposalIdHex = null
                }
                abandonedProposalsCount++
            }
        }
    }

    private fun selectWinningLiveProposal(
        activeOfflineBase: TransportDecision,
        membership: ConvoyMembershipSnapshot
    ): LiveProposalState? {
        var best: LiveProposalState? = null
        for ((_, entry) in liveProposals) {
            val p = entry.decision
            if (p.generation <= activeOfflineBase.generation) continue
            if (p.baseOfflineId?.contentEquals(activeOfflineBase.decisionId) != true) continue
            if (p.rosterHash8?.contentEquals(membership.rosterHash8) != true) continue
            if (best == null || DecisionOrdering.compare(p, best.decision) == DecisionComparison.CandidateWins) {
                best = entry
            }
        }
        return best
    }

    private fun tryBuildAndValidateCommit(
        propEntry: LiveProposalState,
        activeOfflineBase: TransportDecision,
        membership: ConvoyMembershipSnapshot,
        now: Long
    ): TransportDecision? {
        val prop = propEntry.decision
        val n = membership.meshParticipants.size
        var readyBitmap = 0
        val ackSeqs = ArrayList<Int>(n)

        for (i in 0 until n) {
            val memberId = membership.meshParticipants[i]
            val isExcluded = (prop.excludedBitmap and (1 shl i)) != 0
            if (isExcluded) {
                ackSeqs.add(0)
                continue
            }

            if (memberId == normalizedLocalId) {
                val localHoldStart = propEntry.ackHoldStartMs[normalizedLocalId] ?: return null
                if (now - localHoldStart < config.commitHoldMs) return null
                readyBitmap = readyBitmap or (1 shl i)
                ackSeqs.add(ownReadinessSeq and 0xFFFF)
            } else {
                if (!isMemberLeaseFresh(memberId, now)) return null
                val rec = memberReadiness[memberId]?.latestRecord ?: return null
                if (!rec.recoveryEligible || rec.baseGen != activeOfflineBase.generation) return null
                if (rec.ackFor?.contentEquals(prop.decisionId) != true) return null
                val holdStart = propEntry.ackHoldStartMs[memberId] ?: return null
                if (now - holdStart < config.commitHoldMs) return null
                readyBitmap = readyBitmap or (1 shl i)
                ackSeqs.add(rec.readinessSeq and 0xFFFF)
            }
        }

        val commitId = ConvoyHash.computeOnlineCommittedId(
            sessionId = membership.sessionId,
            generation = prop.generation,
            proposerTag = prop.proposerTag,
            rosterHash8 = prop.rosterHash8!!,
            baseOfflineId = prop.baseOfflineId!!,
            excludedBitmap = prop.excludedBitmap,
            manualExclusion = prop.manualExclusion,
            readyBitmap = readyBitmap,
            ackSeqs = ackSeqs
        )
        val commit = TransportDecision(
            generation = prop.generation,
            mode = TransportMode.ONLINE,
            phase = DecisionPhase.ONLINE_COMMITTED,
            proposerTag = prop.proposerTag,
            rosterHash8 = prop.rosterHash8,
            baseOfflineId = prop.baseOfflineId,
            excludedBitmap = prop.excludedBitmap,
            readyBitmap = readyBitmap,
            ackSeqs = ackSeqs,
            manualExclusion = prop.manualExclusion,
            decisionId = commitId,
            extVersion = config.extVersion,
            configVersion = config.configVersion
        )

        // Proposer self-validates before executing or advertising (Section 8.5)
        val selfCheck = OnlineCommitValidator.validate(commit, membership, config)
        if (selfCheck != CommitValidationResult.VALID) {
            decisionConflictActive = true
            emitDiag("PROPOSER_COMMIT_SELF_VALIDATION_FAILED", selfCheck.name, now)
            return null
        }
        return commit
    }

    private fun areAllRequiredMembersReady(
        membership: ConvoyMembershipSnapshot,
        excludedBitmap: Int,
        baseOfflineGen: Long,
        now: Long
    ): Boolean {
        for (i in membership.meshParticipants.indices) {
            if ((excludedBitmap and (1 shl i)) != 0) continue
            val mId = membership.meshParticipants[i]
            if (mId == normalizedLocalId) continue
            if (!isMemberLeaseFresh(mId, now)) return false
            val rec = memberReadiness[mId]?.latestRecord ?: return false
            if (!rec.recoveryEligible || rec.baseGen != baseOfflineGen) return false
        }
        return true
    }

    private fun computeValidManualExcludedBitmap(
        req: ManualExclusionRequest,
        membership: ConvoyMembershipSnapshot,
        baseOffline: TransportDecision,
        now: Long
    ): Int? {
        // Verify confirmed non-eligible list has not drifted since user confirmed (Section 9.1)
        val currentEligible = HashSet<String>()
        currentEligible.add(normalizedLocalId)
        for (i in membership.meshParticipants.indices) {
            val mId = membership.meshParticipants[i]
            if (mId == normalizedLocalId) continue
            if (isMemberLeaseFresh(mId, now)) {
                val rec = memberReadiness[mId]?.latestRecord
                if (rec != null && rec.recoveryEligible && rec.baseGen == baseOffline.generation) {
                    currentEligible.add(mId)
                }
            }
        }
        if (currentEligible != req.expectedEligibleSnapshot) {
            emitDiag("EXCLUSION_LIST_STALE", "Eligible members changed before confirmation", now)
            return null
        }

        var bitmap = 0
        for (exId in req.excludedMemberIds) {
            val normEx = ConvoyHash.normalizeNfcString(exId)
            if (normEx == normalizedLocalId) return null // proposer cannot exclude self
            val idx = membership.indexOfMeshParticipant(normEx)
            if (idx < 0) return null
            // Never exclude a member holding a live recoveryEligible record (Rule 9.3.5)
            if (normEx in currentEligible) return null
            bitmap = bitmap or (1 shl idx)
        }
        return bitmap
    }

    private fun findReachableOrKnownNonReadyMembers(
        membership: ConvoyMembershipSnapshot,
        baseOfflineGen: Long,
        now: Long
    ): List<String> {
        val list = ArrayList<String>()
        for (mId in membership.meshParticipants) {
            if (mId == normalizedLocalId) continue
            val fresh = isMemberLeaseFresh(mId, now)
            val rec = memberReadiness[mId]?.latestRecord
            val ready = fresh && rec != null && rec.recoveryEligible && rec.baseGen == baseOfflineGen
            if (!ready) list.add(mId)
        }
        return list
    }

    private fun canCreateProposalWithGuards(now: Long): Boolean {
        if ((now - lastAudioTransitionAtMs) < config.minDwellMs) return false
        pruneTransitions(now)
        return transitionHistoryMs.size < config.maxTransitionsPerWindow
    }

    private fun pruneTransitions(now: Long) {
        while (transitionHistoryMs.isNotEmpty() && (now - transitionHistoryMs.first()) > config.transitionWindowMs) {
            transitionHistoryMs.removeFirst()
        }
    }

    private fun computeConvoyModeState(): ConvoyModeState {
        if (decisionConflictActive) return ConvoyModeState.DECISION_CONFLICT
        if (membershipMismatchActive && acceptedDecision?.mode != TransportMode.OFFLINE) {
            return ConvoyModeState.MEMBERSHIP_MISMATCH
        }
        val d = acceptedDecision ?: return ConvoyModeState.ONLINE
        if (liveProposals.isNotEmpty() && d.mode == TransportMode.OFFLINE) {
            return ConvoyModeState.ONLINE_PROPOSED
        }
        return when (d.mode) {
            TransportMode.OFFLINE -> ConvoyModeState.OFFLINE
            TransportMode.ONLINE -> when (d.phase) {
                DecisionPhase.ONLINE_PROPOSED -> ConvoyModeState.ONLINE_PROPOSED
                DecisionPhase.ONLINE_COMMITTED, DecisionPhase.ADOPTED -> ConvoyModeState.ONLINE_COMMITTED
            }
        }
    }

    private fun emitDiag(code: String, detail: String, now: Long) {
        diagnosticsBuffer.add(ProtocolDiagnostic(code, detail, now))
    }

    private enum class IncomingOutcome { ADOPTED, PROPOSAL_TRACKED, IGNORED, ROSTER_MISMATCH }

    companion object {
        fun isSeqNewer16(candidate: Int, previous: Int): Boolean {
            val diff = (candidate - previous) and 0xFFFF
            return diff in 1..0x7FFF
        }

        fun keyOf(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
