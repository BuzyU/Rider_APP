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

    @Suppress("UNUSED_PARAMETER")
    suspend fun addFriend(userId: String, handle: String): Result<Unit> {
        return try {
            val request = com.ridervoice.models.FriendRequest(handle = handle)
            val response = apiService.sendFriendRequest(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorMsg = response.errorBody()?.string()?.let { body ->
                    try {
                        val json = org.json.JSONObject(body)
                        json.optString("error", "Error ${response.code()}")
                    } catch (e: Exception) {
                        null
                    }
                } ?: "Failed to add friend (${response.code()})"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun respondToInvite(inviteId: String, accept: Boolean): Result<Unit> {
        return try {
            val response = apiService.respondToInvite(com.ridervoice.models.InviteRespondRequest(inviteId, if (accept) "ACCEPTED" else "DECLINED"))
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to respond (${response.code()})"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
