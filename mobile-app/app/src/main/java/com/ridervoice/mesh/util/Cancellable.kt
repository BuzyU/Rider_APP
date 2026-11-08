package com.ridervoice.mesh.util

import kotlinx.coroutines.CancellationException

suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try { Result.success(block()) }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }
