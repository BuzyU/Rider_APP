package com.ridervoice.audio

import android.bluetooth.BluetoothAdapter

class BluetoothHandler {

    private val adapter = BluetoothAdapter.getDefaultAdapter()

    fun isBluetoothEnabled(): Boolean {
        return adapter?.isEnabled == true
    }
}
