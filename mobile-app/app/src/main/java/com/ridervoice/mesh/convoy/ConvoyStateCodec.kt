package com.ridervoice.mesh.convoy

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ConvoyReadinessWireRecord(
    val rosterIdx: Int,
    val senderIncarnation: Long,
    val seq: Int,
    val livekitReady: Boolean,
    val recoveryEligible: Boolean,
    val meshReady: Boolean,
    val netLost: Boolean,
    val localMode: LocalModeWire,
    val readinessSource: ReadinessSource,
    val baseGen: Long,
    val ackFor: ByteArray,
    val ageTenthsSec: Int,
    val hops: Int
) {
    init {
        require(rosterIdx in 0 until ConvoyMembershipSnapshot.MAX_MESH_PARTICIPANTS) { "Invalid rosterIdx $rosterIdx" }
        require(ackFor.size == 8) { "ackFor must be 8 bytes" }
        require(ageTenthsSec in 0..0xFFFF) { "Invalid ageTenthsSec $ageTenthsSec" }
        require(hops in 0..0xFF) { "Invalid hops $hops" }
    }

    fun toObservation(memberId: String, receivedAtMs: Long): MemberObservation = MemberObservation(
        memberId = memberId,
        lastStateAtMs = receivedAtMs,
        netState = if (netLost) NetState.SUSPECT else NetState.HEALTHY,
        livekitReadiness = if (livekitReady) LiveKitReadiness.READY else LiveKitReadiness.NOT_READY,
        recoveryEligible = recoveryEligible,
        meshReadiness = if (meshReady) MeshReadiness.ROUTE_AVAILABLE else MeshReadiness.NOT_READY,
        localExecutionState = when (localMode) {
            LocalModeWire.ONLINE_ACTIVE -> LocalTransportState.ONLINE_ACTIVE
            LocalModeWire.OFFLINE_ACTIVE -> LocalTransportState.OFFLINE_ACTIVE
            LocalModeWire.UNAVAILABLE_OR_FAILED -> LocalTransportState.RECOVERY_FAILED
            LocalModeWire.TRANSITIONING -> LocalTransportState.STARTING_OFFLINE
        },
        readinessSeq = seq and 0xFFFF,
        senderIncarnation = senderIncarnation,
        baseGen = baseGen,
        ackFor = if (ackFor.all { it == 0.toByte() }) null else ackFor.copyOf(),
        ageMs = ageTenthsSec * 100,
        hops = hops,
        readinessSource = readinessSource
    )

    override fun equals(other: Any?): Boolean =
        other is ConvoyReadinessWireRecord &&
            rosterIdx == other.rosterIdx &&
            senderIncarnation == other.senderIncarnation &&
            seq == other.seq &&
            livekitReady == other.livekitReady &&
            recoveryEligible == other.recoveryEligible &&
            meshReady == other.meshReady &&
            netLost == other.netLost &&
            localMode == other.localMode &&
            readinessSource == other.readinessSource &&
            baseGen == other.baseGen &&
            ackFor.contentEquals(other.ackFor) &&
            ageTenthsSec == other.ageTenthsSec &&
            hops == other.hops

    override fun hashCode(): Int {
        var r = rosterIdx
        r = 31 * r + senderIncarnation.hashCode()
        r = 31 * r + seq
        r = 31 * r + ackFor.contentHashCode()
        return r
    }
}

data class ConvoyStateWirePayload(
    val extVersion: Int = ConvoyProtocolConfig.EXT_VERSION,
    val configVersion: Int = ConvoyProtocolConfig.CONFIG_VERSION,
    val isSuspectBurst: Boolean = false,
    val isProposalAbort: Boolean = false,
    val decision: TransportDecision? = null,
    val records: List<ConvoyReadinessWireRecord> = emptyList()
)

sealed interface ConvoyStateDecodeResult {
    data class Ok(val payload: ConvoyStateWirePayload) : ConvoyStateDecodeResult
    data class UnsupportedVersion(val extVersion: Int) : ConvoyStateDecodeResult
    data class Malformed(val reason: String) : ConvoyStateDecodeResult
}

/**
 * Big-endian, bounds-checked wire codec for [ConvoyStateWirePayload] (`docs/protocol/convoy-switching-v1.md`).
 * Max plaintext size for 6 mesh participants = 218 B (<= 400 B MAX_CONTROL_PAYLOAD_BYTES).
 */
object ConvoyStateCodec {
    const val HEADER_LEN = 4
    const val RECORD_LEN = 27
    const val MAX_RECORDS = ConvoyMembershipSnapshot.MAX_MESH_PARTICIPANTS

    fun encode(p: ConvoyStateWirePayload): ByteArray {
        require(p.records.size <= MAX_RECORDS) { "Too many readiness records: ${p.records.size}" }
        val dec = p.decision
        val decSize = when {
            dec == null -> 0
            dec.mode == TransportMode.OFFLINE -> 21
            dec.phase == DecisionPhase.ONLINE_PROPOSED -> 38
            else -> 40 + dec.ackSeqs.size * 2
        }
        val total = HEADER_LEN + decSize + p.records.size * RECORD_LEN
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)

        buf.put((p.extVersion and 0xFF).toByte())
        buf.put((p.configVersion and 0xFF).toByte())
        buf.put((p.records.size and 0xFF).toByte())
        val pFlags = (if (dec != null) 0x01 else 0x00) or
            (if (p.isSuspectBurst) 0x02 else 0x00) or
            (if (p.isProposalAbort) 0x04 else 0x00)
        buf.put(pFlags.toByte())

        if (dec != null) {
            buf.putInt(dec.generation.toInt())
            val mpFlags = (dec.mode.wireCode and 0x01) or
                ((dec.phase.wireCode and 0x03) shl 1) or
                (if (dec.manualExclusion) 0x08 else 0x00)
            buf.put(mpFlags.toByte())
            buf.put(dec.proposerTag)
            buf.put(dec.decisionId)
            if (dec.mode == TransportMode.ONLINE) {
                buf.put(dec.rosterHash8!!)
                buf.put(dec.baseOfflineId!!)
                buf.put((dec.excludedBitmap and 0xFF).toByte())
                if (dec.phase == DecisionPhase.ONLINE_COMMITTED) {
                    buf.put((dec.readyBitmap and 0xFF).toByte())
                    buf.put((dec.ackSeqs.size and 0xFF).toByte())
                    for (s in dec.ackSeqs) {
                        buf.putShort((s and 0xFFFF).toShort())
                    }
                }
            }
        }

        for (rec in p.records) {
            buf.put((rec.rosterIdx and 0xFF).toByte())
            buf.putLong(rec.senderIncarnation)
            buf.putShort((rec.seq and 0xFFFF).toShort())
            val rFlags = (if (rec.livekitReady) 0x01 else 0x00) or
                (if (rec.recoveryEligible) 0x02 else 0x00) or
                (if (rec.meshReady) 0x04 else 0x00) or
                (if (rec.netLost) 0x08 else 0x00) or
                ((rec.localMode.wireCode and 0x03) shl 4) or
                ((rec.readinessSource.wireBit and 0x01) shl 6)
            buf.put(rFlags.toByte())
            buf.putInt(rec.baseGen.toInt())
            buf.put(rec.ackFor)
            buf.putShort((rec.ageTenthsSec and 0xFFFF).toShort())
            buf.put((rec.hops and 0xFF).toByte())
        }

        return buf.array()
    }

    fun decode(data: ByteArray, meshParticipantCount: Int = MAX_RECORDS): ConvoyStateDecodeResult {
        if (data.size < HEADER_LEN) {
            return ConvoyStateDecodeResult.Malformed("TOO_SHORT")
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val extVersion = buf.get().toInt() and 0xFF
        if (extVersion != ConvoyProtocolConfig.EXT_VERSION) {
            return ConvoyStateDecodeResult.UnsupportedVersion(extVersion)
        }
        val configVersion = buf.get().toInt() and 0xFF
        val recordCount = buf.get().toInt() and 0xFF
        val pFlags = buf.get().toInt() and 0xFF

        if (recordCount > MAX_RECORDS) {
            return ConvoyStateDecodeResult.Malformed("RECORD_COUNT_EXCEEDED")
        }
        if ((pFlags and 0xF8) != 0) {
            return ConvoyStateDecodeResult.Malformed("RESERVED_PAYLOAD_FLAGS")
        }
        val hasDecision = (pFlags and 0x01) != 0
        val isSuspectBurst = (pFlags and 0x02) != 0
        val isProposalAbort = (pFlags and 0x04) != 0

        val decision: TransportDecision? = if (hasDecision) {
            if (buf.remaining() < 21) return ConvoyStateDecodeResult.Malformed("TRUNCATED_DECISION_BASE")
            val generation = buf.int.toLong() and 0xFFFFFFFFL
            if (generation == 0L || generation > ConvoyProtocolConfig.MAX_GENERATION) {
                return ConvoyStateDecodeResult.Malformed("INVALID_GENERATION")
            }
            val mpFlags = buf.get().toInt() and 0xFF
            if ((mpFlags and 0xF0) != 0) {
                return ConvoyStateDecodeResult.Malformed("RESERVED_MODE_PHASE_FLAGS")
            }
            val mode = TransportMode.fromWireCode(mpFlags and 0x01)
                ?: return ConvoyStateDecodeResult.Malformed("BAD_MODE")
            val phase = DecisionPhase.fromWireCode((mpFlags ushr 1) and 0x03)
                ?: return ConvoyStateDecodeResult.Malformed("BAD_PHASE")
            val manualExclusion = (mpFlags and 0x08) != 0

            if (mode == TransportMode.OFFLINE && (phase != DecisionPhase.ADOPTED || manualExclusion)) {
                return ConvoyStateDecodeResult.Malformed("INVALID_OFFLINE_FLAGS")
            }
            if (mode == TransportMode.ONLINE && phase == DecisionPhase.ADOPTED) {
                return ConvoyStateDecodeResult.Malformed("INVALID_ONLINE_PHASE")
            }

            val proposerTag = ByteArray(8).also { buf.get(it) }
            val decisionId = ByteArray(8).also { buf.get(it) }

            if (mode == TransportMode.ONLINE) {
                if (buf.remaining() < 17) return ConvoyStateDecodeResult.Malformed("TRUNCATED_ONLINE_FIELDS")
                val rosterHash8 = ByteArray(8).also { buf.get(it) }
                val baseOfflineId = ByteArray(8).also { buf.get(it) }
                val excludedBitmap = buf.get().toInt() and 0xFF
                val validMask = if (meshParticipantCount >= 31) Int.MAX_VALUE else (1 shl meshParticipantCount) - 1
                if ((excludedBitmap and validMask.inv()) != 0) {
                    return ConvoyStateDecodeResult.Malformed("OUT_OF_RANGE_EXCLUDED_BITMAP")
                }

                if (phase == DecisionPhase.ONLINE_COMMITTED) {
                    if (buf.remaining() < 2) return ConvoyStateDecodeResult.Malformed("TRUNCATED_COMMIT_HEADER")
                    val readyBitmap = buf.get().toInt() and 0xFF
                    if ((readyBitmap and validMask.inv()) != 0) {
                        return ConvoyStateDecodeResult.Malformed("OUT_OF_RANGE_READY_BITMAP")
                    }
                    val ackCount = buf.get().toInt() and 0xFF
                    if (ackCount !in 1..MAX_RECORDS) {
                        return ConvoyStateDecodeResult.Malformed("INVALID_ACK_COUNT")
                    }
                    if (buf.remaining() < ackCount * 2) {
                        return ConvoyStateDecodeResult.Malformed("TRUNCATED_ACK_SEQS")
                    }
                    val ackSeqs = List(ackCount) { buf.short.toInt() and 0xFFFF }
                    TransportDecision(
                        generation = generation,
                        mode = mode,
                        phase = phase,
                        proposerTag = proposerTag,
                        rosterHash8 = rosterHash8,
                        baseOfflineId = baseOfflineId,
                        excludedBitmap = excludedBitmap,
                        readyBitmap = readyBitmap,
                        ackSeqs = ackSeqs,
                        manualExclusion = manualExclusion,
                        decisionId = decisionId,
                        extVersion = extVersion,
                        configVersion = configVersion
                    )
                } else {
                    TransportDecision(
                        generation = generation,
                        mode = mode,
                        phase = phase,
                        proposerTag = proposerTag,
                        rosterHash8 = rosterHash8,
                        baseOfflineId = baseOfflineId,
                        excludedBitmap = excludedBitmap,
                        manualExclusion = manualExclusion,
                        decisionId = decisionId,
                        extVersion = extVersion,
                        configVersion = configVersion
                    )
                }
            } else {
                TransportDecision(
                    generation = generation,
                    mode = mode,
                    phase = phase,
                    proposerTag = proposerTag,
                    decisionId = decisionId,
                    extVersion = extVersion,
                    configVersion = configVersion
                )
            }
        } else {
            null
        }

        if (buf.remaining() != recordCount * RECORD_LEN) {
            return ConvoyStateDecodeResult.Malformed("RECORD_LENGTH_MISMATCH")
        }

        val records = ArrayList<ConvoyReadinessWireRecord>(recordCount)
        for (i in 0 until recordCount) {
            val rosterIdx = buf.get().toInt() and 0xFF
            if (rosterIdx >= MAX_RECORDS || rosterIdx >= meshParticipantCount) {
                return ConvoyStateDecodeResult.Malformed("INVALID_ROSTER_IDX")
            }
            val incarnation = buf.long
            val seq = buf.short.toInt() and 0xFFFF
            val rFlags = buf.get().toInt() and 0xFF
            if ((rFlags and 0x80) != 0) {
                return ConvoyStateDecodeResult.Malformed("RESERVED_RECORD_FLAGS")
            }
            val baseGen = buf.int.toLong() and 0xFFFFFFFFL
            val ackFor = ByteArray(8).also { buf.get(it) }
            val ageTenths = buf.short.toInt() and 0xFFFF
            val hops = buf.get().toInt() and 0xFF
            records.add(
                ConvoyReadinessWireRecord(
                    rosterIdx = rosterIdx,
                    senderIncarnation = incarnation,
                    seq = seq,
                    livekitReady = (rFlags and 0x01) != 0,
                    recoveryEligible = (rFlags and 0x02) != 0,
                    meshReady = (rFlags and 0x04) != 0,
                    netLost = (rFlags and 0x08) != 0,
                    localMode = LocalModeWire.fromWireCode((rFlags ushr 4) and 0x03),
                    readinessSource = if ((rFlags and 0x40) != 0) ReadinessSource.MANUAL_ASSERT else ReadinessSource.AUTO_PROBE,
                    baseGen = baseGen,
                    ackFor = ackFor,
                    ageTenthsSec = ageTenths,
                    hops = hops
                )
            )
        }

        return ConvoyStateDecodeResult.Ok(
            ConvoyStateWirePayload(
                extVersion = extVersion,
                configVersion = configVersion,
                isSuspectBurst = isSuspectBurst,
                isProposalAbort = isProposalAbort,
                decision = decision,
                records = records
            )
        )
    }
}
