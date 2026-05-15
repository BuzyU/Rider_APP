package com.ridervoice.utils

import android.util.Log

object Logger {

    private const val TAG = "RiderVoice"

    fun debug(message: String) {
        Log.d(TAG, message)
    }

    fun error(message: String) {
        Log.e(TAG, message)
    }
}
