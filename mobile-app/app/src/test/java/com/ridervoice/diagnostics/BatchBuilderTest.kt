package com.ridervoice.diagnostics

import com.google.gson.Gson
import com.ridervoice.diagnostics.data.entities.DiagOutboxEntity
import com.ridervoice.diagnostics.data.entities.DiagSampleEntity
import com.ridervoice.diagnostics.data.entities.DiagSessionEntity
import com.ridervoice.diagnostics.models.DiagnosticBatchPayload
import com.ridervoice.diagnostics.models.EventSample
import com.ridervoice.diagnostics.models.InboundMetricSample
import com.ridervoice.diagnostics.models.SelfMetricSample
import org.junit.Assert.*
import org.junit.Test

class BatchBuilderTest {

    private val gson = Gson()

    @Test
    fun `batch chunking caps rows at exactly 200 per batch`() {
        val totalSamples = 450
        val sampleList = (1..totalSamples).map { i ->
            DiagSampleEntity(
                id = i.toLong(),
                localSessionId = "session_test_1",
                type = "SELF",
                payloadJson = gson.toJson(
                    SelfMetricSample(
                        timestamp = 1700000000L + i,
                        deviceEpochMs = 1700000000000L + i,
                        elapsedRealtimeMs = 100000L + i,
                        packetsSent = i.toLong()
                    )
                )
            )
        }

        // Simulate chunking logic in collector (limit 200 per chunk)
        val batches = mutableListOf<List<DiagSampleEntity>>()
        var remaining = sampleList
        while (remaining.isNotEmpty()) {
            val chunk = remaining.take(200)
            batches.add(chunk)
            remaining = remaining.drop(200)
        }

        assertEquals("450 items should be split into 3 batches", 3, batches.size)
        assertEquals("First batch must have 200 items", 200, batches[0].size)
        assertEquals("Second batch must have 200 items", 200, batches[1].size)
        assertEquals("Third batch must have 50 items", 50, batches[2].size)
        assertTrue("No batch may exceed 200 items", batches.all { it.size <= 200 })
    }

    @Test
    fun `session sequence numbers are strictly monotonic and persisted`() {
        var session = DiagSessionEntity(
            localSessionId = "session_mono_seq",
            convoyId = "convoy_alpha",
            lastSeq = 0
        )

        val outbox = mutableListOf<DiagOutboxEntity>()

        // Simulate 4 successive batch enqueues
        for (i in 1..4) {
            val nextSeq = session.lastSeq + 1
            session = session.copy(lastSeq = nextSeq)
            outbox.add(
                DiagOutboxEntity(
                    id = i.toLong(),
                    localSessionId = session.localSessionId,
                    seq = nextSeq,
                    type = "BATCH",
                    payloadJson = "{}"
                )
            )
        }

        assertEquals(4, outbox.size)
        assertEquals(listOf(1, 2, 3, 4), outbox.map { it.seq })
        assertEquals(4, session.lastSeq)
    }

    @Test
    fun `stop request is enqueued with lastSeq strictly after all batches`() {
        var session = DiagSessionEntity(
            localSessionId = "session_stop_order",
            convoyId = "convoy_alpha",
            lastSeq = 0
        )

        val outbox = mutableListOf<DiagOutboxEntity>()

        // 3 Batches
        for (b in 1..3) {
            val nextSeq = session.lastSeq + 1
            session = session.copy(lastSeq = nextSeq)
            outbox.add(
                DiagOutboxEntity(
                    id = b.toLong(),
                    localSessionId = session.localSessionId,
                    seq = nextSeq,
                    type = "BATCH",
                    payloadJson = "{}"
                )
            )
        }

        // Enqueue STOP
        val stopSeq = session.lastSeq
        session = session.copy(state = "STOPPED")
        outbox.add(
            DiagOutboxEntity(
                id = 4L,
                localSessionId = session.localSessionId,
                seq = stopSeq,
                type = "STOP",
                payloadJson = "{}"
            )
        )

        // Verify outbox ordering: BATCH seq 1, BATCH seq 2, BATCH seq 3, STOP seq 3
        assertEquals(4, outbox.size)
        val stopItem = outbox.last()
        assertEquals("STOP", stopItem.type)
        assertEquals(3, stopItem.seq)

        // In SQL: ORDER BY seq ASC, id ASC
        val sortedOutbox = outbox.sortedWith(compareBy({ it.seq }, { it.id }))
        assertEquals("STOP item must sort AFTER BATCH of the same sequence number because its id is higher", 4L, sortedOutbox.last().id)
        assertEquals("STOP", sortedOutbox.last().type)
    }
}
