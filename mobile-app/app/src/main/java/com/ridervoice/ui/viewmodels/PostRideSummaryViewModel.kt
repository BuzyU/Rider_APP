package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.ridervoice.services.RideRecorder
import com.ridervoice.services.RideSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PostRideSummaryViewModel @Inject constructor(
    private val rideRecorder: RideRecorder
) : ViewModel() {

    val summary: StateFlow<RideSummary?> = rideRecorder.lastSummary

    fun save() {
        rideRecorder.clearSummary()
    }

    fun discard() {
        rideRecorder.discardLastSession()
    }
}
