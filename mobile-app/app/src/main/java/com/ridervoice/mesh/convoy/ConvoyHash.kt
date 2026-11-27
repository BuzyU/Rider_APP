package com.ridervoice.mesh.convoy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

/**
 * Canonical domain-separated SHA-256 hashes, deterministic ordering, and observation-independent
 * commit validation for Convoy-Wide Voice Switching (v7).
 */
object ConvoyHash {

    private val TAG_PROPOSER = "rvtag/v1".toByteArray(Charsets.US_ASCII)
    private val TAG_DEC_OFFLINE = "rvdec/v1/offline".toByteArray(Charsets.US_ASCII)
    private val TAG_DEC_ONLINE_PROPOSED = "rvdec/v1/online_proposed".toByteArray(Charsets.US_ASCII)
    private val TAG_DEC_ONLINE_COMMITTED = "rvdec/v1/online_committed".toByteArray(Charsets.US_ASCII)

    fun stableSessionUuid(roomId: String): UUID = try {
        UUID.fromString(roomId.trim())
    } catch (_: IllegalArgumentException) {
        UUID.nameUUIDFromBytes(normalizeNfcString(roomId).toByteArray(Charsets.UTF_8))
    }

    fun normalizeNfcString(input: String): String =
        Normalizer.normalize(input.trim(), Normalizer.Form.NFC)

    fun compareUtf8Bytes(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai != bi) return ai - bi
        }
        return a.size - b.size
    }

    fun compareUnsignedBytes(a: ByteArray, b: ByteArray): Int = compareUtf8Bytes(a, b)

    private fun uuidBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    fun computeRosterHash(sessionId: UUID, sortedCanonicalMembers: List<ConvoyMember>): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(0x01.toByte())
        md.update(uuidBytes(sessionId))
        md.update(sortedCanonicalMembers.size.toByte())
        val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        for (m in sortedCanonicalMembers) {
            val idBytes = normalizeNfcString(m.stableMemberId).toByteArray(Charsets.UTF_8)
            lenBuf.clear()
            lenBuf.putShort(idBytes.size.toShort())
            md.update(lenBuf.array())
            md.update(idBytes)
            md.update(m.role.wireCode)
        }
        return md.digest()
    }

    fun computeProposerTag(sessionId: UUID, stableMemberId: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(TAG_PROPOSER)
        md.update(uuidBytes(sessionId))
        md.update(normalizeNfcString(stableMemberId).toByteArray(Charsets.UTF_8))
        return md.digest().copyOfRange(0, 8)
    }

    fun computeOfflineDecisionId(
        sessionId: UUID,
        generation: Long,
        proposerTag: ByteArray
    ): ByteArray {
        require(proposerTag.size == 8)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(TAG_DEC_OFFLINE)
        md.update(uuidBytes(sessionId))
        val hdr = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putInt(generation.toInt())
            .put(TransportMode.OFFLINE.wireCode.toByte())
            .put(DecisionPhase.ADOPTED.wireCode.toByte())
            .array()
        md.update(hdr)
        md.update(proposerTag)
        return md.digest().copyOfRange(0, 8)
    }

    fun computeOnlineProposedId(
        sessionId: UUID,
        generation: Long,
        proposerTag: ByteArray,
        rosterHash8: ByteArray,
        baseOfflineId: ByteArray,
        excludedBitmap: Int,
        manualExclusion: Boolean
    ): ByteArray {
        require(proposerTag.size == 8 && rosterHash8.size == 8 && baseOfflineId.size == 8)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(TAG_DEC_ONLINE_PROPOSED)
        md.update(uuidBytes(sessionId))
        val hdr = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putInt(generation.toInt())
            .put(TransportMode.ONLINE.wireCode.toByte())
            .put(DecisionPhase.ONLINE_PROPOSED.wireCode.toByte())
            .array()
        md.update(hdr)
        md.update(proposerTag)
        md.update(rosterHash8)
        md.update(baseOfflineId)
        md.update(excludedBitmap.toByte())
        md.update(if (manualExclusion) 0x01.toByte() else 0x00.toByte())
        return md.digest().copyOfRange(0, 8)
    }

    fun computeOnlineCommittedId(
        sessionId: UUID,
        generation: Long,
        proposerTag: ByteArray,
        rosterHash8: ByteArray,
        baseOfflineId: ByteArray,
        excludedBitmap: Int,
        manualExclusion: Boolean,
        readyBitmap: Int,
        ackSeqs: List<Int>
    ): ByteArray {
        require(proposerTag.size == 8 && rosterHash8.size == 8 && baseOfflineId.size == 8)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(TAG_DEC_ONLINE_COMMITTED)
        md.update(uuidBytes(sessionId))
        val hdr = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putInt(generation.toInt())
            .put(TransportMode.ONLINE.wireCode.toByte())
            .put(DecisionPhase.ONLINE_COMMITTED.wireCode.toByte())
            .array()
        md.update(hdr)
        md.update(proposerTag)
        md.update(rosterHash8)
        md.update(baseOfflineId)
        md.update(excludedBitmap.toByte())
        md.update(if (manualExclusion) 0x01.toByte() else 0x00.toByte())
        md.update(readyBitmap.toByte())
        md.update(ackSeqs.size.toByte())
        val seqBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        for (s in ackSeqs) {
            seqBuf.clear()
            seqBuf.putShort(s.toShort())
            md.update(seqBuf.array())
        }
        return md.digest().copyOfRange(0, 8)
    }

    fun verifyDecisionId(sessionId: UUID, d: TransportDecision): Boolean {
        val expected = when (d.mode) {
            TransportMode.OFFLINE -> {
                if (d.phase != DecisionPhase.ADOPTED) return false
                computeOfflineDecisionId(sessionId, d.generation, d.proposerTag)
            }
            TransportMode.ONLINE -> when (d.phase) {
                DecisionPhase.ONLINE_PROPOSED -> computeOnlineProposedId(
                    sessionId, d.generation, d.proposerTag,
                    d.rosterHash8 ?: return false,
                    d.baseOfflineId ?: return false,
                    d.excludedBitmap, d.manualExclusion
                )
                DecisionPhase.ONLINE_COMMITTED -> computeOnlineCommittedId(
                    sessionId, d.generation, d.proposerTag,
                    d.rosterHash8 ?: return false,
                    d.baseOfflineId ?: return false,
                    d.excludedBitmap, d.manualExclusion, d.readyBitmap, d.ackSeqs
                )
                DecisionPhase.ADOPTED -> return false
            }
        }
        return d.decisionId.contentEquals(expected)
    }
}

sealed interface DecisionComparison {
    data object CandidateWins : DecisionComparison
    data object CurrentWins : DecisionComparison
    data object Identical : DecisionComparison
    data object Equivocation : DecisionComparison
}

/**
 * Total deterministic ordering for valid decisions (Section 5.4):
 * 1. Higher generation wins.
 * 2. Equal generation: OFFLINE beats ONLINE.
 * 3. Equal gen + ONLINE: ONLINE_COMMITTED beats ONLINE_PROPOSED.
 * 4. Equal gen + mode + phase: smaller unsigned proposerTag wins.
 * 5. Equal gen + mode + phase + proposerTag + different decisionId => Equivocation.
 */
object DecisionOrdering {
    fun compare(candidate: TransportDecision, current: TransportDecision?): DecisionComparison {
        if (current == null) return DecisionComparison.CandidateWins
        if (candidate.decisionId.contentEquals(current.decisionId) &&
            candidate.generation == current.generation &&
            candidate.mode == current.mode &&
            candidate.phase == current.phase &&
            candidate.proposerTag.contentEquals(current.proposerTag)
        ) {
            return DecisionComparison.Identical
        }

        if (candidate.generation != current.generation) {
            return if (candidate.generation > current.generation) {
                DecisionComparison.CandidateWins
            } else {
                DecisionComparison.CurrentWins
            }
        }

        if (candidate.mode != current.mode) {
            return if (candidate.mode == TransportMode.OFFLINE) {
                DecisionComparison.CandidateWins
            } else {
                DecisionComparison.CurrentWins
            }
        }

        if (candidate.mode == TransportMode.ONLINE && candidate.phase != current.phase) {
            return if (candidate.phase == DecisionPhase.ONLINE_COMMITTED) {
                DecisionComparison.CandidateWins
            } else {
                DecisionComparison.CurrentWins
            }
        }

        val tagCmp = ConvoyHash.compareUnsignedBytes(candidate.proposerTag, current.proposerTag)
        if (tagCmp != 0) {
            return if (tagCmp < 0) DecisionComparison.CandidateWins else DecisionComparison.CurrentWins
        }

        // Same generation, mode, phase, and proposerTag with different decisionId => equivocation
        return DecisionComparison.Equivocation
    }
}

enum class CommitValidationResult {
    VALID,
    INVALID_MODE_OR_PHASE,
    INVALID_DECISION_ID,
    INVALID_VERSION,
    ROSTER_MISMATCH,
    UNKNOWN_PROPOSER,
    PROPOSER_EXCLUDED_OR_NOT_READY,
    INVALID_BITMAPS,
    INVALID_ACK_SEQS_LENGTH,
    EXCLUSION_POLICY_VIOLATION
}

/**
 * Observation-independent, deterministic validation of an ONLINE_COMMITTED decision (Section 8.5 & 9.3).
 * Uses ONLY the commit fields, canonical membership snapshot, and protocol config.
 */
object OnlineCommitValidator {
    fun validate(
        commit: TransportDecision,
        membership: ConvoyMembershipSnapshot,
        config: ConvoyProtocolConfig
    ): CommitValidationResult {
        if (commit.mode != TransportMode.ONLINE || commit.phase != DecisionPhase.ONLINE_COMMITTED) {
            return CommitValidationResult.INVALID_MODE_OR_PHASE
        }
        if (commit.extVersion != config.extVersion || commit.configVersion != config.configVersion) {
            return CommitValidationResult.INVALID_VERSION
        }
        val rh = commit.rosterHash8 ?: return CommitValidationResult.ROSTER_MISMATCH
        if (!rh.contentEquals(membership.rosterHash8)) {
            return CommitValidationResult.ROSTER_MISMATCH
        }
        if (!ConvoyHash.verifyDecisionId(membership.sessionId, commit)) {
            return CommitValidationResult.INVALID_DECISION_ID
        }

        val n = membership.meshParticipants.size
        if (n == 0 || n > ConvoyMembershipSnapshot.MAX_MESH_PARTICIPANTS) {
            return CommitValidationResult.INVALID_BITMAPS
        }
        val validMask = membership.allMeshBitmapMask
        if ((commit.readyBitmap and validMask.inv()) != 0 || (commit.excludedBitmap and validMask.inv()) != 0) {
            return CommitValidationResult.INVALID_BITMAPS
        }
        if ((commit.readyBitmap and commit.excludedBitmap) != 0) {
            return CommitValidationResult.INVALID_BITMAPS
        }
        if ((commit.readyBitmap or commit.excludedBitmap) != validMask) {
            return CommitValidationResult.INVALID_BITMAPS
        }

        val proposerIdx = membership.meshParticipants.indexOfFirst { id ->
            ConvoyHash.computeProposerTag(membership.sessionId, id).contentEquals(commit.proposerTag)
        }
        if (proposerIdx < 0) {
            return CommitValidationResult.UNKNOWN_PROPOSER
        }
        val proposerBit = 1 shl proposerIdx
        if ((commit.readyBitmap and proposerBit) == 0 || (commit.excludedBitmap and proposerBit) != 0) {
            return CommitValidationResult.PROPOSER_EXCLUDED_OR_NOT_READY
        }

        if (commit.ackSeqs.size != n) {
            return CommitValidationResult.INVALID_ACK_SEQS_LENGTH
        }

        if (!commit.manualExclusion) {
            if (!config.allowAutomaticExclusionT2) {
                if (commit.excludedBitmap != 0) {
                    return CommitValidationResult.EXCLUSION_POLICY_VIOLATION
                }
            } else {
                val excludedCount = Integer.bitCount(commit.excludedBitmap)
                if (excludedCount * 2 >= n) {
                    return CommitValidationResult.EXCLUSION_POLICY_VIOLATION
                }
            }
        }

        return CommitValidationResult.VALID
    }
}
