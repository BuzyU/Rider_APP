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
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {

    // ── Auth / Profile ────────────────────────────────────────────────────────

    @POST("/api/users/fcm-token")
    suspend fun updateFcmToken(@Body body: FcmTokenRequest): Response<Any>

    /** Creates/updates the authenticated user's profile (handle, displayName, etc). */
    @POST("/api/users/profile")
    suspend fun upsertProfile(@Body body: com.ridervoice.models.ProfileRequest): Response<com.ridervoice.models.ProfileResponse>

    /** Returns the authenticated user's own full profile. */
    @GET("/api/users/me")
    suspend fun getMyProfile(): Response<com.ridervoice.models.ProfileResponse>

    /** Public search for a rider by handle (used by the add-friend flow). */
    @GET("/api/users/search")
    suspend fun searchByHandle(@retrofit2.http.Query("handle") handle: String): Response<com.ridervoice.models.ProfileResponse>

    // ── Friends ───────────────────────────────────────────────────────────────

    @GET("/api/friends/list/{userId}")
    suspend fun getFriendsList(@Path("userId") userId: String): Response<List<Friend>>

    @POST("/api/friends/request")
    suspend fun sendFriendRequest(@Body body: FriendRequest): Response<Any>

    // ── Invites ───────────────────────────────────────────────────────────────

    /** Joiner: get all pending ride invites */
    @GET("/api/invites/invites/{userId}")
    suspend fun getPendingInvites(@Path("userId") userId: String): Response<List<RideInvite>>

    /** Joiner: accept or decline an invite */
    @POST("/api/invites/respond")
    suspend fun respondToInvite(@Body body: InviteRespondRequest): Response<Any>

    /** Host: send a ride invite to a friend */
    @POST("/api/invites/invite")
    suspend fun sendRideInvite(@Body body: com.ridervoice.models.SendInviteRequest): Response<Any>

    // ── Lobby (HOST path) ─────────────────────────────────────────────────────

    /** Host: create a named convoy with trip details */
    @POST("/api/lobby/create")
    suspend fun createConvoy(@Body body: ConvoyCreateRequest): Response<ConvoyCreateResponse>

    /** Host: poll invite accept/decline statuses */
    @GET("/api/lobby/{roomName}/status")
    suspend fun getLobbyStatus(@Path("roomName") roomName: String): Response<LobbyStatus>

    /** Host: start the ride — returns LiveKit token */
    @POST("/api/lobby/{roomName}/start")
    suspend fun startRide(@Path("roomName") roomName: String): Response<StartRideResponse>

    // ── Lobby (JOIN path) ─────────────────────────────────────────────────────

    /** Joiner: get a LiveKit token after accepting an invite */
    @POST("/api/lobby/join-token")
    suspend fun getJoinToken(@Body body: JoinTokenRequest): Response<StartRideResponse>

    // ── Legacy room token (kept for quick-join / guest) ───────────────────────

    @POST("/api/rooms/room/token")
    suspend fun getRoomToken(@Body body: RoomTokenRequest): Response<RoomData>

    // ── Emergency ─────────────────────────────────────────────────────────────────

    @POST("/api/emergency/alert")
    suspend fun sendSosAlert(@Body body: com.ridervoice.models.SosAlertRequest): Response<Any>

    @POST("/api/emergency/cancel")
    suspend fun cancelEmergencyAlert(@Body body: com.ridervoice.models.CancelAlertRequest): Response<Any>

    // ── Ride History ──────────────────────────────────────────────────────────────

    @GET("/api/rides/history")
    suspend fun getRideHistory(): Response<List<com.ridervoice.models.RideHistoryResponse>>
}