package com.ridervoice.errors

class RetryPolicy {

    fun shouldRetry(attempt: Int): Boolean {
        return attempt < 5
    }
}
