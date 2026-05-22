package com.ridervoice.models

data class Friend(
    val id: String,
    val handle: String,
    val displayName: String?,
    val bikeModel: String?
)

data class RideInvite(
    val id: String,
    val roomId: String,
    val inviterId: String,
    val inviteeId: String,
    val status: String,
    val inviter: InviterDetails,
    val room: RoomDetails
)

data class InviterDetails(
    val handle: String,
    val displayName: String?
)

data class RoomDetails(
    val name: String
)
