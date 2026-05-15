package com.ridervoice.security

object TokenValidator {

    fun isValid(token: String): Boolean {
        return token.isNotBlank()
    }
}
