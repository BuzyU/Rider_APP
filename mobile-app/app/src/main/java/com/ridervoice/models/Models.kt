package com.ridervoice.models

// ── Convoy / Lobby ────────────────────────────────────────────────────────────

data class ConvoyCreateRequest(
    val convoyName: String,
    val origin: String? = null,
    val destination: String? = null,
    val estimatedDurationMin: Int? = null,
    val meetupPoint: String? = null
)

data class ConvoyCreateResponse(
    val roomId: String,
    val convoyName: String,
    val hostId: String
)

data class LobbyStatus(
    val roomId: String,
    val convoyName: String,
    val invites: List<LobbyInviteEntry>,
    val acceptedCount: Int,
    val pendingCount: Int,
    val declinedCount: Int,
    val canStart: Boolean
)

data class LobbyInviteEntry(
    val inviteId: String,
    val status: String,           // PENDING | ACCEPTED | DECLINED
    val invitee: LobbyRiderInfo
)

data class LobbyRiderInfo(
    val id: String,
    val handle: String?,
    val displayName: String?,
    val bikeModel: String?
)

data class StartRideResponse(
    val token: String,
    val roomName: String,
    val livekitUrl: String
)

data class JoinTokenRequest(
    val roomName: String
)

// ── Invites ───────────────────────────────────────────────────────────────────

data class InviteRespondRequest(
    val inviteId: String,
    val response: String          // ACCEPTED | DECLINED
)

data class SendInviteRequest(
    val roomId: String,
    val inviteeId: String
)

// ── Friends ───────────────────────────────────────────────────────────────────

data class FriendRequest(
    val addresseeId: String
)

// ── Device setup ──────────────────────────────────────────────────────────────

data class DeviceHandshakeResult(
    val deviceName: String,
    val deviceType: DeviceType,
    val scoConnected: Boolean,
    val signalStrengthDbm: Int?   // null for wired
)

enum class DeviceType { BLUETOOTH_SCO, BLUETOOTH_A2DP, WIRED, USB, EARPIECE }

// Existing models kept ─────────────────────────────────────────────────────────

data class RoomData(val roomName: String, val token: String)
data class RoomTokenRequest(val roomName: String)
data class FcmTokenRequest(val userId: String, val token: String, val platform: String = "android")

object RideSession {
    var livekitUrl: String = ""
    var livekitToken: String = ""
}

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

data class InviterDetails(val handle: String, val displayName: String?)
data class RoomDetails(val name: String)

data class Participant(
    val identity: String,
    val isGhost: Boolean = false,
    val disconnectedAt: Long? = null
)

data class RiderLocation(
    val riderId: String,
    val lat: Double,
    val lng: Double,
    val speed: Float,
    val heading: Float,
    val timestamp: Long
)
