package com.ridervoice.security

import kotlinx.coroutines.delay

class TokenRefreshManager {

    suspend fun refreshToken(): String {
        delay(1000)
        return "new_token"
    }
}
