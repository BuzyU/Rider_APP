package com.ridervoice.models

data class ConvoyCreateRequest(
    val convoyName: String,
    val origin: String? = null,
    val destination: String? = null,
    val estimatedDurationMin: Int? = null,
    val meetupPoint: String? = null,
    val diagnosticsEnabled: Boolean = false,
    val clientRequestId: String? = null
)

data class ConvoyCreateResponse(
    val roomId: String,
    val convoyName: String,
    val hostId: String,
    val diagnosticsEnabled: Boolean = false,
    val joinCode: String? = null,
    val status: String? = null
)

data class LobbyStatus(
    val roomId: String,
    val convoyName: String,
    val invites: List<LobbyInviteEntry> = emptyList(),
    val acceptedCount: Int = 0,
    val pendingCount: Int = 0,
    val declinedCount: Int = 0,
    val canStart: Boolean = false,
    val isRideStarted: Boolean = false,
    val isHost: Boolean = false,
    val status: String? = null,
    val isOwner: Boolean? = null,
    val startedAt: String? = null,
    val diagnosticsEnabled: Boolean = false,
    val host: LobbyHost? = null,
    val joinCode: String? = null,
    val origin: String? = null,
    val destination: String? = null,
    val meetupPoint: String? = null,
    val estimatedDurationMin: Int? = null
)

data class LobbyHost(
    val id: String,
    val callsign: String? = null,
    val handle: String? = null,
    val displayName: String? = null,
    val bikeModel: String? = null
)

data class LobbyInviteEntry(
    val inviteId: String,
    val status: String,
    val invitee: LobbyRiderInfo,
    val source: String? = null
)

data class LobbyRiderInfo(
    val id: String,
    val handle: String?,
    val displayName: String?,
    val bikeModel: String?,
    val callsign: String? = null
)

data class StartRideResponse(
    val token: String,
    val roomName: String,
    val roomId: String? = null,
    val livekitUrl: String,
    val diagnosticsEnabled: Boolean = false,
    val testNumber: Int? = null,
    val expiresAt: String? = null
)

data class JoinTokenRequest(
    val roomName: String,
    val roomId: String? = null
)

data class SosAlertRequest(
    val roomName: String,
    val lat: Double?,
    val lng: Double?
)

data class CancelAlertRequest(
    val roomName: String? = null,
    val alertId: String? = null,
    val reason: String = "FALSE_ALARM"
)

data class InviteRespondRequest(
    val inviteId: String,
    val response: String
)

data class SendInviteRequest(
    val roomId: String,
    val inviteeId: String
)

data class FriendRequest(
    val addresseeId: String? = null,
    val handle: String? = null
)

data class ProfileRequest(
    val handle: String? = null,
    val displayName: String? = null,
    val bikeModel: String? = null,
    val bio: String? = null,
    val phone: String? = null
)

data class ProfileResponse(
    val id: String,
    val handle: String?,
    val displayName: String?,
    val bikeModel: String?,
    val bio: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val createdAt: String? = null
)

data class DeviceHandshakeResult(
    val deviceName: String,
    val deviceType: DeviceType,
    val scoConnected: Boolean,
    val signalStrengthDbm: Int?
)

enum class DeviceType { BLUETOOTH_SCO, BLUETOOTH_A2DP, WIRED, USB, EARPIECE }

data class RoomData(
    val roomName: String,
    val token: String,
    val roomId: String? = null,
    val livekitUrl: String? = null,
    val diagnosticsEnabled: Boolean = false,
    val testNumber: Int? = null
)
data class RoomTokenRequest(val roomName: String)
data class FcmTokenRequest(val token: String, val platform: String = "android", val userId: String = "")

data class PostRideSnapshot(
    val convoyName: String,
    val testNumber: Int? = null,
    val diagnosticsEnabled: Boolean = false,
    val endedBy: String = "USER"
)

data class TokenPayload(
    val token: String,
    val livekitUrl: String? = null,
    val roomId: String? = null,
    val roomName: String? = null,
    val expiresAtEpochSec: Long? = null,
    val diagnosticsEnabled: Boolean = false,
    val testNumber: Int? = null
)

data class RefreshTokenRequest(
    val roomId: String? = null,
    val roomName: String? = null
)

data class LobbyJoinRequest(
    val kind: String, // "TOKEN", "CODE", "MEMBER"
    val value: String
)

data class AppConfigResponse(
    val roomIdMode: Boolean? = null,
    val minAppVersion: String? = null,
    val latestVersion: String? = null,
    val apkUrl: String? = null,
    val maintenance: Boolean? = null
)

@Deprecated("Use ActiveRideRepository instead")
object RideSession {
    var livekitUrl: String = ""
    var livekitToken: String = ""
    var activeRoomName: String? = null
    var activeRoomId: String? = null
    var isHost: Boolean = false
    var diagnosticsEnabled: Boolean = false
    var testNumber: Int? = null

    fun clear() {
        livekitUrl = ""
        livekitToken = ""
        activeRoomName = null
        activeRoomId = null
        isHost = false
        diagnosticsEnabled = false
        testNumber = null
    }
}

data class ShareLinkResponse(
    val token: String,
    val shareUrl: String,
    val expiresAt: String,
    val roomId: String? = null,
    val roomName: String? = null
)

data class JoinViaTokenRequest(
    val token: String
)

data class TransferHostRequest(
    val newHostId: String
)

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

@androidx.compose.runtime.Immutable
data class Participant(
    val identity: String,
    val isGhost: Boolean = false,
    val disconnectedAt: Long? = null,
    val displayName: String? = null
)

data class RiderLocation(
    val riderId: String,
    val lat: Double,
    val lng: Double,
    val speed: Float?,
    val heading: Float?,
    val timestamp: Long
)

data class RideHistoryResponse(
    val id: String,
    val riderId: String,
    val startTime: String,
    val endTime: String?,
    val distanceKm: Float,
    val privacyState: String,
    val routeJson: String?,
    val roomName: String? = null
)

data class SosAlertResponse(
    val sent: Boolean,
    val alertId: String? = null,
    val recipientsCount: Int? = null,
    val reason: String? = null,
    val emergencyNumber: String? = null,
    val emergencyName: String? = null,
    val message: String? = null
)

data class CancelAlertResponse(
    val cancelled: Boolean,
    val reason: String? = null
)

data class SyncRideRequest(
    val roomName: String? = null,
    val startTime: String,
    val endTime: String? = null,
    val distanceKm: Float = 0f,
    val privacyState: String = "PRIVATE",
    val routeJson: String? = null,
    val events: List<SyncRideEvent>? = null
)

data class SyncRideEvent(
    val eventType: String,
    val lat: Double,
    val lng: Double,
    val timestamp: String
)

data class SyncRideResponse(
    val success: Boolean,
    val rideId: String
)
