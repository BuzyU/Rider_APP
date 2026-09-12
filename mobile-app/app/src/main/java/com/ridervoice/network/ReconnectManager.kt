package com.ridervoice.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReconnectManager(
    private val reconnectAction: suspend () -> Unit
) {
    private val maxAttempts = 10
    private var attempts    = 0
    private var job: Job?   = null

    fun attemptReconnect() {
        job?.cancel()
        attempts = 0

        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && attempts < maxAttempts) {
                try {
                    reconnectAction()
                    attempts = 0
                    return@launch
                } catch (e: CancellationException) {

                    throw e
                } catch (e: Exception) {
                    attempts++
                    val backoffMs = minOf(2_000L * attempts, 30_000L)
                    delay(backoffMs)
                }
            }

        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        attempts = 0
    }
}
