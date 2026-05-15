package com.ridervoice.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RoomViewModel : ViewModel() {

    private val _connected = MutableStateFlow(false)

    val connected: StateFlow<Boolean> = _connected

    fun setConnected(value: Boolean) {
        _connected.value = value
    }
}
