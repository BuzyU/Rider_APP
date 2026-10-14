package com.ridervoice.diagnostics.upload

import com.ridervoice.diagnostics.models.DiagnosticBatchRequest
import com.ridervoice.diagnostics.models.DiagnosticBatchResponse
import com.ridervoice.diagnostics.models.DiagnosticJoinRequest
import com.ridervoice.diagnostics.models.DiagnosticJoinResponse
import com.ridervoice.diagnostics.models.DiagnosticStopRequest
import com.ridervoice.diagnostics.models.DiagnosticStopResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface DiagnosticApiService {

    @POST("api/diagnostics/join")
    suspend fun joinDiagnosticRun(
        @Body request: DiagnosticJoinRequest
    ): Response<DiagnosticJoinResponse>

    @POST("api/diagnostics/batches")
    suspend fun postDiagnosticBatch(
        @Body request: DiagnosticBatchRequest
    ): Response<DiagnosticBatchResponse>

    @POST("api/diagnostics/stop")
    suspend fun stopDiagnosticRun(
        @Body request: DiagnosticStopRequest
    ): Response<DiagnosticStopResponse>
}
