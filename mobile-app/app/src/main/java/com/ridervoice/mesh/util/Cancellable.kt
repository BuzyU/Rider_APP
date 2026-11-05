package com.ridervoice.mesh.util

suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try { Result.success(block()) }
    catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

