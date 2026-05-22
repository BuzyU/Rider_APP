package com.ridervoice.network
import com.ridervoice.models.RoomData
import com.ridervoice.models.Friend
import com.ridervoice.models.RideInvite
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("/token")
    suspend fun getToken(@Query("room") room: String, @Query("user") user: String): Response<RoomData>

    @GET("/api/friends/list/{userId}")
    suspend fun getFriendsList(@Path("userId") userId: String): Response<List<Friend>>

    @GET("/api/rides/invites/{userId}")
    suspend fun getPendingInvites(@Path("userId") userId: String): Response<List<RideInvite>>
}
