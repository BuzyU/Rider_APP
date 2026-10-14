package com.ridervoice.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.util.Log
import java.util.concurrent.Executor

/**
 * Lightweight hardware descriptor separating framework AudioDeviceInfo from pure routing logic.
 */
data class AudioDeviceDescriptor(
    val id: Int,
    val type: Int,
    val productName: String,
    val rawInfo: AudioDeviceInfo? = null
)

/**
 * Gateway interface abstracting AudioManager operations for production and JVM unit testing.
 */
interface AudioManagerGateway {
    var mode: Int
    var isSpeakerphoneOn: Boolean
    var isBluetoothScoOn: Boolean

    fun isModernRoutingSupported(): Boolean

    fun getAvailableCommunicationDevices(): List<AudioDeviceDescriptor>
    fun getCommunicationDevice(): AudioDeviceDescriptor?
    fun setCommunicationDevice(device: AudioDeviceDescriptor): Boolean
    fun clearCommunicationDevice()
    fun registerCommunicationDeviceListener(executor: Executor, onDeviceChanged: (AudioDeviceDescriptor?) -> Unit)
    fun unregisterCommunicationDeviceListener()

    fun startBluetoothSco()
    fun stopBluetoothSco()

    fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?)
    fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback)

    fun getStreamMaxVolume(streamType: Int): Int
    fun getStreamVolume(streamType: Int): Int
    fun setStreamVolume(streamType: Int, index: Int, flags: Int)
}

/**
 * Android implementation delegating directly to framework AudioManager.
 */
class AndroidAudioManagerGateway(
    private val audioManager: AudioManager
) : AudioManagerGateway {
    private val TAG = "AndroidAudioGateway"
    private var commListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    override fun isModernRoutingSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    override var mode: Int
        get() = audioManager.mode
        set(value) { audioManager.mode = value }

    override var isSpeakerphoneOn: Boolean
        @Suppress("DEPRECATION")
        get() = audioManager.isSpeakerphoneOn
        @Suppress("DEPRECATION")
        set(value) { audioManager.isSpeakerphoneOn = value }

    override var isBluetoothScoOn: Boolean
        @Suppress("DEPRECATION")
        get() = audioManager.isBluetoothScoOn
        @Suppress("DEPRECATION")
        set(value) { audioManager.isBluetoothScoOn = value }

    override fun getAvailableCommunicationDevices(): List<AudioDeviceDescriptor> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.map {
                AudioDeviceDescriptor(
                    id = it.id,
                    type = it.type,
                    productName = it.productName?.toString() ?: "Device",
                    rawInfo = it
                )
            }
        } else {
            emptyList()
        }
    }

    override fun getCommunicationDevice(): AudioDeviceDescriptor? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let {
                AudioDeviceDescriptor(
                    id = it.id,
                    type = it.type,
                    productName = it.productName?.toString() ?: "Device",
                    rawInfo = it
                )
            }
        } else {
            null
        }
    }

    override fun setCommunicationDevice(device: AudioDeviceDescriptor): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val raw = device.rawInfo ?: audioManager.availableCommunicationDevices.firstOrNull { it.id == device.id }
            if (raw != null) {
                audioManager.setCommunicationDevice(raw)
            } else {
                false
            }
        } else {
            false
        }
    }

    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    override fun registerCommunicationDeviceListener(
        executor: Executor,
        onDeviceChanged: (AudioDeviceDescriptor?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            unregisterCommunicationDeviceListener()
            val listener = AudioManager.OnCommunicationDeviceChangedListener { info ->
                val desc = info?.let {
                    AudioDeviceDescriptor(
                        id = it.id,
                        type = it.type,
                        productName = it.productName?.toString() ?: "Device",
                        rawInfo = it
                    )
                }
                onDeviceChanged(desc)
            }
            commListener = listener
            try {
                audioManager.addOnCommunicationDeviceChangedListener(executor, listener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register OnCommunicationDeviceChangedListener: ${e.message}")
            }
        }
    }

    override fun unregisterCommunicationDeviceListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commListener?.let {
                try {
                    audioManager.removeOnCommunicationDeviceChangedListener(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove OnCommunicationDeviceChangedListener: ${e.message}")
                }
            }
            commListener = null
        }
    }

    override fun startBluetoothSco() {
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
    }

    override fun stopBluetoothSco() {
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
    }

    override fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?) {
        audioManager.registerAudioDeviceCallback(callback, handler)
    }

    override fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback) {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    override fun getStreamMaxVolume(streamType: Int): Int {
        return audioManager.getStreamMaxVolume(streamType)
    }

    override fun getStreamVolume(streamType: Int): Int {
        return audioManager.getStreamVolume(streamType)
    }

    override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        audioManager.setStreamVolume(streamType, index, flags)
    }
}
