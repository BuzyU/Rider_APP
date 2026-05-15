package com.ridervoice.network

import com.ridervoice.models.RoomData
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface ApiService {

    @GET("/token")
    suspend fun getToken(
        @Query("room") room: String,
        @Query("user") user: String
    ): Response<RoomData>
}
