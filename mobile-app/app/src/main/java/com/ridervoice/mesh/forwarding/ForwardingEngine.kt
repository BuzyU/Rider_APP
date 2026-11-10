package com.ridervoice.mesh.forwarding

import androidx.annotation.VisibleForTesting
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
import com.ridervoice.mesh.protocol.PacketIds
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.SessionManager
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val localEpoch: Int = SecureRandom().nextInt(),
    private val suppressRedundantRelays: Boolean = MeshConfig.SUPPRESS_REDUNDANT_RELAYS,
    private val peerFilterPort: PeerFilterPort? = null,
    private val onlineIdentitySupplier: () -> String? = { null }
) {
    private val voiceSeqGen = AtomicInteger(0)
    private val controlSeqGen = AtomicInteger(0)
    private val authKey: ByteArray = sessionManager.session.authKey()
    private val payloadKey: ByteArray = sessionManager.session.payloadKey()
    private val sessionId: String = sessionManager.session.sessionId
    private val sParts = MeshPacketCodec.uuidParts(sessionId)
    private val selfParts = MeshPacketCodec.uuidParts(selfId)
    private val localPacketIds = PacketIds(sParts.first, sParts.second, selfParts.first, selfParts.second)
    private val sourceKeyCache = java.util.concurrent.ConcurrentHashMap<String, javax.crypto.spec.SecretKeySpec>()

    private fun getSourcePayloadKey(srcId: String): javax.crypto.spec.SecretKeySpec =
        sourceKeyCache.computeIfAbsent(srcId) { javax.crypto.spec.SecretKeySpec(PayloadCrypto.sourceKey(payloadKey, it), "AES") }

    companion object {
        const val DIRECT_HELLO_KIND = 100
    }

    private fun dedupKind(packet: MeshPacket): Int =
        if (packet.type == MeshPacketType.HELLO && packet.hopCount == 0) DIRECT_HELLO_KIND else packet.type.code

    private val receivedVoice = AtomicLong(0L)
    private val forwardedVoice = AtomicLong(0L)
    private val droppedDup = AtomicLong(0L)
    private val droppedInvalid = AtomicLong(0L)
    private val droppedBadVersion = AtomicLong(0L)
    private val droppedTtl = AtomicLong(0L)
    private val droppedStaleTimestamp = AtomicLong(0L)
    private val suspectedClockSkewMs = AtomicLong(0L)
    @Volatile private var suspectedClockSkewPeerId: String? = null
    val lastVoiceDatagramBytes = AtomicLong(0L)
    val avgVoiceDatagramBytes = AtomicLong(0L)
    val droppedUnknownSource = AtomicLong(0L)
    val droppedOversize = AtomicLong(0L)
    @Volatile var onIncomingFrameUsEma: Double = 0.0
    @Volatile var originateVoiceUsEma: Double = 0.0
    @Volatile var captureToSendMs: Double = 0.0

    data class Counters(
        val received: Long,
        val forwarded: Long,
        val dup: Long,
        val invalid: Long,
        val ttl: Long,
        val staleTimestamp: Long = 0L,
        val suspectedClockSkewMs: Long = 0L,
        val suspectedClockSkewPeerId: String? = null,
        val droppedBadVersion: Long = 0L,
        val lastVoiceDatagramBytes: Long = 0L,
        val avgVoiceDatagramBytes: Long = 0L,
        val droppedUnknownSource: Long = 0L,
        val droppedOversize: Long = 0L,
        val captureToSendMs: Double = 0.0,
        val onIncomingFrameUsEma: Double = 0.0,
        val originateVoiceUsEma: Double = 0.0
    )
    fun counters() = Counters(
        received = receivedVoice.get(),
        forwarded = forwardedVoice.get(),
        dup = droppedDup.get(),
        invalid = droppedInvalid.get(),
        ttl = droppedTtl.get(),
        staleTimestamp = droppedStaleTimestamp.get(),
        suspectedClockSkewMs = suspectedClockSkewMs.get(),
        suspectedClockSkewPeerId = suspectedClockSkewPeerId,
        droppedBadVersion = droppedBadVersion.get(),
        lastVoiceDatagramBytes = lastVoiceDatagramBytes.get(),
        avgVoiceDatagramBytes = avgVoiceDatagramBytes.get(),
        droppedUnknownSource = droppedUnknownSource.get(),
        droppedOversize = droppedOversize.get(),
        captureToSendMs = captureToSendMs,
        onIncomingFrameUsEma = onIncomingFrameUsEma,
        originateVoiceUsEma = originateVoiceUsEma
    )

    /**
     * Originates a new local voice frame from this device and sends it to all direct neighbors.
     */
    fun originateVoice(pcmPayload: ByteArray, captureElapsedRealtimeNanos: Long = 0L): MeshPacket? {
        val t0 = System.nanoTime()
        val now = clock()
        if (sessionManager.session.isExpired(now)) return null
        val seq = voiceSeqGen.incrementAndGet()
        val aad = PayloadCrypto.aad(
            sessionHi = sParts.first,
            sessionLo = sParts.second,
            sourceHi = selfParts.first,
            sourceLo = selfParts.second,
            typeCode = MeshPacketType.VOICE.code,
            epoch = localEpoch,
            sequence = seq,
            captureTimestamp = now,
            audioFormat = AudioFormatId.PCM16_16K_MONO
        )
        val nonce = PayloadCrypto.nonce(localEpoch, seq, MeshPacketType.VOICE.code)
        val sourceKey = getSourcePayloadKey(selfId)
        val sealed = PayloadCrypto.seal(sourceKey, nonce, aad, pcmPayload)
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
            payload = sealed,
            sessionHi = sParts.first,
            sessionLo = sParts.second,
            sourceHi = selfParts.first,
            sourceLo = selfParts.second,
            prevHopHi = selfParts.first,
            prevHopLo = selfParts.second
        )
        duplicateCache.markIfNew(localPacketIds, epoch = localEpoch, kind = dedupKind(packet), seq = seq)
        val wire = MeshPacketCodec.encode(packet, authKey)
        val wireLen = wire.size.toLong()
        lastVoiceDatagramBytes.set(wireLen)
        val curAvg = avgVoiceDatagramBytes.get()
        avgVoiceDatagramBytes.set(if (curAvg == 0L) wireLen else (0.9 * curAvg + 0.1 * wireLen).toLong())

        graphRepository.expire()
        val targets = graphRepository.directNeighbors()
        var sentFirst = false
        for (neighbor in targets) {
            val sent = link.send(neighbor.endpoint, wire)
            if (sent && !sentFirst) {
                sentFirst = true
                if (captureElapsedRealtimeNanos > 0L) {
                    val sendNanos = runCatching { android.os.SystemClock.elapsedRealtimeNanos() }.getOrNull()?.takeIf { it != 0L } ?: System.nanoTime()
                    val diffMs = (sendNanos - captureElapsedRealtimeNanos) / 1_000_000.0
                    if (diffMs >= 0.0) {
                        captureToSendMs = if (captureToSendMs == 0.0) diffMs else (0.9 * captureToSendMs + 0.1 * diffMs)
                    }
                }
            }
        }
        val elapsedUs = (System.nanoTime() - t0) / 1000.0
        originateVoiceUsEma = if (originateVoiceUsEma == 0.0) elapsedUs else (0.9 * originateVoiceUsEma + 0.1 * elapsedUs)
        return packet
    }

    /**
     * Originates a HELLO discovery heartbeat and broadcasts it to all known endpoints and broadcast addresses.
     */
    fun originateHello(broadcastEndpoints: Collection<MeshEndpoint> = emptyList()): MeshPacket? {
        val now = clock()
        if (sessionManager.session.isExpired(now)) return null
        graphRepository.expire()
        val snapshot = graphRepository.currentSnapshot()
        routingTable.recompute(snapshot, now)

        val seq = controlSeqGen.incrementAndGet()
        val onlineId = onlineIdentitySupplier()
        val onlineTag = if (onlineId != null) {
            PayloadCrypto.computeOnlineIdTag(payloadKey, onlineId)
        } else null
        val hello = HelloPayload(
            name = selfName,
            graphVersion = snapshot.version.toInt(),
            neighborIds = graphRepository.directNeighborIds(),
            onlineIdTag = onlineTag
        )
        val plainHello = HelloCodec.encode(hello)
        val aad = PayloadCrypto.aad(
            sessionHi = sParts.first,
            sessionLo = sParts.second,
            sourceHi = selfParts.first,
            sourceLo = selfParts.second,
            typeCode = MeshPacketType.HELLO.code,
            epoch = localEpoch,
            sequence = seq,
            captureTimestamp = now,
            audioFormat = AudioFormatId.PCM16_16K_MONO
        )
        val nonce = PayloadCrypto.nonce(localEpoch, seq, MeshPacketType.HELLO.code)
        val sourceKey = getSourcePayloadKey(selfId)
        val sealed = PayloadCrypto.seal(sourceKey, nonce, aad, plainHello)
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
            payload = sealed,
            sessionHi = sParts.first,
            sessionLo = sParts.second,
            sourceHi = selfParts.first,
            sourceLo = selfParts.second,
            prevHopHi = selfParts.first,
            prevHopLo = selfParts.second
        )
        duplicateCache.markIfNew(localPacketIds, epoch = localEpoch, kind = dedupKind(packet), seq = seq)
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
            sessionHi = sParts.first,
            sessionLo = sParts.second,
            sourceHi = selfParts.first,
            sourceLo = selfParts.second,
            typeCode = MeshPacketType.BYE.code,
            epoch = localEpoch,
            sequence = seq,
            captureTimestamp = now,
            audioFormat = AudioFormatId.PCM16_16K_MONO
        )
        val nonce = PayloadCrypto.nonce(localEpoch, seq, MeshPacketType.BYE.code)
        val sourceKey = getSourcePayloadKey(selfId)
        val sealed = PayloadCrypto.seal(sourceKey, nonce, aad, ByteArray(0))
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
            payload = sealed,
            sessionHi = sParts.first,
            sessionLo = sParts.second,
            sourceHi = selfParts.first,
            sourceLo = selfParts.second,
            prevHopHi = selfParts.first,
            prevHopLo = selfParts.second
        )
        duplicateCache.markIfNew(localPacketIds, epoch = localEpoch, kind = dedupKind(packet), seq = seq)
        val wire = MeshPacketCodec.encode(packet, authKey)
        val targets = graphRepository.directNeighbors()
        for (neighbor in targets) {
            link.send(neighbor.endpoint, wire)
        }
        return packet
    }

    /**
     * Processes an incoming raw [MeshFrame] through all 18 validation and forwarding steps.
     */
    fun onIncomingFrame(frame: MeshFrame): ReceiveOutcome {
        val t0 = System.nanoTime()
        try {
            return processIncomingFrame(frame)
        } finally {
            val elapsedUs = (System.nanoTime() - t0) / 1000.0
            onIncomingFrameUsEma = if (onIncomingFrameUsEma == 0.0) elapsedUs else (0.9 * onIncomingFrameUsEma + 0.1 * elapsedUs)
        }
    }

    private fun processIncomingFrame(frame: MeshFrame): ReceiveOutcome {
        // 1-2 & 5 & 7. Validate magic, version, payload bounds, and HMAC authentication tag
        val decoded = when (val res = MeshPacketCodec.decode(frame.data, authKey)) {
            is DecodeResult.Ok -> res.packet
            is DecodeResult.Invalid -> {
                if (res.reason == DecodeResult.Reason.BAD_VERSION) {
                    droppedBadVersion.incrementAndGet()
                }
                droppedInvalid.incrementAndGet()
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DECODE_FAILED)
            }
        }

        // 3. Validate session ID
        if (decoded.sessionId != sessionId) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_MISMATCH)
        }
        val now = clock()
        if (sessionManager.session.isExpired(now)) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_EXPIRED)
        }

        // 4. Validate source ID & previous hop ID
        if (decoded.sourceId == selfId) {
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SELF_ORIGINATED)
        }
        if (decoded.previousHopId == selfId || decoded.previousHopId.isBlank()) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID)
        }
        if (decoded.hopCount == 0 && decoded.previousHopId != decoded.sourceId) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID)
        }

        // Enforce debug peer filter when configured in debug builds / test harnesses
        if (peerFilterPort != null && !peerFilterPort.shouldAccept(decoded.previousHopId)) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID)
        }

        // 6. Validate packet timestamp
        if (!ttlPolicy.isTimestampFresh(decoded.captureTimestamp)) {
            val skew = Math.abs(now - decoded.captureTimestamp)
            droppedStaleTimestamp.incrementAndGet()
            suspectedClockSkewMs.set(skew)
            suspectedClockSkewPeerId = decoded.sourceId
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.STALE_TIMESTAMP)
        }

        // 5 (strict). Payload size must match the packet type exactly, taking into account the 16-byte GCM tag.
        val payloadOk = when (decoded.type) {
            MeshPacketType.VOICE -> decoded.payload.size == MeshConfig.ENCRYPTED_VOICE_PAYLOAD_BYTES
            MeshPacketType.BYE -> decoded.payload.size == PayloadCrypto.TAG_LEN
            MeshPacketType.HELLO -> decoded.payload.size >= PayloadCrypto.TAG_LEN
        }
        if (!payloadOk) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
        }

        // 8. Check TTL > 0 and hop count bounds
        if (!ttlPolicy.isTtlValidOnReceive(decoded.ttl, decoded.hopCount)) {
            droppedTtl.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.TTL_EXHAUSTED)
        }

        // Fast non-mutating duplicate check BEFORE decrypting
        if (duplicateCache.isDuplicate(decoded.packetIds, epoch = decoded.epoch, kind = dedupKind(decoded), seq = decoded.sequence)) {
            droppedDup.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DUPLICATE)
        }

        // Non-mutating joint session admission check (source + relay must both fit within 6-device cap)
        val candidateIds = if (decoded.previousHopId != decoded.sourceId) {
            listOf(decoded.sourceId, decoded.previousHopId)
        } else {
            listOf(decoded.sourceId)
        }
        when (sessionManager.canAdmitAll(candidateIds)) {
            SessionManager.Admission.ADMITTED,
            SessionManager.Admission.ALREADY_MEMBER -> Unit
            SessionManager.Admission.REJECTED_FULL -> {
                droppedInvalid.incrementAndGet()
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_FULL)
            }
            SessionManager.Admission.REJECTED_EXPIRED -> {
                droppedInvalid.incrementAndGet()
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_EXPIRED)
            }
        }

        // Expire stale neighbors before processing or selecting next hops
        if (graphRepository.expire()) {
            routingTable.recompute(graphRepository.currentSnapshot(), now)
        }

        // 12. Local delivery: Decrypt payload and dispatch locally
        val aad = PayloadCrypto.aad(
            sessionHi = decoded.effectiveSessionHi,
            sessionLo = decoded.effectiveSessionLo,
            sourceHi = decoded.effectiveSourceHi,
            sourceLo = decoded.effectiveSourceLo,
            typeCode = decoded.type.code,
            epoch = decoded.epoch,
            sequence = decoded.sequence,
            captureTimestamp = decoded.captureTimestamp,
            audioFormat = decoded.audioFormat
        )
        val nonce = PayloadCrypto.nonce(decoded.epoch, decoded.sequence, decoded.type.code)
        val sourceKey = getSourcePayloadKey(decoded.sourceId)
        val plain = PayloadCrypto.open(sourceKey, nonce, aad, decoded.payload)
        if (plain == null) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
        }

        // Pre-validate plain format before state mutations
        val decodedHello = if (decoded.type == MeshPacketType.HELLO) {
            val h = HelloCodec.decode(plain)
            if (h == null) {
                droppedInvalid.incrementAndGet()
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.MALFORMED_HELLO)
            }
            h
        } else null

        if (decoded.type == MeshPacketType.VOICE && plain.size != MeshConfig.BYTES_PER_FRAME) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
        }
        if (decoded.type == MeshPacketType.BYE && plain.isNotEmpty()) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
        }

        // ONLY AFTER AEAD decryption and payload format validation succeed:
        if (!duplicateCache.markIfNew(decoded.packetIds, epoch = decoded.epoch, kind = dedupKind(decoded), seq = decoded.sequence)) {
            droppedDup.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DUPLICATE)
        }
        sessionManager.admit(decoded.sourceId)
        if (decoded.previousHopId != decoded.sourceId) {
            sessionManager.admit(decoded.previousHopId)
        }

        if (decoded.hopCount == 0) {
            link.noteAuthenticatedEndpoint(frame.from)
        }

        when (decoded.type) {
            MeshPacketType.HELLO -> {
                val hello = decodedHello!!
                val changed = graphRepository.onHello(
                    sourceId = decoded.sourceId,
                    name = hello.name,
                    hopCount = decoded.hopCount,
                    endpoint = frame.from,
                    neighborIds = hello.neighborIds,
                    sequence = decoded.sequence,
                    epoch = decoded.epoch,
                    onlineIdTag = hello.onlineIdTag
                )
                if (changed) {
                    routingTable.recompute(graphRepository.currentSnapshot(), now)
                }
            }
            MeshPacketType.BYE -> {
                val directNeighbor = graphRepository.neighbor(decoded.sourceId)
                val heardDirectlyRecently = directNeighbor != null && (now - directNeighbor.lastSeen) <= MeshConfig.NEIGHBOR_TIMEOUT_MS
                if (heardDirectlyRecently && decoded.hopCount > 0) {
                    // Ignored: cannot tear down an active direct neighbor with a relayed BYE
                } else {
                    sessionManager.remove(decoded.sourceId)
                    if (graphRepository.onBye(decoded.sourceId)) {
                        routingTable.recompute(graphRepository.currentSnapshot(), now)
                    }
                }
            }
            MeshPacketType.VOICE -> {
                receivedVoice.incrementAndGet()
                sessionManager.touch(decoded.sourceId)
                onLocalVoicePacket(decoded.copy(payload = plain))
            }
        }

        // 13-18. Decrease TTL, select valid next-hop neighbors, exclude incoming & source & expired, forward
        val forwardedTo = ArrayList<String>()
        if (ttlPolicy.canForwardAfterReceive(decoded.ttl) &&
            ttlPolicy.tryConsumeForwardBudget(decoded.sourceId)
        ) {
            val forwardedPacket = decoded.forwardedBy(selfId, selfParts.first, selfParts.second) // preserves originalSourceDeviceId and epoch
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
                forwardedVoice.incrementAndGet()
            }
        }

        return ReceiveOutcome.Accepted(decoded, forwardedTo)
    }

    /**
     * Controlled flooding next-hop selection:
     * - Excludes [previousHopId] (never send backward to the node that just handed us the packet)
     * - Excludes [sourceId] (never send back to the original sender)
     * - Excludes expired routes/neighbors
     * - Excludes neighbors that have already heard this packet directly when [suppressRedundantRelays] is enabled
     */
    private fun selectForwardNeighbors(
        sourceId: String,
        previousHopId: String,
        now: Long
    ): List<com.ridervoice.mesh.graph.DirectNeighbor> {
        val alreadyCovered: Set<String> =
            if (suppressRedundantRelays) coveredByDirectHearing(sourceId, previousHopId, now) else emptySet()
        return graphRepository.directNeighbors().filter { neighbor ->
            neighbor.deviceId != previousHopId &&
                neighbor.deviceId != sourceId &&
                neighbor.deviceId !in alreadyCovered &&
                (now - neighbor.lastSeen) <= MeshConfig.NEIGHBOR_TIMEOUT_MS
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

    fun close() {
        authKey.fill(0)
        payloadKey.fill(0)
        for (k in sourceKeyCache.values) {
            try { k.encoded.fill(0) } catch (_: Exception) {}
        }
        sourceKeyCache.clear()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun isZeroized(): Boolean {
        return authKey.isNotEmpty() && authKey.all { it == 0.toByte() } &&
               payloadKey.isNotEmpty() && payloadKey.all { it == 0.toByte() } &&
               sourceKeyCache.isEmpty()
    }
}
