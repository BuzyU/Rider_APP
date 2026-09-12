package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.AudioDeviceRouter
import com.ridervoice.audio.RouterState
import com.ridervoice.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceSetupViewModel @Inject constructor(
    private val audioDeviceRouter: AudioDeviceRouter,
    private val securePrefs: SecurePreferences
) : ViewModel() {

    val routerState: StateFlow<RouterState> = audioDeviceRouter.routerState
    val activeDevice: StateFlow<AudioDevice> = audioDeviceRouter.activeDevice

    init {
        audioDeviceRouter.start()
    }

    override fun onCleared() {
        super.onCleared()

    }

    fun finishSetup() {
        val currentDevice = activeDevice.value
        securePrefs.saveDeviceConfigured(true)
        securePrefs.saveDeviceName(currentDevice.displayName())
    }
}
