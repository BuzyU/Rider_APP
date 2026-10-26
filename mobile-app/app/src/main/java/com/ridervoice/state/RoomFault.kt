package com.ridervoice.state

/**
 * Fault taxonomy for room identity, membership, and connection validation.
 * Used for fail-closed client-side isolation (P0-B).
 */
sealed class RoomFault {
    /**
     * Confirmed mismatch: the connected LiveKit room or token claim does not match
     * the canonical lkRoomName / intended room ID for this ride.
     * Teardown scope: LOCAL DEVICE SESSION ONLY.
     */
    data class ConfirmedRoomMismatch(
        val intendedRoomId: String,
        val expectedLkRoomName: String?,
        val actualRoomName: String?,
        val reason: String
    ) : RoomFault()

    /**
     * Transient network disconnect or WebRTC connection glitch.
     * Teardown scope: reconnect with backoff.
     */
    data class TransientNetwork(
        val message: String,
        val cause: Throwable? = null
    ) : RoomFault()

    /**
     * JWT expired or needs refresh.
     * Teardown scope: single refresh attempt via ensureFreshToken.
     */
    data class AuthExpired(
        val message: String
    ) : RoomFault()

    /**
     * Requester is not an accepted member of this convoy.
     */
    data class NotMember(
        val message: String
    ) : RoomFault()

    /**
     * Requester was explicitly removed by the host.
     */
    data class RemovedByHost(
        val message: String
    ) : RoomFault()

    /**
     * The convoy/ride has authoritatively ended (HTTP 410 or verified room deletion).
     */
    data class RoomEnded(
        val message: String
    ) : RoomFault()

    /**
     * Unexpected failure.
     */
    data class Unknown(
        val message: String,
        val correlationId: String? = null
    ) : RoomFault()
}
