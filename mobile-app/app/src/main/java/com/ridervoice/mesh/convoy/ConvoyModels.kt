package com.ridervoice.mesh.convoy

import java.util.UUID

fun interface MonotonicClock {
    fun nowMs(): Long
}

enum class MemberRole(val wireCode: Byte) {
    MESH_PARTICIPANT(0x01),
    NON_MESH_PARTICIPANT(0x02);

    companion object {
        fun fromWireCode(code: Byte): MemberRole? = values().firstOrNull { it.wireCode == code }
    }
}

data class ConvoyMember(
    val stableMemberId: String,
    val role: MemberRole
)

/**
 * Canonical, frozen (while offline) convoy membership snapshot.
 *
 * - Normalizes member IDs to UTF-8 NFC and rejects blank or duplicate IDs.
 * - Sorts members deterministically by unsigned UTF-8 NFC bytes.
 * - [localRevision] is strictly local UI/diagnostic metadata and is never hashed or transmitted.
 */
class ConvoyMembershipSnapshot private constructor(
    val sessionId: UUID,
    val members: List<ConvoyMember>,
    val meshParticipants: List<String>,
    val rosterHash: ByteArray,
    val rosterHash8: ByteArray,
    val localRevision: Long
) {
    val allMeshBitmapMask: Int
        get() = if (meshParticipants.size >= 31) Int.MAX_VALUE else (1 shl meshParticipants.size) - 1

    val isGroupFallbackEligible: Boolean
        get() = meshParticipants.size in 2..MAX_MESH_PARTICIPANTS

    val isMeshCapExceeded: Boolean
        get() = meshParticipants.size > MAX_MESH_PARTICIPANTS

    fun indexOfMeshParticipant(stableMemberId: String): Int {
        val norm = ConvoyHash.normalizeNfcString(stableMemberId)
        return meshParticipants.indexOf(norm)
    }

    fun isMeshParticipant(stableMemberId: String): Boolean = indexOfMeshParticipant(stableMemberId) >= 0

    override fun equals(other: Any?): Boolean =
        other is ConvoyMembershipSnapshot &&
            sessionId == other.sessionId &&
            members == other.members &&
            rosterHash.contentEquals(other.rosterHash)

    override fun hashCode(): Int = 31 * sessionId.hashCode() + rosterHash.contentHashCode()

    companion object {
        const val MAX_MESH_PARTICIPANTS = 6

        fun create(
            sessionId: UUID,
            rawMembers: List<ConvoyMember>,
            localRevision: Long = 0L
        ): ConvoyMembershipSnapshot {
            val normalized = rawMembers.map { m ->
                val normId = ConvoyHash.normalizeNfcString(m.stableMemberId)
                require(normId.isNotBlank()) { "Member stableMemberId must not be blank" }
                ConvoyMember(normId, m.role)
            }
            val seen = HashSet<String>(normalized.size)
            for (m in normalized) {
                require(seen.add(m.stableMemberId)) { "Duplicate stableMemberId in roster: ${m.stableMemberId}" }
            }
            val sorted = normalized.sortedWith { a, b ->
                ConvoyHash.compareUtf8Bytes(
                    a.stableMemberId.toByteArray(Charsets.UTF_8),
                    b.stableMemberId.toByteArray(Charsets.UTF_8)
                )
            }
            val meshIds = sorted.filter { it.role == MemberRole.MESH_PARTICIPANT }.map { it.stableMemberId }
            val fullHash = ConvoyHash.computeRosterHash(sessionId, sorted)
            val shortHash = fullHash.copyOfRange(0, 8)
            return ConvoyMembershipSnapshot(
                sessionId = sessionId,
                members = sorted,
                meshParticipants = meshIds,
                rosterHash = fullHash,
                rosterHash8 = shortHash,
                localRevision = localRevision
            )
        }
    }
}

enum class TransportMode(val wireCode: Int) {
    ONLINE(0),
    OFFLINE(1);

    companion object {
        fun fromWireCode(code: Int): TransportMode? = values().firstOrNull { it.wireCode == code }
    }
}

enum class DecisionPhase(val wireCode: Int) {
    ADOPTED(0),
    ONLINE_PROPOSED(1),
    ONLINE_COMMITTED(2);

    companion object {
        fun fromWireCode(code: Int): DecisionPhase? = values().firstOrNull { it.wireCode == code }
    }
}

/**
 * Immutable atomic convoy-wide transport decision.
 * Never merge fields across two decisions.
 */
data class TransportDecision(
    val generation: Long,
    val mode: TransportMode,
    val phase: DecisionPhase,
    val proposerTag: ByteArray,
    val rosterHash8: ByteArray? = null,
    val baseOfflineId: ByteArray? = null,
    val excludedBitmap: Int = 0,
    val readyBitmap: Int = 0,
    val ackSeqs: List<Int> = emptyList(),
    val manualExclusion: Boolean = false,
    val decisionId: ByteArray,
    val extVersion: Int = ConvoyProtocolConfig.EXT_VERSION,
    val configVersion: Int = ConvoyProtocolConfig.CONFIG_VERSION
) {
    init {
        require(generation in 1L..ConvoyProtocolConfig.MAX_GENERATION) { "Invalid generation $generation" }
        require(proposerTag.size == 8) { "proposerTag must be 8 bytes" }
        require(decisionId.size == 8) { "decisionId must be 8 bytes" }
        if (mode == TransportMode.ONLINE) {
            require(rosterHash8 != null && rosterHash8.size == 8) { "ONLINE decision requires 8-byte rosterHash8" }
            require(baseOfflineId != null && baseOfflineId.size == 8) { "ONLINE decision requires 8-byte baseOfflineId" }
        }
    }

    fun proposalId(sessionId: UUID): ByteArray {
        require(mode == TransportMode.ONLINE) { "Only ONLINE decisions have a proposalId" }
        return if (phase == DecisionPhase.ONLINE_PROPOSED) {
            decisionId.copyOf()
        } else {
            ConvoyHash.computeOnlineProposedId(
                sessionId = sessionId,
                generation = generation,
                proposerTag = proposerTag,
                rosterHash8 = rosterHash8!!,
                baseOfflineId = baseOfflineId!!,
                excludedBitmap = excludedBitmap,
                manualExclusion = manualExclusion
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TransportDecision &&
            generation == other.generation &&
            mode == other.mode &&
            phase == other.phase &&
            proposerTag.contentEquals(other.proposerTag) &&
            (rosterHash8 contentEqualsNullable other.rosterHash8) &&
            (baseOfflineId contentEqualsNullable other.baseOfflineId) &&
            excludedBitmap == other.excludedBitmap &&
            readyBitmap == other.readyBitmap &&
            ackSeqs == other.ackSeqs &&
            manualExclusion == other.manualExclusion &&
            decisionId.contentEquals(other.decisionId) &&
            extVersion == other.extVersion &&
            configVersion == other.configVersion

    override fun hashCode(): Int {
        var result = generation.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + phase.hashCode()
        result = 31 * result + proposerTag.contentHashCode()
        result = 31 * result + (rosterHash8?.contentHashCode() ?: 0)
        result = 31 * result + (baseOfflineId?.contentHashCode() ?: 0)
        result = 31 * result + excludedBitmap
        result = 31 * result + readyBitmap
        result = 31 * result + ackSeqs.hashCode()
        result = 31 * result + manualExclusion.hashCode()
        result = 31 * result + decisionId.contentHashCode()
        result = 31 * result + extVersion
        result = 31 * result + configVersion
        return result
    }
}

private infix fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null && other == null) true
    else if (this != null && other != null) this.contentEquals(other)
    else false

enum class ConvoyModeState {
    ONLINE,
    OFFLINE,
    ONLINE_PROPOSED,
    ONLINE_COMMITTED,
    MEMBERSHIP_MISMATCH,
    DECISION_CONFLICT
}

enum class LocalTransportState {
    ONLINE_ACTIVE,
    ONLINE_SUSPECT,
    STARTING_OFFLINE,
    OFFLINE_ACTIVE,
    FALLBACK_UNAVAILABLE,
    STARTING_ONLINE,
    RECOVERY_FAILED,
    VOICE_UNAVAILABLE,
    GENERATION_UNKNOWN,
    GENERATION_EXHAUSTED,
    MEMBERSHIP_MISMATCH,
    LIVEKIT_PROBE_UNAVAILABLE
}

enum class LiveKitReadiness { NOT_READY, CONNECTING, CONNECTED_UNVERIFIED, READY, FAILED }
enum class MeshReadiness { NOT_READY, DISCOVERING, ROUTE_AVAILABLE, ROUTE_UNAVAILABLE, AUDIO_READY, FAILED }
enum class NetState(val wireCode: Int) { HEALTHY(0), SUSPECT(1), LOST(2) }
enum class ReadinessSource(val wireBit: Int) { AUTO_PROBE(0), MANUAL_ASSERT(1) }

enum class LocalModeWire(val wireCode: Int) {
    ONLINE_ACTIVE(0),
    OFFLINE_ACTIVE(1),
    UNAVAILABLE_OR_FAILED(2),
    TRANSITIONING(3);

    companion object {
        fun fromWireCode(code: Int): LocalModeWire = values().firstOrNull { it.wireCode == code } ?: TRANSITIONING
        fun fromLocalTransportState(state: LocalTransportState): LocalModeWire = when (state) {
            LocalTransportState.ONLINE_ACTIVE, LocalTransportState.ONLINE_SUSPECT -> ONLINE_ACTIVE
            LocalTransportState.OFFLINE_ACTIVE -> OFFLINE_ACTIVE
            LocalTransportState.STARTING_OFFLINE, LocalTransportState.STARTING_ONLINE -> TRANSITIONING
            else -> UNAVAILABLE_OR_FAILED
        }
    }
}

data class MemberObservation(
    val memberId: String,
    val lastStateAtMs: Long?,
    val netState: NetState,
    val livekitReadiness: LiveKitReadiness,
    val recoveryEligible: Boolean,
    val meshReadiness: MeshReadiness,
    val localExecutionState: LocalTransportState,
    val readinessSeq: Int,
    val senderIncarnation: Long,
    val baseGen: Long,
    val ackFor: ByteArray? = null,
    val ageMs: Int = 0,
    val hops: Int = 0,
    val readinessSource: ReadinessSource = ReadinessSource.AUTO_PROBE
)

data class ManualExclusionRequest(
    val excludedMemberIds: Set<String>,
    val confirmedAtMs: Long,
    val expectedEligibleSnapshot: Set<String>
)

data class ConvoyProtocolConfig(
    val extVersion: Int = EXT_VERSION,
    val configVersion: Int = CONFIG_VERSION,
    val failureConfirmationMs: Long = 3_000L,
    val recoveryStabilityMs: Long = 4_000L,
    val minDwellMs: Long = 20_000L,
    val maxTransitionsPerWindow: Int = 3,
    val transitionWindowMs: Long = 120_000L,
    val readinessLeaseMs: Long = 6_000L,
    val hopPenaltyMs: Long = 500L,
    val epochWarmupMs: Long = 6_000L,
    val seqResyncMs: Long = 10 * 60_000L,
    val proposalTtlMs: Long = 6_000L,
    val commitHoldMs: Long = 1_500L,
    val proposalStaggerMs: Long = 150L,
    val refallbackCooldownMs: Long = 60_000L,
    val reachableNotReadyPromptMs: Long = 60_000L,
    val activeHelloIntervalMs: Long = 1_500L,
    val maxHops: Int = 5,
    val allowAutomaticExclusionT2: Boolean = false
) {
    init {
        // Section 15 startup invariants
        require(readinessLeaseMs >= 3 * activeHelloIntervalMs) {
            "Lease ($readinessLeaseMs) must be >= 3 * ACTIVE interval ($activeHelloIntervalMs)"
        }
        require(readinessLeaseMs > maxHops * hopPenaltyMs + activeHelloIntervalMs) {
            "Lease ($readinessLeaseMs) must exceed maxHops*HOP_PENALTY + ACTIVE interval"
        }
        require(commitHoldMs >= activeHelloIntervalMs) {
            "COMMIT_HOLD ($commitHoldMs) must be >= ACTIVE interval ($activeHelloIntervalMs)"
        }
    }

    companion object {
        const val EXT_VERSION = 1
        const val CONFIG_VERSION = 1
        const val MAX_GENERATION = 0xFFFFFFF0L
        const val PLAUSIBILITY_JUMP_LIMIT = 65_536L
        const val MAX_RETIRED_INCARNATIONS = 8
    }
}

enum class PromptKind {
    STUCK_OFFLINE_EXCLUSION,
    PROTOCOL_M_MANUAL_RETURN,
    APP_UPDATE_REQUIRED,
    MORE_THAN_SIX_PARTICIPANTS
}

sealed interface TransportAction {
    data class ExecuteFallback(val decisionId: ByteArray) : TransportAction
    data class ExecuteRecovery(val decisionId: ByteArray) : TransportAction
    data class ReportFallbackUnavailable(val reason: String) : TransportAction
    data class ReportRecoveryFailed(val reason: String) : TransportAction
    data class AdvertiseDecision(val decision: TransportDecision) : TransportAction
    data class SendControlBurst(val reason: String) : TransportAction
    data class PromptRider(val kind: PromptKind, val memberIds: List<String> = emptyList()) : TransportAction
    data object NoOp : TransportAction
}

data class ProtocolDiagnostic(
    val code: String,
    val detail: String,
    val timestampMs: Long
)

data class CoordinatorInput(
    val localMemberId: String,
    val membership: ConvoyMembershipSnapshot,
    val currentDecision: TransportDecision?,
    val observations: List<MemberObservation>,
    val localNetLost: Boolean = false,
    val localTotalLossConfirmed: Boolean,
    val localLiveKitReadiness: LiveKitReadiness = LiveKitReadiness.READY,
    val localRecoveryStable: Boolean,
    val localMeshRouteAvailable: Boolean,
    val localExecutionState: LocalTransportState = LocalTransportState.ONLINE_ACTIVE,
    val generationHighWater: Long,
    val generationUnknown: Boolean = false,
    val manualReadyAsserted: Boolean = false,
    val manualExclusionRequest: ManualExclusionRequest? = null,
    val probingUnavailable: Boolean = false,
    val incomingDecisions: List<TransportDecision> = emptyList()
)

data class CoordinatorOutput(
    val acceptedDecision: TransportDecision?,
    val decisionToAdvertise: TransportDecision? = acceptedDecision,
    val convoyModeState: ConvoyModeState,
    val actions: List<TransportAction>,
    val generationToReserve: Long?,
    val generationHighWater: Long,
    val generationExhausted: Boolean,
    val generationUnknown: Boolean,
    val ackFor: ByteArray?,
    val localRecoveryEligible: Boolean,
    val readinessSource: ReadinessSource,
    val baseOfflineGen: Long,
    val ownReadinessSeq: Int = 0,
    val diagnostics: List<ProtocolDiagnostic>
)

data class PersistedConvoyState(
    val sessionId: UUID,
    val membership: ConvoyMembershipSnapshot?,
    val acceptedDecision: TransportDecision?,
    val generationHighWater: Long,
    val generationUnknown: Boolean,
    val transitionTimestampsMs: List<Long>,
    val expiresAtMs: Long,
    val lastLocalExecutionState: LocalTransportState?
)

interface ConvoyStatePersistence {
    fun load(sessionId: UUID): PersistedConvoyState?
    fun saveGenerationHighWaterWriteAhead(sessionId: UUID, highWater: Long): Boolean
    fun saveAcceptedDecisionAtomically(sessionId: UUID, decision: TransportDecision, highWater: Long): Boolean
    fun saveSnapshot(state: PersistedConvoyState): Boolean
    fun clear(sessionId: UUID? = null)
}
