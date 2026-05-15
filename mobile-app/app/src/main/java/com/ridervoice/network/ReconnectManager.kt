package com.ridervoice.network

import kotlinx.coroutines.*

class ReconnectManager(
    private val reconnectAction: suspend () -> Unit
) {

    private var reconnectAttempts = 0
    private val maxAttempts = 10

    fun attemptReconnect() {

        CoroutineScope(Dispatchers.IO).launch {

            while (reconnectAttempts < maxAttempts) {

                try {
                    reconnectAction()
                    reconnectAttempts = 0
                    break
                } catch (e: Exception) {
                    reconnectAttempts++
                    delay((2000 * reconnectAttempts).toLong())
                }
            }
        }
    }
}
