package com.ridervoice.data.repository

import com.ridervoice.models.Friend
import com.ridervoice.models.RideInvite
import com.ridervoice.network.ApiService
import javax.inject.Inject

class SquadRepository @Inject constructor(
    private val apiService: ApiService
) {

    suspend fun getFriends(userId: String): Result<List<Friend>> {
        return try {
            val response = apiService.getFriendsList(userId)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Failed to load friends: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPendingInvites(userId: String): Result<List<RideInvite>> {
        return try {
            val response = apiService.getPendingInvites(userId)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Failed to load invites: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
