package com.ridervoice.network

import com.ridervoice.models.ConvoyCreateRequest
import com.ridervoice.models.ConvoyCreateResponse
import com.ridervoice.models.FcmTokenRequest
import com.ridervoice.models.FriendRequest
import com.ridervoice.models.Friend
import com.ridervoice.models.InviteRespondRequest
import com.ridervoice.models.JoinTokenRequest
import com.ridervoice.models.LobbyStatus
import com.ridervoice.models.RideInvite
import com.ridervoice.models.RoomData
import com.ridervoice.models.RoomTokenRequest
import com.ridervoice.models.StartRideResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {

    @POST("/api/users/fcm-token")
    suspend fun updateFcmToken(@Body body: FcmTokenRequest): Response<Any>

    @POST("/api/users/profile")
    suspend fun upsertProfile(@Body body: com.ridervoice.models.ProfileRequest): Response<com.ridervoice.models.ProfileResponse>

    @GET("/api/users/me")
    suspend fun getMyProfile(): Response<com.ridervoice.models.ProfileResponse>

    @GET("/api/users/search")
    suspend fun searchByHandle(@retrofit2.http.Query("handle") handle: String): Response<com.ridervoice.models.ProfileResponse>

    @GET("/api/friends/list/{userId}")
    suspend fun getFriendsList(@Path("userId") userId: String): Response<List<Friend>>

    @POST("/api/friends/request")
    suspend fun sendFriendRequest(@Body body: FriendRequest): Response<Any>

    @GET("/api/invites/invites/{userId}")
    suspend fun getPendingInvites(@Path("userId") userId: String): Response<List<RideInvite>>

    @POST("/api/invites/respond")
    suspend fun respondToInvite(@Body body: InviteRespondRequest): Response<Any>

    @POST("/api/invites/invite")
    suspend fun sendRideInvite(@Body body: com.ridervoice.models.SendInviteRequest): Response<Any>

    @POST("/api/lobby/create")
    suspend fun createConvoy(@Body body: ConvoyCreateRequest): Response<ConvoyCreateResponse>

    @GET("/api/lobby/{roomName}/status")
    suspend fun getLobbyStatus(@Path("roomName") roomName: String): Response<LobbyStatus>

    @POST("/api/lobby/{roomName}/start")
    suspend fun startRide(@Path("roomName") roomName: String): Response<StartRideResponse>

    @POST("/api/lobby/join-token")
    suspend fun getJoinToken(@Body body: JoinTokenRequest): Response<StartRideResponse>

    @POST("/api/lobby/{roomName}/share-link")
    suspend fun generateShareLink(@Path("roomName") roomName: String): Response<com.ridervoice.models.ShareLinkResponse>

    @POST("/api/lobby/join-via-token")
    suspend fun joinViaToken(@Body body: com.ridervoice.models.JoinViaTokenRequest): Response<StartRideResponse>

    @DELETE("/api/lobby/{roomName}/riders/{userId}")
    suspend fun removeRiderFromConvoy(@Path("roomName") roomName: String, @Path("userId") userId: String): Response<Any>

    @POST("/api/lobby/{roomName}/end")
    suspend fun endRideForEveryone(@Path("roomName") roomName: String): Response<Any>

    @POST("/api/lobby/{roomName}/transfer-host")
    suspend fun transferHost(@Path("roomName") roomName: String, @Body body: com.ridervoice.models.TransferHostRequest): Response<Any>

    @POST("/api/rooms/room/token")
    suspend fun getRoomToken(@Body body: RoomTokenRequest): Response<RoomData>

    @POST("/api/emergency/alert")
    suspend fun sendSosAlert(@Body body: com.ridervoice.models.SosAlertRequest): Response<com.ridervoice.models.SosAlertResponse>

    @POST("/api/emergency/cancel")
    suspend fun cancelEmergencyAlert(@Body body: com.ridervoice.models.CancelAlertRequest): Response<com.ridervoice.models.CancelAlertResponse>

    @POST("/api/rides/sync")
    suspend fun syncRide(@Body body: com.ridervoice.models.SyncRideRequest): Response<com.ridervoice.models.SyncRideResponse>

    @GET("/api/rides/history")
    suspend fun getRideHistory(): Response<List<com.ridervoice.models.RideHistoryResponse>>
}
