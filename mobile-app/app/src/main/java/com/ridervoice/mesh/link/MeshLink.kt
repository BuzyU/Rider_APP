package com.ridervoice.mesh.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport-independent link interface for sending and receiving raw mesh frames.
 */
interface MeshLink {
    val linkId: String
    val state: StateFlow<MeshLinkState>
    val incomingFrames: Flow<MeshFrame>

    suspend fun start()
    suspend fun stop()

    fun send(
        endpoint: MeshEndpoint,
        payload: ByteArray
    ): Boolean

    fun endpoints(): Set<MeshEndpoint>

    fun noteAuthenticatedEndpoint(endpoint: MeshEndpoint) {}
    fun datagramRates(): Pair<Int, Int> = Pair(0, 0)
}

