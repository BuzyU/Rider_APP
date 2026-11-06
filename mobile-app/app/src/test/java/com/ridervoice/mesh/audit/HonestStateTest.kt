package com.ridervoice.mesh.audit

import android.media.AudioRecord
import com.ridervoice.mesh.MeshConfig
import com.ridervoice.mesh.audio.AudioRecordSource
import com.ridervoice.mesh.audio.OfflineAudioCapture
import com.ridervoice.mesh.audio.PlayoutFrame
import com.ridervoice.mesh.audio.MultiSourceJitterBufferManager
import com.ridervoice.mesh.audio.SourceJitterBuffer
import com.ridervoice.mesh.graph.MeshGraphRepository
import com.ridervoice.mesh.graph.MeshGraphSnapshot
import com.ridervoice.mesh.identity.MeshIdentity
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import com.ridervoice.mesh.link.MeshLink
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.protocol.AudioFormatId
import com.ridervoice.mesh.protocol.MeshPacket
import com.ridervoice.mesh.protocol.MeshPacketCodec
import com.ridervoice.mesh.protocol.MeshPacketType
import com.ridervoice.mesh.protocol.PayloadCrypto
import com.ridervoice.mesh.routing.RouteCalculator
import com.ridervoice.mesh.routing.RoutingTable
import com.ridervoice.mesh.session.OfflineSession
import com.ridervoice.mesh.transport.AudioOwner
import com.ridervoice.mesh.transport.AudioOwnershipArbiter
import com.ridervoice.mesh.transport.MicState
import com.ridervoice.mesh.transport.OfflineMeshVoiceTransport
import com.ridervoice.mesh.transport.UnifiedVoicePeer
import com.ridervoice.mesh.transport.VoiceSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class HonestStateTest {

    private class FakeLink : MeshLink {
        override val linkId: String = "fake"
        private val _state = MutableStateFlow(MeshLinkState.RUNNING)
        override val state: StateFlow<MeshLinkState> = _state
        override val incomingFrames: Flow<MeshFrame> = MutableSharedFlow()
        val sent = CopyOnWriteArrayList<Pair<MeshEndpoint, ByteArray>>()

        override fun send(endpoint: MeshEndpoint, payload: ByteArray): Boolean {
            sent.add(endpoint to payload)
            return true
        }

        override fun endpoints(): Set<MeshEndpoint> = emptySet()
        override suspend fun start() {}
        override suspend fun stop() { _state.value = MeshLinkState.STOPPED }
    }

    private class FakeDeadAudioRecordSource : AudioRecordSource {
        override val state: Int = AudioRecord.STATE_INITIALIZED
        override val audioSessionId: Int = 42
        var started = false
        var stopped = false
        override fun startRecording() { started = true }
        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            // Negative error code simulating HAL reset / lost mic
            return AudioRecord.ERROR_DEAD_OBJECT
        }
        override fun stop() { stopped = true }
        override fun release() {}
    }

    private class FakeHealthyAudioRecordSource : AudioRecordSource {
        override val state: Int = AudioRecord.STATE_INITIALIZED
        override val audioSessionId: Int = 42
        override fun startRecording() {}
        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            return sizeInBytes
        }
        override fun stop() {}
        override fun release() {}
    }

    @Test
    fun captureLoopDoesNotSpinOnNegativeReadAndReportsHardwareBusy() = runBlocking {
        val hardwareBusyCalled = AtomicBoolean(false)
        val deadSource = FakeDeadAudioRecordSource()
        val capture = OfflineAudioCapture(
            gateAllowed = { true },
            canClaimAudio = { true },
            hasMicPermission = { true },
            onHardwareBusy = { hardwareBusyCalled.set(true) },
            audioRecordFactory = { deadSource },
            onPcmFrame = {}
        )

        capture.setMicEnabled(true)
        val res = capture.start()
        assertEquals(com.ridervoice.mesh.audio.CaptureStart.Started, res)

        // Give the coroutine a moment to read and hit ERROR_DEAD_OBJECT
        withTimeout(2_000L) {
            while (capture.isCapturing.value) {
                delay(20L)
            }
        }

        assertFalse("Capture should terminate on negative read", capture.isCapturing.value)
        assertTrue("Hardware busy callback must be fired", hardwareBusyCalled.get())
        assertTrue("AudioRecord.stop should have been called", deadSource.stopped)
    }

    @Test
    fun micStateTransitionsReflectPermissionAndOwnershipHonestly() = runBlocking {
        var hasPermission = false
        val arbiter = AudioOwnershipArbiter()
        val transport = OfflineMeshVoiceTransport(
            identity = MeshIdentity(UUID.randomUUID().toString(), "Alice"),
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            clock = { 1_000L },
            linkFactory = { FakeLink() },
            hasMicPermission = { hasPermission },
            audioRecordFactory = { FakeHealthyAudioRecordSource() }
        )

        val session = VoiceSession.Offline(OfflineSession.create(1_000L))
        transport.start(session)

        // 1. Initially OFF
        assertEquals(MicState.OFF, transport.micState.value)

        // 2. Permission denied
        transport.setMicrophoneEnabled(true)
        assertEquals(MicState.PERMISSION_DENIED, transport.micState.value)

        // 3. Permission granted -> ON
        hasPermission = true
        transport.setMicrophoneEnabled(true)
        assertEquals(MicState.ON, transport.micState.value)

        // 4. Turning mic off -> OFF
        transport.setMicrophoneEnabled(false)
        assertEquals(MicState.OFF, transport.micState.value)

        // 5. Another transport takes ownership -> OWNERSHIP_DENIED
        arbiter.claimLiveKit()
        transport.setMicrophoneEnabled(true)
        assertEquals(MicState.OWNERSHIP_DENIED, transport.micState.value)

        transport.stop()
    }

    @Test
    fun diagnosticsFedWithLiveCountersAndDirectNeighbors() = runBlocking {
        var t = 1_000L
        val clock = { t }
        val arbiter = AudioOwnershipArbiter()
        val id = UUID.randomUUID().toString()
        val peerId = UUID.randomUUID().toString()
        val s = OfflineSession.create(t)

        val transport = OfflineMeshVoiceTransport(
            identity = MeshIdentity(id, "Self"),
            audioOwnershipArbiter = arbiter,
            gateAllowed = { true },
            clock = clock,
            linkFactory = { FakeLink() }
        )

        transport.start(VoiceSession.Offline(s))

        // Deliver 10 distinct voice packets through the incoming link flow
        val rawFrames = (1..10).map { seq ->
            val aad = PayloadCrypto.aad(s.sessionId, peerId, MeshPacketType.VOICE.code, 0, seq, t, AudioFormatId.PCM16_16K_MONO)
            val nonce = PayloadCrypto.nonce(0, seq, MeshPacketType.VOICE.code, peerId)
            val sourceKey = PayloadCrypto.sourceKey(s.payloadKey(), peerId)
            val sealed = PayloadCrypto.seal(sourceKey, nonce, aad, ByteArray(640))
            val packet = MeshPacket(
                type = MeshPacketType.VOICE,
                sessionId = s.sessionId,
                sourceId = peerId,
                sequence = seq,
                captureTimestamp = t,
                ttl = MeshConfig.DEFAULT_TTL,
                hopCount = 0,
                previousHopId = peerId,
                payload = sealed
            )
            MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.MESH_PORT), MeshPacketCodec.encode(packet, s.authKey()))
        }

        // Send a direct HELLO so peerId is admitted and direct neighbor
        val helloPayload = com.ridervoice.mesh.discovery.HelloCodec.encode(
            com.ridervoice.mesh.discovery.HelloPayload(name = "Peer", graphVersion = 1, neighborIds = emptyList())
        )
        val helloAad = PayloadCrypto.aad(s.sessionId, peerId, MeshPacketType.HELLO.code, 0, 100, t, AudioFormatId.PCM16_16K_MONO)
        val helloNonce = PayloadCrypto.nonce(0, 100, MeshPacketType.HELLO.code, peerId)
        val helloSourceKey = PayloadCrypto.sourceKey(s.payloadKey(), peerId)
        val sealedHello = PayloadCrypto.seal(helloSourceKey, helloNonce, helloAad, helloPayload)
        val helloPacket = MeshPacket(
            type = MeshPacketType.HELLO,
            sessionId = s.sessionId,
            sourceId = peerId,
            sequence = 100,
            captureTimestamp = t,
            ttl = MeshConfig.HELLO_TTL,
            hopCount = 0,
            previousHopId = peerId,
            payload = sealedHello
        )
        val helloFrame = MeshFrame(MeshEndpoint("10.0.0.2", MeshConfig.MESH_PORT), MeshPacketCodec.encode(helloPacket, s.authKey()))

        // Simulate incoming frames via fake link reflection
        val field = OfflineMeshVoiceTransport::class.java.getDeclaredField("forwardingEngine")
        field.isAccessible = true
        val engine = field.get(transport) as com.ridervoice.mesh.forwarding.ForwardingEngine
        engine.onIncomingFrame(helloFrame)
        for (f in rawFrames) {
            engine.onIncomingFrame(f)
        }

        // Advance time by 1.0s and wait for projector tick
        t += 1_000L
        withTimeout(3_000L) {
            while (transport.diagnostics.value.receivedVoiceFrames < 10L) {
                delay(50L)
            }
        }

        val diag = transport.diagnostics.value
        assertEquals("receivedVoiceFrames must be 10", 10L, diag.receivedVoiceFrames)
        assertEquals("directNeighborCount must be 1", 1, diag.directNeighborCount)

        transport.stop()
    }

    @Test
    fun routeAgeGrowsOverTimeAndResetsOnNextHopChange() {
        val self = "A"
        val b = "B"
        val c = "C"
        val table = RoutingTable()

        var now = 1_000L

        // Topology 1: A <-> B (expiry at 10,000)
        val ep = MeshEndpoint("10.0.0.2", 45454)
        val snap1 = MeshGraphSnapshot(
            selfId = self,
            nodes = mapOf(self to com.ridervoice.mesh.graph.MeshNodeInfo(self, "Self"), b to com.ridervoice.mesh.graph.MeshNodeInfo(b, "B")),
            edges = listOf(com.ridervoice.mesh.graph.MeshEdge(self, b, ep, now, 10_000L, null, true)),
            version = 1
        )
        table.recompute(snap1, now)

        var routeB = table.routeTo(b)!!
        assertEquals("Initial route age should be 0", 0L, now - routeB.since)

        // Advance clock by 1.5s, route remains stable
        now = 2_500L
        table.recompute(snap1, now)
        routeB = table.routeTo(b)!!
        val peer1 = UnifiedVoicePeer.fromOffline(
            UnifiedVoicePeer.projectMeshGraph(snap1, table.routes.value, emptySet(), now).first { it.deviceId == b },
            now
        )
        assertEquals("Route age should grow by 1500ms", 1_500L, peer1.routeAgeMs)
        assertEquals("Remaining TTL shrinks", 7_500L, peer1.routeTtlRemainingMs)

        // Topology 2: Route to B now goes via C (A <-> C <-> B)
        now = 3_000L
        val snap2 = MeshGraphSnapshot(
            selfId = self,
            nodes = mapOf(
                self to com.ridervoice.mesh.graph.MeshNodeInfo(self, "Self"),
                b to com.ridervoice.mesh.graph.MeshNodeInfo(b, "B"),
                c to com.ridervoice.mesh.graph.MeshNodeInfo(c, "C")
            ),
            edges = listOf(
                com.ridervoice.mesh.graph.MeshEdge(self, c, ep, now, 10_000L, null, true),
                com.ridervoice.mesh.graph.MeshEdge(c, b, null, now, 10_000L, null, true)
            ),
            version = 2
        )
        table.recompute(snap2, now)
        routeB = table.routeTo(b)!!
        assertEquals("Next hop changed to C", c, routeB.nextHop)

        val peer2 = UnifiedVoicePeer.fromOffline(
            UnifiedVoicePeer.projectMeshGraph(snap2, table.routes.value, emptySet(), now).first { it.deviceId == b },
            now
        )
        assertEquals("Route age must reset to 0 on next hop change", 0L, peer2.routeAgeMs)
    }

    @Test
    fun t27_sourceJitterBufferOrderingAcrossSequenceWrap() {
        val clock = { 1_000L }
        val buffer = SourceJitterBuffer(sourceId = "nodeA", targetDelayFrames = 2, maxQueueFrames = 10, clock = clock)
        val pcm1 = ByteArray(MeshConfig.BYTES_PER_FRAME) { 1 }
        val pcm2 = ByteArray(MeshConfig.BYTES_PER_FRAME) { 2 }
        val pcm3 = ByteArray(MeshConfig.BYTES_PER_FRAME) { 3 }

        val seq1 = Int.MAX_VALUE - 1
        val seq2 = Int.MAX_VALUE
        val seq3 = Int.MIN_VALUE // wrapped sequence

        buffer.enqueue(seq1, pcm1)
        buffer.enqueue(seq2, pcm2)
        assertTrue(buffer.isPrimed())

        buffer.enqueue(seq3, pcm3)
        assertEquals(0L, buffer.lateDroppedCount)

        val frame1 = buffer.pollFrame() as PlayoutFrame.Audio
        assertEquals(seq1, frame1.sequence)

        val frame2 = buffer.pollFrame() as PlayoutFrame.Audio
        assertEquals(seq2, frame2.sequence)

        val frame3 = buffer.pollFrame() as PlayoutFrame.Audio
        assertEquals(seq3, frame3.sequence)
    }

    @Test
    fun oneWayEdgeIsNotSelectedByRouteCalculator() {
        val now = 1_000L
        val self = "self-node"
        val dest = "dest-node"
        val relay = "relay-node"
        val ep = MeshEndpoint("10.0.0.1", MeshConfig.MESH_PORT)

        val snap = MeshGraphSnapshot(
            selfId = self,
            nodes = mapOf(
                self to com.ridervoice.mesh.graph.MeshNodeInfo(self, "Self"),
                dest to com.ridervoice.mesh.graph.MeshNodeInfo(dest, "Dest"),
                relay to com.ridervoice.mesh.graph.MeshNodeInfo(relay, "Relay")
            ),
            edges = listOf(
                com.ridervoice.mesh.graph.MeshEdge(self, dest, ep, now, now + 10_000L, null, bidirectional = false),
                com.ridervoice.mesh.graph.MeshEdge(self, relay, ep, now, now + 10_000L, null, bidirectional = true),
                com.ridervoice.mesh.graph.MeshEdge(relay, dest, null, now, now + 10_000L, null, bidirectional = true)
            ),
            version = 1
        )

        val routes = RouteCalculator.compute(snap, now)
        val routeToDest = routes[dest]
        org.junit.Assert.assertNotNull("Route to dest must exist via bidirectional relay", routeToDest)
        assertEquals("Next hop must be relay, ignoring unconfirmed 1-way edge", relay, routeToDest!!.nextHop)
        assertEquals("Hop count must be 2", 2, routeToDest.hopCount)
    }

    @Test
    fun senderRestartWithNewEpochImmediatelyPlaysInJitterBuffer() {
        var now = 1_000L
        val clock = { now }
        val manager = MultiSourceJitterBufferManager(targetDelayFrames = 2, maxQueueFrames = 10, clock = clock)
        val pcm = ByteArray(MeshConfig.BYTES_PER_FRAME) { 0x42.toByte() }

        // Enqueue epoch 1 frames
        manager.onVoicePacket("sourceA", 10, pcm, epoch = 1)
        manager.onVoicePacket("sourceA", 11, pcm, epoch = 1)
        // Drain frames
        val (f1, _) = manager.pollMixedFrame()
        org.junit.Assert.assertNotNull("Epoch 1 frame 10 delivered", f1)
        val (f2, _) = manager.pollMixedFrame()
        org.junit.Assert.assertNotNull("Epoch 1 frame 11 delivered", f2)

        // Sender restarts with epoch 2 at seq 1 (which would be late relative to seq 12)
        manager.onVoicePacket("sourceA", 1, pcm, epoch = 2)
        manager.onVoicePacket("sourceA", 2, pcm, epoch = 2)

        val (f3, _) = manager.pollMixedFrame()
        org.junit.Assert.assertNotNull("Epoch 2 frame 1 should play immediately upon re-prime", f3)
    }
}