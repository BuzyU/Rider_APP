package com.ridervoice.mesh.forwarding

import androidx.annotation.VisibleForTesting
import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.audio.codec.OpusVoiceEncoder
import com.ridervoice.mesh.audio.codec.PcmVoiceEncoder
import com.ridervoice.mesh.audio.codec.VoiceEncoder
import com.ridervoice.mesh.discovery.HelloCodec
import com.ridervoice.mesh.discovery.HelloPayload
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.MeshPacketV4
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.protocol.ReplayResult
import com.ridervoice.mesh.protocol.ReplayWindow
import com.ridervoice.mesh.protocol.ShortIdDirectory
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.SessionManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Outcome of processing an incoming frame through the validation and forwarding pipeline.
 */
sealed class ReceiveOutcome {
    data class Accepted(
        val packet: MeshPacket,
        val forwardedTo: List<String>
    ) : ReceiveOutcome()

    data class Dropped(val reason: DropReason) : ReceiveOutcome()

    enum class DropReason {
        DECODE_FAILED,
        BAD_VERSION,
        SESSION_MISMATCH,
        SESSION_EXPIRED,
        SELF_ORIGINATED,
        PREVIOUS_HOP_INVALID,
        SESSION_FULL,
        STALE_TIMESTAMP,
        TTL_EXHAUSTED,
        DUPLICATE,
        MALFORMED_HELLO,
        BAD_PAYLOAD,
        BAD_FORMAT,
        UNKNOWN_SOURCE,
        OVERSIZE,
        STALE_EPOCH,
        ID_COLLISION
    }
}

/**
 * Protocol v4 Daisy-hop reception, validation, local delivery, and multi-hop forwarding engine.
 *
 * Guarantees:
 * - Original [MeshPacket.sourceId] is NEVER modified when relaying.
 * - Single AES-GCM AEAD tag authenticates payload and immutable header bytes.
 * - Relays forward in-place by rewriting only mutable hop bytes (ttlHop, prevShort) with ZERO AES operations.
 * - Duplicate suppression via bounded 128-bit [ReplayWindow] per (srcShort, epoch, type).
 * - Short IDs mapped to full UUIDs via [ShortIdDirectory].
 * - No wall clock dependence on wire.
 */
class ForwardingEngine(
    private val selfId: String,
    private val selfName: String,
    private val sessionManager: SessionManager,
    private val graphRepository: MeshGraphRepository,
    private val routingTable: RoutingTable,
    private val replayWindow: ReplayWindow = ReplayWindow(),
    private val shortIdDirectory: ShortIdDirectory = ShortIdDirectory(),
    private val ttlPolicy: TtlPolicy,
    private val link: MeshLink,
    private val clock: () -> Long,
    private val onLocalVoicePacket: (MeshPacket) -> Unit = {},
    private val localEpoch: Int = SecureRandom().nextInt(),
    private val suppressRedundantRelays: Boolean = MeshConfig.SUPPRESS_REDUNDANT_RELAYS,
    private val peerFilterPort: PeerFilterPort? = null,
    private val onlineIdentitySupplier: () -> String? = { null },
    private val monotonicClock: () -> Long = clock
) {
    constructor(
        selfId: String,
        selfName: String,
        sessionManager: SessionManager,
        graphRepository: MeshGraphRepository,
        routingTable: RoutingTable,
        ttlPolicy: TtlPolicy,
        link: MeshLink,
        clock: () -> Long,
        onLocalVoicePacket: (MeshPacket) -> Unit = {},
        localEpoch: Int = SecureRandom().nextInt(),
        suppressRedundantRelays: Boolean = MeshConfig.SUPPRESS_REDUNDANT_RELAYS,
        peerFilterPort: PeerFilterPort? = null,
        onlineIdentitySupplier: () -> String? = { null },
        monotonicClock: () -> Long = clock
    ) : this(
        selfId = selfId,
        selfName = selfName,
        sessionManager = sessionManager,
        graphRepository = graphRepository,
        routingTable = routingTable,
        replayWindow = ReplayWindow(),
        shortIdDirectory = ShortIdDirectory(),
        ttlPolicy = ttlPolicy,
        link = link,
        clock = clock,
        onLocalVoicePacket = onLocalVoicePacket,
        localEpoch = localEpoch,
        suppressRedundantRelays = suppressRedundantRelays,
        peerFilterPort = peerFilterPort,
        onlineIdentitySupplier = onlineIdentitySupplier,
        monotonicClock = monotonicClock
    )

    private val voiceSeqGen = AtomicInteger(0)
    private val controlSeqGen = AtomicInteger(0)
    private val authKey: ByteArray = sessionManager.session.authKey()
    private val payloadKey: ByteArray = sessionManager.session.payloadKey()
    private val sessionId: String = sessionManager.session.sessionId
    private val selfShort: Int = shortIdDirectory.registerSelf(selfId)

    private val pinnedEpochs = ConcurrentHashMap<Int, Int>()

    private fun dedupKind(typeCode: Int, hop: Int): Int =
        if (typeCode == MeshPacketType.HELLO.codeV4 && hop == 0) DIRECT_HELLO_KIND else typeCode

    private val receivedVoice = AtomicLong(0L)
    private val forwardedVoice = AtomicLong(0L)
    private val droppedDup = AtomicLong(0L)
    private val droppedInvalid = AtomicLong(0L)
    private val droppedBadVersion = AtomicLong(0L)
    private val droppedTtl = AtomicLong(0L)
    val lastVoiceDatagramBytes = AtomicLong(0L)
    val avgVoiceDatagramBytes = AtomicLong(0L)
    val droppedUnknownSource = AtomicLong(0L)
    val droppedOversize = AtomicLong(0L)
    val droppedStaleEpoch = AtomicLong(0L)
    val idCollision = AtomicLong(0L)
    @Volatile var onIncomingFrameUsEma: Double = 0.0
    @Volatile var originateVoiceUsEma: Double = 0.0
    @Volatile var captureToSendMs: Double = 0.0
    val epoch: Int get() = localEpoch

    var selfSupportedFormatsProvider: () -> Int = { AudioFormatId.BITMASK_ALL }
    var onFormatChangedListener: ((Int) -> Unit)? = null

    private var voiceEncoder: VoiceEncoder? = null
    private var inTalkspurt: Boolean = false
    private var lastNegotiatedFormat: Int? = null
    private var lastMembershipChangeTimeMs: Long = 0L
    private var previousKnownMemberIds: Set<String> = emptySet()

    private fun getOrCreateVoiceEncoder(fmt: Int): VoiceEncoder {
        val existing = voiceEncoder
        if (existing != null && existing.formatId == fmt) {
            return existing
        }
        val created: VoiceEncoder = if (fmt == AudioFormatId.OPUS_16K_MONO_20MS) {
            OpusVoiceEncoder()
        } else {
            PcmVoiceEncoder()
        }
        voiceEncoder = created
        return created
    }

    fun currentNegotiatedFormat(): Int? {
        val snapshot = graphRepository.snapshots.value
        val otherMembers = snapshot.nodes.filter { it.key != selfId }
        val now = monotonicClock()

        val currentMemberIds = otherMembers.keys.toSet()
        if (currentMemberIds != previousKnownMemberIds) {
            lastMembershipChangeTimeMs = now
            previousKnownMemberIds = currentMemberIds
        }

        val ownMask = selfSupportedFormatsProvider()
        if (otherMembers.isEmpty()) {
            val soloFmt = if ((ownMask and AudioFormatId.BITMASK_OPUS) != 0) {
                AudioFormatId.OPUS_16K_MONO_20MS
            } else if ((ownMask and AudioFormatId.BITMASK_PCM) != 0) {
                AudioFormatId.PCM16_16K_MONO
            } else null
            return soloFmt
        }

        val unknownMembers = otherMembers.values.filter { it.supportedFormats == null }
        val candidateFormat = if (unknownMembers.isNotEmpty()) {
            val anyRecent = unknownMembers.any { (now - it.firstSeenAt) < 3 * MeshConfig.HELLO_INTERVAL_MS }
            if (anyRecent) {
                null
            } else {
                AudioFormatId.PCM16_16K_MONO
            }
        } else {
            var intersection = ownMask
            for (m in otherMembers.values) {
                intersection = intersection and (m.supportedFormats ?: 0)
            }
            if ((intersection and AudioFormatId.BITMASK_OPUS) != 0) {
                AudioFormatId.OPUS_16K_MONO_20MS
            } else if ((intersection and AudioFormatId.BITMASK_PCM) != 0) {
                AudioFormatId.PCM16_16K_MONO
            } else {
                null
            }
        }

        if (candidateFormat == null) return null

        val effectiveFormat = if (candidateFormat == AudioFormatId.OPUS_16K_MONO_20MS) {
            if (lastNegotiatedFormat == AudioFormatId.PCM16_16K_MONO && (now - lastMembershipChangeTimeMs) < 2000L) {
                AudioFormatId.PCM16_16K_MONO
            } else {
                AudioFormatId.OPUS_16K_MONO_20MS
            }
        } else {
            candidateFormat
        }

        if (lastNegotiatedFormat != null && lastNegotiatedFormat != effectiveFormat) {
            lastNegotiatedFormat = effectiveFormat
            voiceEncoder = null
            onFormatChangedListener?.invoke(effectiveFormat)
        } else {
            lastNegotiatedFormat = effectiveFormat
        }

        return effectiveFormat
    }

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
        val droppedStaleEpoch: Long = 0L,
        val idCollision: Long = 0L,
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
        droppedBadVersion = droppedBadVersion.get(),
        lastVoiceDatagramBytes = lastVoiceDatagramBytes.get(),
        avgVoiceDatagramBytes = avgVoiceDatagramBytes.get(),
        droppedUnknownSource = droppedUnknownSource.get(),
        droppedOversize = droppedOversize.get(),
        droppedStaleEpoch = droppedStaleEpoch.get(),
        idCollision = idCollision.get(),
        captureToSendMs = captureToSendMs,
        onIncomingFrameUsEma = onIncomingFrameUsEma,
        originateVoiceUsEma = originateVoiceUsEma
    )

    /**
     * Originates a new local voice frame from this device and sends it to all direct neighbors.
     */
    fun originateVoice(pcmPayload: ByteArray, captureElapsedRealtimeNanos: Long = 0L, isGated: Boolean = false): MeshPacket? {
        val t0 = System.nanoTime()
        val now = clock()
        if (sessionManager.session.isExpired(now)) return null
        val fmt = currentNegotiatedFormat() ?: return null
        val seq = voiceSeqGen.incrementAndGet() and 0xFFFFFF

        if (isGated) {
            if (inTalkspurt) {
                inTalkspurt = false
                val marker = ByteArray(1)
                return transmitVoicePacket(marker, fmt, seq, captureElapsedRealtimeNanos, t0)
            } else {
                return null
            }
        }

        val enc = getOrCreateVoiceEncoder(fmt)
        val encoded = enc.encode(pcmPayload)

        val isSilence = encoded.size <= 2
        if (isSilence) {
            if (inTalkspurt) {
                inTalkspurt = false
                val marker = ByteArray(1)
                return transmitVoicePacket(marker, fmt, seq, captureElapsedRealtimeNanos, t0)
            } else {
                val elapsedUs = (System.nanoTime() - t0) / 1000.0
                originateVoiceUsEma = if (originateVoiceUsEma == 0.0) elapsedUs else (0.9 * originateVoiceUsEma + 0.1 * elapsedUs)
                return null
            }
        } else {
            inTalkspurt = true
            return transmitVoicePacket(encoded, fmt, seq, captureElapsedRealtimeNanos, t0)
        }
    }

    private fun transmitVoicePacket(
        payload: ByteArray,
        fmt: Int,
        seq: Int,
        captureElapsedRealtimeNanos: Long,
        t0: Long
    ): MeshPacket {
        val packet = MeshPacket(
            type = MeshPacketType.VOICE,
            sessionId = sessionId,
            sourceId = selfId,
            sourceShortId = selfShort,
            sequence = seq,
            ttl = ttlPolicy.defaultTtl,
            hopCount = 0,
            previousHopId = selfId,
            previousHopShortId = selfShort,
            audioFormat = fmt,
            epoch = localEpoch,
            payload = payload
        )
        replayWindow.accept(selfShort, localEpoch, MeshPacketType.VOICE.codeV4, seq)
        val wire = MeshPacketCodec.encode(packet, payloadKey)
        val wireLen = wire.size.toLong()
        lastVoiceDatagramBytes.set(wireLen)
        val curAvg = avgVoiceDatagramBytes.get()
        avgVoiceDatagramBytes.set(if (curAvg == 0L) wireLen else (0.9 * curAvg + 0.1 * wireLen).toLong())

        val monoNow = monotonicClock()
        val targets = graphRepository.forwardingView().neighbors.filter { (monoNow - it.lastSeen) <= MeshConfig.NEIGHBOR_TIMEOUT_MS }
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

        val seq = controlSeqGen.incrementAndGet() and 0xFFFFFF
        val onlineId = onlineIdentitySupplier()
        val onlineTag = if (onlineId != null) {
            PayloadCrypto.computeOnlineIdTag(payloadKey, onlineId)
        } else null
        val hello = HelloPayload(
            deviceId = selfId,
            name = selfName,
            graphVersion = snapshot.version.toInt(),
            neighborIds = graphRepository.directNeighborIds(),
            onlineIdTag = onlineTag,
            supportedFormats = selfSupportedFormatsProvider()
        )
        val plainHello = HelloCodec.encode(hello)
        val packet = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = sessionId,
            sourceId = selfId,
            sourceShortId = selfShort,
            sequence = seq,
            ttl = MeshConfig.HELLO_TTL,
            hopCount = 0,
            previousHopId = selfId,
            previousHopShortId = selfShort,
            audioFormat = AudioFormatId.NONE,
            epoch = localEpoch,
            payload = plainHello
        )
        replayWindow.accept(selfShort, localEpoch, DIRECT_HELLO_KIND, seq)
        val wire = MeshPacketCodec.encode(packet, payloadKey)
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
        val seq = controlSeqGen.incrementAndGet() and 0xFFFFFF
        val packet = MeshPacket(
            type = MeshPacketType.BYE,
            sessionId = sessionId,
            sourceId = selfId,
            sourceShortId = selfShort,
            sequence = seq,
            ttl = MeshConfig.HELLO_TTL,
            hopCount = 0,
            previousHopId = selfId,
            previousHopShortId = selfShort,
            audioFormat = AudioFormatId.NONE,
            epoch = localEpoch,
            payload = ByteArray(0)
        )
        replayWindow.accept(selfShort, localEpoch, MeshPacketType.BYE.codeV4, seq)
        val wire = MeshPacketCodec.encode(packet, payloadKey)
        val targets = graphRepository.directNeighbors()
        for (neighbor in targets) {
            link.send(neighbor.endpoint, wire)
        }
        return packet
    }

    /**
     * Processes an incoming raw [MeshFrame] through the validation and forwarding pipeline.
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
        if (frame.data.size < MeshPacketV4.MIN_PACKET_LEN) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DECODE_FAILED)
        }

        val vtf = frame.data[0].toInt() and 0xFF
        val version = (vtf ushr 5) and 0x07
        if (version != MeshPacketV4.VERSION) {
            droppedBadVersion.incrementAndGet()
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_VERSION)
        }

        val typeCode = (vtf ushr 3) and 0x03
        val type = MeshPacketType.fromCodeV4(typeCode)
        if (type == null) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DECODE_FAILED)
        }

        val fmt = vtf and 0x07
        val ttlHop = frame.data[1].toInt() and 0xFF
        val ttl = (ttlHop ushr 4) and 0x0F
        val hop = ttlHop and 0x0F

        val srcShort = ByteBuffer.wrap(frame.data, 2, 4).order(ByteOrder.BIG_ENDIAN).int
        val prevShort = ByteBuffer.wrap(frame.data, 6, 4).order(ByteOrder.BIG_ENDIAN).int
        val epoch = ByteBuffer.wrap(frame.data, 10, 4).order(ByteOrder.BIG_ENDIAN).int
        val seq = ((frame.data[14].toInt() and 0xFF) shl 16) or ((frame.data[15].toInt() and 0xFF) shl 8) or (frame.data[16].toInt() and 0xFF)

        val now = clock()
        if (sessionManager.session.isExpired(now)) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SESSION_EXPIRED)
        }

        // Validate source ID & previous hop ID
        if (srcShort == selfShort) {
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.SELF_ORIGINATED)
        }
        if (prevShort == selfShort) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID)
        }
        if (hop == 0 && prevShort != srcShort) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID)
        }

        // Enforce debug peer filter when configured
        if (peerFilterPort != null) {
            val prevId = shortIdDirectory.resolve(prevShort)
            if (prevId != null && !peerFilterPort.shouldAccept(prevId)) {
                droppedInvalid.incrementAndGet()
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.PREVIOUS_HOP_INVALID)
            }
        }

        // Check TTL and hop count bounds
        if (!ttlPolicy.isTtlValidOnReceive(ttl, hop)) {
            droppedTtl.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.TTL_EXHAUSTED)
        }

        // Check VOICE payload limits and format support
        if (type == MeshPacketType.VOICE) {
            if (fmt == AudioFormatId.OPUS_16K_MONO_20MS && frame.data.size > MeshConfig.MAX_VOICE_DATAGRAM_BYTES) {
                droppedOversize.incrementAndGet()
                droppedInvalid.incrementAndGet()
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.OVERSIZE)
            }
            if (fmt == AudioFormatId.PCM16_16K_MONO && frame.data.size > (MeshPacketV4.HEADER_LEN + MeshConfig.BYTES_PER_FRAME + MeshPacketV4.TAG_LEN)) {
                droppedOversize.incrementAndGet()
                droppedInvalid.incrementAndGet()
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.OVERSIZE)
            }

            val selfMask = selfSupportedFormatsProvider()
            val bit = when (fmt) {
                AudioFormatId.PCM16_16K_MONO -> AudioFormatId.BITMASK_PCM
                AudioFormatId.OPUS_16K_MONO_20MS -> AudioFormatId.BITMASK_OPUS
                else -> 0
            }
            if ((selfMask and bit) == 0) {
                droppedInvalid.incrementAndGet()
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_FORMAT)
            }
        }

        val dedupKind = dedupKind(typeCode, hop)
        // Non-mutating replay check before expensive operations
        if (replayWindow.isDuplicateOrOld(srcShort, epoch, dedupKind, seq)) {
            droppedDup.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DUPLICATE)
        }

        // Authenticate AEAD tag and decrypt payload
        val ciphertextAndTag = frame.data.copyOfRange(MeshPacketV4.HEADER_LEN, frame.data.size)
        val sourceKey = PayloadCrypto.sourceKeySpecV4(payloadKey, srcShort, epoch)
        val nonce = PayloadCrypto.nonceV4(typeCode, seq, epoch)
        val aad = PayloadCrypto.aadV4(vtf.toByte(), srcShort, epoch, seq)

        val plain = PayloadCrypto.openV4(sourceKey, nonce, aad, ciphertextAndTag)
        if (plain == null) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DECODE_FAILED)
        }

        if (type == MeshPacketType.VOICE && !plainSizeValid(fmt, plain.size)) {
            droppedInvalid.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
        }

        // Pre-validate plain formats
        var sourceFullId: String? = null
        var prevHopFullId: String = ""
        var decodedHello: HelloPayload? = null

        when (type) {
            MeshPacketType.HELLO -> {
                val h = HelloCodec.decode(plain)
                if (h == null) {
                    droppedInvalid.incrementAndGet()
                    return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.MALFORMED_HELLO)
                }
                if (MeshPacketV4.shortId(h.deviceId) != srcShort) {
                    droppedInvalid.incrementAndGet()
                    return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
                }
                val registered = shortIdDirectory.registerHello(h.deviceId, srcShort)
                if (!registered) {
                    idCollision.incrementAndGet()
                    return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.ID_COLLISION)
                }
                sourceFullId = h.deviceId
                prevHopFullId = if (hop == 0) sourceFullId else (shortIdDirectory.resolve(prevShort) ?: "")
                decodedHello = h
            }
            MeshPacketType.VOICE,
            MeshPacketType.BYE -> {
                val resolvedSrc = shortIdDirectory.resolve(srcShort)
                if (resolvedSrc == null) {
                    droppedUnknownSource.incrementAndGet()
                    droppedInvalid.incrementAndGet()
                    return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.UNKNOWN_SOURCE)
                }
                sourceFullId = resolvedSrc
                prevHopFullId = if (hop == 0) resolvedSrc else (shortIdDirectory.resolve(prevShort) ?: resolvedSrc)

                if (type == MeshPacketType.BYE && plain.isNotEmpty()) {
                    droppedInvalid.incrementAndGet()
                    return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.BAD_PAYLOAD)
                }
            }
        }

        // Epoch pinning validation
        val pinned = pinnedEpochs[srcShort]
        if (type == MeshPacketType.HELLO) {
            pinnedEpochs[srcShort] = epoch
        } else {
            if (pinned == null) {
                pinnedEpochs[srcShort] = epoch
            } else if (pinned != epoch) {
                droppedStaleEpoch.incrementAndGet()
                droppedInvalid.incrementAndGet()
                return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.STALE_EPOCH)
            }
        }

        // Session admission check
        val candidateIds = if (prevHopFullId.isNotBlank() && prevHopFullId != sourceFullId) {
            listOf(sourceFullId, prevHopFullId)
        } else {
            listOf(sourceFullId)
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

        // Accept into replay window
        val replayRes = replayWindow.accept(srcShort, epoch, dedupKind, seq)
        if (replayRes != ReplayResult.NEW) {
            droppedDup.incrementAndGet()
            return ReceiveOutcome.Dropped(ReceiveOutcome.DropReason.DUPLICATE)
        }

        // Update session manager state
        if (prevHopFullId.isNotBlank() && prevHopFullId != sourceFullId) {
            sessionManager.admitAndTouch(sourceFullId, prevHopFullId)
        } else {
            sessionManager.admitAndTouch(sourceFullId)
        }

        if (hop == 0) {
            link.noteAuthenticatedEndpoint(frame.from)
        }

        if (graphRepository.expire()) {
            routingTable.recompute(graphRepository.currentSnapshot(), now)
        }

        val forwardedTo = ArrayList<String>()

        // Earlier forwarding for VOICE: forward immediately before local delivery to minimize relay hop latency
        if (type == MeshPacketType.VOICE) {
            if (ttlPolicy.canForwardAfterReceive(ttl) &&
                ttlPolicy.tryConsumeForwardBudget(sourceFullId)
            ) {
                val forwardedWire = MeshPacketV4.rewriteMutableFieldsForForward(
                    datagram = frame.data,
                    newTtl = ttl - 1,
                    newHop = hop + 1,
                    selfShort = selfShort
                )
                val candidateNeighbors = selectForwardNeighbors(
                    sourceId = sourceFullId,
                    previousHopId = prevHopFullId,
                    now = now
                )
                for (neighbor in candidateNeighbors) {
                    if (link.send(neighbor.endpoint, forwardedWire)) {
                        forwardedTo.add(neighbor.deviceId)
                    }
                }
                if (forwardedTo.isNotEmpty()) {
                    forwardedVoice.incrementAndGet()
                }
            }
        }

        val decodedPacket = MeshPacket(
            type = type,
            sessionId = sessionId,
            sourceId = sourceFullId,
            sourceShortId = srcShort,
            sequence = seq,
            ttl = ttl,
            hopCount = hop,
            previousHopId = prevHopFullId,
            previousHopShortId = prevShort,
            audioFormat = fmt,
            epoch = epoch,
            payload = plain
        )

        when (type) {
            MeshPacketType.HELLO -> {
                val hello = decodedHello!!
                val changed = graphRepository.onHello(
                    sourceId = sourceFullId,
                    name = hello.name,
                    hopCount = hop,
                    endpoint = frame.from,
                    neighborIds = hello.neighborIds,
                    sequence = seq,
                    epoch = epoch,
                    onlineIdTag = hello.onlineIdTag,
                    supportedFormats = hello.supportedFormats
                )
                if (changed) {
                    routingTable.recompute(graphRepository.currentSnapshot(), now)
                }
            }
            MeshPacketType.BYE -> {
                val directNeighbor = graphRepository.neighbor(sourceFullId)
                val heardDirectlyRecently = directNeighbor != null && (now - directNeighbor.lastSeen) <= MeshConfig.NEIGHBOR_TIMEOUT_MS
                if (heardDirectlyRecently && hop > 0) {
                    // Ignored: cannot tear down an active direct neighbor with a relayed BYE
                } else {
                    sessionManager.remove(sourceFullId)
                    if (graphRepository.onBye(sourceFullId)) {
                        routingTable.recompute(graphRepository.currentSnapshot(), now)
                    }
                }
            }
            MeshPacketType.VOICE -> {
                receivedVoice.incrementAndGet()
                sessionManager.touch(sourceFullId)
                onLocalVoicePacket(decodedPacket)
            }
        }

        // Relay forwarding for non-VOICE
        if (type != MeshPacketType.VOICE &&
            ttlPolicy.canForwardAfterReceive(ttl) &&
            ttlPolicy.tryConsumeForwardBudget(sourceFullId)
        ) {
            val forwardedWire = MeshPacketV4.rewriteMutableFieldsForForward(
                datagram = frame.data,
                newTtl = ttl - 1,
                newHop = hop + 1,
                selfShort = selfShort
            )
            val candidateNeighbors = selectForwardNeighbors(
                sourceId = sourceFullId,
                previousHopId = prevHopFullId,
                now = now
            )
            for (neighbor in candidateNeighbors) {
                if (link.send(neighbor.endpoint, forwardedWire)) {
                    forwardedTo.add(neighbor.deviceId)
                }
            }
        }

        return ReceiveOutcome.Accepted(decodedPacket, forwardedTo)
    }

    private fun selectForwardNeighbors(
        sourceId: String,
        previousHopId: String,
        now: Long = monotonicClock()
    ): List<com.ridervoice.mesh.graph.DirectNeighbor> {
        val monoNow = now
        val view = graphRepository.forwardingView()
        val alreadyCovered: Set<String> =
            if (suppressRedundantRelays) view.coveredBy(sourceId, previousHopId, monoNow) else emptySet()
        return view.neighbors.filter { neighbor ->
            neighbor.deviceId != previousHopId &&
                neighbor.deviceId != sourceId &&
                neighbor.deviceId !in alreadyCovered &&
                (monoNow - neighbor.lastSeen) <= MeshConfig.NEIGHBOR_TIMEOUT_MS
        }
    }

    fun close() {
        authKey.fill(0)
        payloadKey.fill(0)
        PayloadCrypto.clearKeyCache()
        shortIdDirectory.clear()
        replayWindow.clear()
        pinnedEpochs.clear()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun isZeroized(): Boolean {
        return authKey.isNotEmpty() && authKey.all { it == 0.toByte() } &&
            payloadKey.isNotEmpty() && payloadKey.all { it == 0.toByte() }
    }

    companion object {
        const val DIRECT_HELLO_KIND = 100

        fun plainSizeValid(audioFormat: Int, plainSize: Int): Boolean {
            return when (audioFormat) {
                AudioFormatId.PCM16_16K_MONO -> plainSize == MeshConfig.BYTES_PER_FRAME || plainSize == 1
                AudioFormatId.OPUS_16K_MONO_20MS -> plainSize in 1..MeshConfig.MAX_OPUS_PAYLOAD_BYTES
                else -> false
            }
        }
    }
}
