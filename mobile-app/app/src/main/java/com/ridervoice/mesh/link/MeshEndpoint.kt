package com.ridervoice.mesh.link

/**
 * Current network address of a peer. This is an ENDPOINT, never an identity:
 * when a peer's IP changes the device keeps its deviceId and only the endpoint is updated.
 */
data class MeshEndpoint(val host: String, val port: Int)

/** A raw datagram received on a link. */
class MeshFrame(val from: MeshEndpoint, val data: ByteArray)

enum class MeshLinkState { STOPPED, STARTING, RUNNING, FAILED }

