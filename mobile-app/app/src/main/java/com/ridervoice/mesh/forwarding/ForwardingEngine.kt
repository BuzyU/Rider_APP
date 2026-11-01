package com.ridervoice.mesh.forwarding

import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.DecodeResult
import com.ridervoice.mesh.protocol.DuplicatePacketCache
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.SessionManager
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Outcome of processing an incoming frame through the 18-step daisy-hop pipeline.
 */
sealed class ReceiveOutcome {
    data class Accepted(
        val packet: MeshPacket,
        val forwardedTo: List<String>
    ) : ReceiveOutcome()

    data class Dropped(val reason: DropReason) : ReceiveOutcome()

    enum class DropReason {
        DECODE_FAILED,
        SESSION_MISMATCH,
        SESSION_EXPIRED,
        SELF_ORIGINATED,
        PREVIOUS_HOP_INVALID,
        SESSION_FULL,
        STALE_TIMESTAMP,
        TTL_EXHAUSTED,
        DUPLICATE,
        MALFORMED_HELLO,
        BAD_PAYLOAD
    }
}

/**
 * Daisy-hop reception, validation, local delivery, and multi-hop forwarding engine.
 *
 * Guarantees:
 * - Original [MeshPacket.sourceId] is NEVER modified when relaying.
 * - Duplicates are suppressed via [DuplicatePacketCache] keyed by sessionId + sourceId + epoch + kind + sequence.
 * - Packets are never sent back to [MeshPacket.previousHopId] or [MeshPacket.sourceId].
 * - Only non-expired direct neighbors with valid routes receive forwarded packets.
 * - Payloads are sealed end-to-end with AES-256-GCM via [PayloadCrypto]. Relays never re-encrypt.
 */
class ForwardingEngine(
    private val selfId: String,
    private val selfName: String,
    private val sessionManager: SessionManager,
    private val graphRepository: MeshGraphRepository,
    private val routingTable: RoutingTable,
    private val duplicateCache: DuplicatePacketCache,
    private val ttlPolicy: TtlPolicy,
    private val link: MeshLink,
    private val clock: () -> Long,
    private val onLocalVoicePacket: (MeshPacket) -> Unit = {},
    private val localEpoch: Int = SecureRandom().nextInt()
) {
    private val voiceSeqGen = AtomicInteger(0)
    private val controlSeqGen = AtomicInteger(0)
    private val authKey: ByteArray = sessionManager.session.authKey()
    private val payloadKey: ByteArray = sessionManager.session.payloadKey()
    private val sessionId: String = sessionManager.session.sessionId

    companion object {
        const val DIRECT_HELLO_KIND = 100
    }

    private fun dedupKind(packet: MeshPacket): Int =
        if (packet.type == MeshPacketType.HELLO && packet.hopCount == 0) DIRECT_HELLO_KIND else packet.type.code

    var receivedVoiceCount: Long = 0L
        private set
    var forwardedVoiceCount: Long = 0L
        private set
    var droppedDuplicateCount: Long = 0L
        private set
    var droppedInvalidCount: Long = 0L
        private set
    var droppedTtlCount: Long = 0L
        private set

    /**
     * Originates a new local voice frame from this device and sends it to all direct neighbors.
     */
    fun originateVoice(pcmPayload: ByteArray): MeshPacket? {
        val now = clock()
        if (sessionManager.session.isExpired(now)) return null
        val seq = voiceSeqGen.incrementAndGet()
        val aad = PayloadCrypto.aad(
            sessionId = sessionId,
            sourceId = selfId,
            typeCode = MeshPacketType.VOICE.code,
            epoch = localEpoch,
            sequence = seq,
            captureTimestamp = now,
            audioFormat = AudioFormatId.PCM16_16K_MONO
        )
        val nonce = PayloadCrypto.nonce(localEpoch, seq, MeshPacketType.VOICE.code)
        val sealed = PayloadCrypto.seal(payloadKey, nonce, aad, pcmPayload)
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = sessionId,
            sourceId = selfId,
            sequence = seq,
            captureTimestamp = now,
            ttl = ttlPolicy.defaultTtl,
            hopCount = 0,
            previousHopId = selfId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = localEpoch,
            payload = sealed
        )
        duplicateCache.markIfNew(sessionId, selfId, seq, dedupKind(packet), localEpoch)
        val wire = MeshPacketCodec.encode(packet, authKey)
        graphRepository.expire()
        val targets = graphRepository.directNeighbors()
        for (neighbor in targets) {
            link.send(neighbor.endpoint, wire)
        }
        return packet
    }

    /**
     * Originates a periodic HELLO heartbeat and broadcasts/unicasts to known & broadcast endpoints.
     */
    fun originateHello(broadcastEndpoints: Collection<MeshEndpoint> = emptyList()): MeshPacket? {
        val now = clock()
        if (sessionManager.session.isExpired(now)) return null
        graphRepository.expire()
        val snapshot = graphRepository.currentSnapshot()
        routingTable.recompute(snapshot, now)

        val seq = controlSeqGen.incrementAndGet()
        val hello = HelloPayload(
            name = selfName,
            graphVersion = snapshot.version.toInt(),
            neighborIds = graphRepository.directNeighborIds()
        )
        val plainHello = HelloCodec.encode(hello)
        val aad = PayloadCrypto.aad(
            sessionId = sessionId,
            sourceId = selfId,
            typeCode = MeshPacketType.HELLO.code,
            epoch = localEpoch,
            sequence = seq,
            captureTimestamp = now,
            audioFormat = AudioFormatId.PCM16_16K_MONO
        )
        val nonce = PayloadCrypto.nonce(localEpoch, seq, MeshPacketType.HELLO.code)
        val sealed = PayloadCrypto.seal(payloadKey, nonce, aad, plainHello)
        val packet = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = sessionId,
            sourceId = selfId,
            sequence = seq,
            captureTimestamp = now,
            ttl = MeshConfig.HELLO_TTL,
            hopCount = 0,
            previousHopId = selfId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = localEpoch,
            payload = sealed
        )
        duplicateCache.markIfNew(sessionId, selfId, seq, dedupKind(packet), localEpoch)
        val wire = MeshPacketCodec.encode(packet, authKey)
        val endpoints = LinkedHashSet<MeshEndpoint>()
        graphRepository.directNeighbors().forEach { endpoints.add(it.endpoint) }
        endpoints.addAll(link.endpoints())
        endpoints.addAll(broadcastEndpoints)
        for (ep in endpoints) {
            link.send(ep, wire)
        }
        return packet
    }

    /**
     * Originates a BYE packet when leaving the session.
     */
    fun originateBye(): MeshPacket {
        val now = clock()
        val seq = controlSeqGen.incrementAndGet()
        val aad = PayloadCrypto.aad(
            sessionId = sessionId,
            sourceId = selfId,
            typeCode = MeshPacketType.BYE.code,
            epoch = localEpoch,
            sequence = seq,
            captureTimestamp = now,
            audioFormat = AudioFormatId.PCM16_16K_MONO
        )
        val nonce = PayloadCrypto.nonce(localEpoch, seq, MeshPacketType.BYE.code)
        val sealed = PayloadCrypto.seal(payloadKey, nonce, aad, ByteArray(0))
        val packet = MeshPacket(
            type = MeshPacketType.BYE,
            sessionId = sessionId,
            sourceId = selfId,
            sequence = seq,
            captureTimestamp = now,
            ttl = MeshConfig.HELLO_TTL,
            hopCount = 0,
            previousHopId = selfId,
            audioFormat = AudioFormatId.PCM16_16K_MONO,
            epoch = localEpoch,
            payload = sealed
        )
        duplicateCache.markIfNew(sessionId, selfId, seq, dedupKind(packet), localEpoch)
        val wire = MeshPacketCodec.encode(packet, authKey)
        for (neighbor in graphRepository.directNeighbors()) {
            link.send(neighbor.endpoint, wire)
        }
        return packet
    }

    /**
     * Processes an incoming raw [MeshFrame] through all 18 validation and forwarding steps.
     */
    fun onIncomingFrame(frame: MeshFrame): ReceiveOutcome {
        // 1-2 & 5 & 7. Validate magic, version, payload bounds, and HMAC authentication tag
        val decoded = when (val res = MeshPacketCodec.decode(frame.data, authKey)) {
            is DecodeResult.Ok -> res.packet
            is DecodeResult.Invalid -> {
                droppedInvalidCount++
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DECODE_FAILED)
            }
        }

        // 3. Validate session ID
        if (decoded.sessionId != sessionId) {
            droppedInvalidCount++
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_MISMATCH)
        }
        val now = clock()
        if (sessionManager.session.isExpired(now)) {
            droppedInvalidCount++
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_EXPIRED)
        }

        // 4. Validate source ID & previous hop ID
        if (decoded.sourceId == selfId) {
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SELF_ORIGINATED)
        }
        if (decoded.previousHopId == selfId || decoded.previousHopId.isBlank()) {
            droppedInvalidCount++
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID)
        }
        if (decoded.hopCount == 0 && decoded.previousHopId != decoded.sourceId) {
            droppedInvalidCount++
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID)
        }

        // 6. Validate packet timestamp
        if (!ttlPolicy.isTimestampFresh(decoded.captureTimestamp)) {
            droppedInvalidCount++
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.STALE_TIMESTAMP)
        }

        // 5 (strict). Payload size must match the packet type exactly, taking into account the 16-byte GCM tag.
        val payloadOk = when (decoded.type) {
            MeshPacketType.VOICE -> decoded.payload.size == MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES
            MeshPacketType.BYE -> decoded.payload.size == PayloadCrypto.TAG_LEN
            MeshPacketType.HELLO -> decoded.payload.size >= PayloadCrypto.TAG_LEN
        }
        if (!payloadOk) {
            droppedInvalidCount++
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
        }

        // 8. Check TTL > 0
        if (!ttlPolicy.isTtlValidOnReceive(decoded.ttl)) {
            droppedTtlCount++
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.TTL_EXHAUSTED)
        }

        // Session admission check (enforces 6-device cap; 7th device rejected)
        when (sessionManager.admit(decoded.sourceId)) {
            SessionManager.Admission.ADMITTED,
            SessionManager.Admission.ALREADY_MEMBER -> Unit
            SessionManager.Admission.REJECTED_FULL -> {
                droppedInvalidCount++
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_FULL)
            }
            SessionManager.Admission.REJECTED_EXPIRED -> {
                droppedInvalidCount++
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_EXPIRED)
            }
        }
        // If relayed, also verify the relay is admitted
        if (decoded.previousHopId != decoded.sourceId) {
            val relayAdmission = sessionManager.admit(decoded.previousHopId)
            if (relayAdmission == SessionManager.Admission.REJECTED_FULL ||
                relayAdmission == SessionManager.Admission.REJECTED_EXPIRED
            ) {
                droppedInvalidCount++
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_FULL)
            }
        }

        // 9-11. Check duplicate cache, drop duplicates, add new packet to cache
        if (!duplicateCache.markIfNew(decoded.sessionId, decoded.sourceId, decoded.sequence, dedupKind(decoded), decoded.epoch)) {
            droppedDuplicateCount++
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DUPLICATE)
        }

        // Expire stale neighbors before processing or selecting next hops
        if (graphRepository.expire()) {
            routingTable.recompute(graphRepository.currentSnapshot(), now)
        }

        // 12. Local delivery: Decrypt payload and dispatch locally
        val aad = PayloadCrypto.aad(
            sessionId = decoded.sessionId,
            sourceId = decoded.sourceId,
            typeCode = decoded.type.code,
            epoch = decoded.epoch,
            sequence = decoded.sequence,
            captureTimestamp = decoded.captureTimestamp,
            audioFormat = decoded.audioFormat
        )
        val nonce = PayloadCrypto.nonce(decoded.epoch, decoded.sequence, decoded.type.code)
        val plain = PayloadCrypto.open(payloadKey, nonce, aad, decoded.payload)
        if (plain == null) {
            droppedInvalidCount++
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
        }

        when (decoded.type) {
            MeshPacketType.HELLO -> {
                val hello = HelloCodec.decode(plain)
                    ?: run {
                        droppedInvalidCount++
                        return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.MALFORMED_HELLO)
                    }
                val changed = graphRepository.onHello(
                    sourceId = decoded.sourceId,
                    name = hello.name,
                    hopCount = decoded.hopCount,
                    endpoint = frame.from,
                    neighborIds = hello.neighborIds
                )
                if (changed) {
                    routingTable.recompute(graphRepository.currentSnapshot(), now)
                }
            }
            MeshPacketType.BYE -> {
                if (plain.isNotEmpty()) {
                    droppedInvalidCount++
                    return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
                }
                sessionManager.remove(decoded.sourceId)
                if (graphRepository.onBye(decoded.sourceId)) {
                    routingTable.recompute(graphRepository.currentSnapshot(), now)
                }
            }
            MeshPacketType.VOICE -> {
                if (plain.size != MeshConfig.BYTES_PER_FRAME) {
                    droppedInvalidCount++
                    return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
                }
                receivedVoiceCount++
                sessionManager.touch(decoded.sourceId)
                onLocalVoicePacket(decoded.copy(payload = plain))
            }
        }

        // 13-18. Decrease TTL, select valid next-hop neighbors, exclude incoming & source & expired, forward
        val forwardedTo = ArrayList<String>()
        if (ttlPolicy.canForwardAfterReceive(decoded.ttl) &&
            ttlPolicy.tryConsumeForwardBudget(decoded.sourceId)
        ) {
            val forwardedPacket = decoded.forwardedBy(selfId) // preserves originalSourceDeviceId and epoch
            val wire = MeshPacketCodec.encode(forwardedPacket, authKey)

            val candidateNeighbors = selectForwardNeighbors(
                sourceId = decoded.sourceId,
                previousHopId = decoded.previousHopId,
                now = now
            )
            for (neighbor in candidateNeighbors) {
                if (link.send(neighbor.endpoint, wire)) {
                    forwardedTo.add(neighbor.deviceId)
                }
            }
            if (decoded.type == MeshPacketType.VOICE && forwardedTo.isNotEmpty()) {
                forwardedVoiceCount++
            }
        }

        return ReceiveOutcome.Accepted(decoded, forwardedTo)
    }

    /**
     * Selects valid next-hop direct neighbors for forwarding:
     * - Excludes [previousHopId] (never send backward to the node that just handed us the packet)
     * - Excludes [sourceId] (never send back to the original sender)
     * - Excludes expired routes/neighbors
     * - Prefers neighbors that are the computed BFS nextHop for at least one known destination,
     *   falling back to all remaining live direct neighbors when topology knowledge is still converging.
     */
    private fun selectForwardNeighbors(
        sourceId: String,
        previousHopId: String,
        now: Long
    ): List<com.ridervoice.mesh.graph.DirectNeighbor> {
        val alreadyCovered: Set<String> =
            if (MeshConfig.SUPPRESS_REDUNDANT_RELAYS) coveredByDirectHearing(sourceId, previousHopId, now) else emptySet()
        val liveDirect = graphRepository.directNeighbors().filter { neighbor ->
            neighbor.deviceId != previousHopId &&
                neighbor.deviceId != sourceId &&
                neighbor.deviceId !in alreadyCovered &&
                (now - neighbor.lastSeen) <= MeshConfig.NEIGHBOR_TIMEOUT_MS
        }
        if (liveDirect.isEmpty()) return emptyList()

        // BFS-derived next-hop destinations
        val candidateNextHops = routingTable.routes.value.values
            .map { it.nextHop }
            .toSet()

        val matching = liveDirect.filter { it.deviceId in candidateNextHops }
        return if (matching.isNotEmpty()) {
            matching
        } else {
            // Topology knowledge is still converging; fall back to all live direct neighbors
            liveDirect
        }
    }

    /**
     * Devices that are fresh, bidirectional radio neighbors of the previous hop or of the original source.
     * They already heard this packet first-hand, so relaying it to them again only burns airtime, battery and
     * HMAC verifications. Only edges confirmed by BOTH ends are trusted, so an asymmetric or stale report can
     * never suppress a relay that is actually needed.
     */
    private fun coveredByDirectHearing(sourceId: String, previousHopId: String, now: Long): Set<String> {
        val covered = HashSet<String>()
        for (e in graphRepository.currentSnapshot().edges) {
            if (!e.bidirectional || e.expiresAt <= now) continue
            if (e.from == sourceId || e.from == previousHopId) covered.add(e.to)
            if (e.to == sourceId || e.to == previousHopId) covered.add(e.from)
        }
        return covered
    }
}
