package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.ridervoice.diagnostics.collector.NetworkDiagnosticsCollector
import com.ridervoice.diagnostics.models.SelfMetricSample
import com.ridervoice.models.RideSession
import com.ridervoice.services.RideRecorder
import com.ridervoice.services.RideSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PostRideSummaryViewModel @Inject constructor(
    private val rideRecorder: RideRecorder,
    private val networkDiagnosticsCollector: NetworkDiagnosticsCollector
) : ViewModel() {

    val summary: StateFlow<RideSummary?> = rideRecorder.lastSummary
    val latestMetric: SelfMetricSample? = networkDiagnosticsCollector.latestSelfMetric.value
    val sampleCount: Int = networkDiagnosticsCollector.sampleCount.value
    val convoyName: String = RideSession.activeRoomName ?: "Convoy"
    val testNumber: Int = RideSession.testNumber ?: 1

    fun save() {
        rideRecorder.clearSummary()
    }

    fun discard() {
        rideRecorder.discardLastSession()
    }
}
