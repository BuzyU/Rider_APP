package com.ridervoice.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioDeviceRouter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "AudioDeviceRouter"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _activeDevice = MutableStateFlow<AudioDevice>(AudioDevice.Earpiece)
    val activeDevice: StateFlow<AudioDevice> = _activeDevice

    private val _routerState = MutableStateFlow(RouterState.IDLE)
    val routerState: StateFlow<RouterState> = _routerState

    private var bluetoothHeadset: BluetoothHeadset? = null
    private var scoConnectRetries = 0
    private val MAX_SCO_RETRIES = 3
    private var isScoStartRequested = false
    private var isStarted = false

    private val wiredHeadsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    val hasMic = intent.getIntExtra("microphone", 0) == 1
                    if (state == 1) {
                        Log.d(TAG, "Wired headset connected (hasMic=$hasMic)")

                        if (_activeDevice.value !is AudioDevice.BluetoothSco) {
                            switchToWired(hasMic)
                        }
                    } else if (state == 0) {
                        Log.d(TAG, "Wired headset disconnected — re-evaluating")
                        reEvaluatePriority()
                    }
                }

                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    Log.w(TAG, "Audio becoming noisy — forcing re-evaluation")
                    reEvaluatePriority()
                }
                "android.hardware.usb.action.USB_AUDIO_ACCESSORY_PLUG",
                "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                    Log.d(TAG, "USB audio device event")
                    scope.launch { delay(500); reEvaluatePriority() }
                }
            }
        }
    }

    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Log.d(TAG, "SCO connected ✓")
                    scoConnectRetries = 0
                    isScoStartRequested = false
                    _activeDevice.value = AudioDevice.BluetoothSco(getConnectedBluetoothName())
                    _routerState.value = RouterState.ACTIVE
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (isScoStartRequested && scoConnectRetries < MAX_SCO_RETRIES) {
                        Log.w(TAG, "SCO disconnected unexpectedly — retry ${scoConnectRetries + 1}/$MAX_SCO_RETRIES")
                        scoConnectRetries++
                        scope.launch {
                            delay(800L * scoConnectRetries)
                            startBluetoothSco()
                        }
                    } else {
                        Log.e(TAG, "SCO failed after $MAX_SCO_RETRIES retries — falling back")
                        isScoStartRequested = false

                        safelyMuteVoiceCall()
                        reEvaluatePriority(skipBluetooth = true)
                    }
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    Log.e(TAG, "SCO error state")
                    isScoStartRequested = false
                    reEvaluatePriority(skipBluetooth = true)
                }
            }
        }
    }

    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as BluetoothHeadset
                Log.d(TAG, "BluetoothHeadset proxy acquired")
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            bluetoothHeadset = null
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        Log.d(TAG, "AudioDeviceRouter starting")
        _routerState.value = RouterState.SCANNING

        val wiredFilter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction("android.hardware.usb.action.USB_AUDIO_ACCESSORY_PLUG")
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(wiredHeadsetReceiver, wiredFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(
                scoStateReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(wiredHeadsetReceiver, wiredFilter)
            context.registerReceiver(
                scoStateReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )
        }

        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (hasBluetoothConnectPermission()) {
            try {
                btAdapter?.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.HEADSET)
            } catch (e: SecurityException) {
                Log.e(TAG, "BLUETOOTH_CONNECT not granted, skipping BT profile proxy", e)
            }
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        reEvaluatePriority()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        Log.d(TAG, "AudioDeviceRouter stopping")
        _routerState.value = RouterState.IDLE

        try { context.unregisterReceiver(wiredHeadsetReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(scoStateReceiver) } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }

        BluetoothAdapter.getDefaultAdapter()
            ?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
        bluetoothHeadset = null

        audioManager.mode = AudioManager.MODE_NORMAL
        _activeDevice.value = AudioDevice.Earpiece
    }

    fun reEvaluatePriority(skipBluetooth: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            reEvaluateModern(skipBluetooth)
        } else {
            reEvaluateLegacy(skipBluetooth)
        }
    }

    private fun reEvaluateModern(skipBluetooth: Boolean) {
        val devices = audioManager.availableCommunicationDevices
        Log.d(TAG, "Available comm devices: ${devices.map { it.type }}")

        val chosen = when {
            !skipBluetooth && devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> {
                val btDev = devices.first { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                Log.d(TAG, "Modern: choosing BT SCO")
                audioManager.setCommunicationDevice(btDev)
                AudioDevice.BluetoothSco(btDev.productName?.toString() ?: "Bluetooth")
            }
            devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> {
                val wiredDev = devices.first { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                Log.d(TAG, "Modern: choosing wired headset")
                audioManager.setCommunicationDevice(wiredDev)
                AudioDevice.WiredHeadset
            }
            devices.any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET } -> {
                val usbDev = devices.first { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                Log.d(TAG, "Modern: choosing USB headset")
                audioManager.setCommunicationDevice(usbDev)
                AudioDevice.UsbAudio
            }
            else -> {
                Log.d(TAG, "Modern: falling back to earpiece")
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?.let { audioManager.setCommunicationDevice(it) }
                AudioDevice.Earpiece
            }
        }

        _activeDevice.value = chosen
        _routerState.value = RouterState.ACTIVE
    }

    private fun reEvaluateLegacy(skipBluetooth: Boolean) {

        val isWiredConnected = audioManager.isWiredHeadsetOn

        when {
            !skipBluetooth && isBluetoothScoAvailableAndConnected() -> {
                Log.d(TAG, "Legacy: starting BT SCO")
                _routerState.value = RouterState.CONNECTING_BT
                startBluetoothSco()

            }
            isWiredConnected -> {
                Log.d(TAG, "Legacy: wired headset")
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                _activeDevice.value = AudioDevice.WiredHeadset
                _routerState.value = RouterState.ACTIVE
            }
            else -> {
                Log.d(TAG, "Legacy: earpiece fallback")
                _activeDevice.value = AudioDevice.Earpiece
                _routerState.value = RouterState.ACTIVE
            }
        }
    }

    private fun switchToWired(hasMic: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = AudioDeviceInfo.TYPE_WIRED_HEADSET
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == type }
                ?.let { audioManager.setCommunicationDevice(it) }
        }
        _activeDevice.value = AudioDevice.WiredHeadset
        _routerState.value = RouterState.ACTIVE
        Log.d(TAG, "Switched to wired headset (hasMic=$hasMic)")
    }

    private fun startBluetoothSco() {
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Log.e(TAG, "SCO not available off-call")
            reEvaluatePriority(skipBluetooth = true)
            return
        }
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "BLUETOOTH_CONNECT not granted, cannot start SCO")
            reEvaluatePriority(skipBluetooth = true)
            return
        }
        isScoStartRequested = true
        @Suppress("DEPRECATION")
        try {
            audioManager.startBluetoothSco()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting SCO", e)
        }
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
    }

    private fun isBluetoothScoAvailableAndConnected(): Boolean {
        if (!audioManager.isBluetoothScoAvailableOffCall) return false
        if (!hasBluetoothConnectPermission()) return false
        return try {
            bluetoothHeadset?.connectedDevices?.isNotEmpty() == true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun getConnectedBluetoothName(): String {
        if (!hasBluetoothConnectPermission()) return "Bluetooth headset"
        return try {
            bluetoothHeadset?.connectedDevices?.firstOrNull()?.name ?: "Bluetooth headset"
        } catch (e: SecurityException) {
            "Bluetooth headset"
        }
    }

    private fun safelyMuteVoiceCall() {
        val prev = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
        audioManager.isSpeakerphoneOn = false
        scope.launch {
            delay(200)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prev, 0)
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

sealed class AudioDevice {
    data class BluetoothSco(val deviceName: String) : AudioDevice()
    object WiredHeadset : AudioDevice()
    object UsbAudio : AudioDevice()
    object Earpiece : AudioDevice()

    fun displayName(): String = when (this) {
        is BluetoothSco -> deviceName
        is WiredHeadset -> "Wired headset"
        is UsbAudio     -> "USB audio"
        is Earpiece     -> "Built-in earpiece"
    }

    fun isHandsFree(): Boolean = this is BluetoothSco || this is WiredHeadset || this is UsbAudio
}

enum class RouterState { IDLE, SCANNING, CONNECTING_BT, ACTIVE, FAILED }
