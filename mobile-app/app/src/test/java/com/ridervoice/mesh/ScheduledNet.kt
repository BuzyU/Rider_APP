package com.ridervoice.mesh

import com.ridervoice.mesh.forwarding.ForwardingEngine
import com.ridervoice.mesh.link.MeshEndpoint
import com.ridervoice.mesh.link.MeshFrame
import java.util.PriorityQueue

class ScheduledNet(val seed: Long) {
    var now = 0L
        private set

    private val rnd = kotlin.random.Random(seed)
    var dropPercent = 0
    var duplicatePercent = 0
    var maxDelayMs = 0L

    val links = HashSet<Pair<String, String>>() // physical adjacency, both directions stored

    private data class Msg(val at: Long, val n: Long, val to: String, val frame: MeshFrame)
    private val queue = PriorityQueue<Msg>(compareBy({ it.at }, { it.n }))
    private var counter = 0L

    val engines = HashMap<String, ForwardingEngine>()
    val endpointOf = HashMap<String, MeshEndpoint>()

    fun connect(a: String, b: String) {
        links += a to b
        links += b to a
    }

    fun disconnect(a: String, b: String) {
        links -= a to b
        links -= b to a
    }

    fun isolate(id: String) {
        links.removeAll { it.first == id || it.second == id }
    }

    fun deliver(from: String, toEndpoint: MeshEndpoint, payload: ByteArray): Boolean {
        val targets = if (toEndpoint.host == "255.255.255.255") {
            links.filter { it.first == from }.map { it.second }
        } else {
            listOfNotNull(endpointOf.entries.firstOrNull { it.value == toEndpoint }?.key)
                .filter { from to it in links }
        }

        for (t in targets) {
            if (rnd.nextInt(100) < dropPercent) continue
            val copies = if (rnd.nextInt(100) < duplicatePercent) 2 else 1
            repeat(copies) {
                val delay = if (maxDelayMs == 0L) 0L else rnd.nextLong(0, maxDelayMs + 1)
                queue += Msg(now + delay, counter++, t, MeshFrame(endpointOf.getValue(from), payload.copyOf()))
            }
        }
        return targets.isNotEmpty()
    }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = queue.peek() ?: break
            if (next.at > end) break
            queue.poll()
            now = next.at
            engines.getValue(next.to).onIncomingFrame(next.frame)
        }
        now = end
    }
}

