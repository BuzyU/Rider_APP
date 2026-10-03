package com.ridervoice.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of audio mode, communication device / SCO, speakerphone and audio focus.
 * LiveKit's own audio handler is disabled (NoAudioHandler) in LiveKitManager.
 */
@Singleton
class AudioDeviceRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocusManager: AudioFocusManager
) {
    private val TAG = "AudioDeviceRouter"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _activeDevice = MutableStateFlow<AudioDevice>(AudioDevice.Earpiece)
    val activeDevice: StateFlow<AudioDevice> = _activeDevice

    private val _routerState = MutableStateFlow(RouterState.IDLE)
    val routerState: StateFlow<RouterState> = _routerState

    /** False while another app holds audio focus (loss / transient loss). LiveKit is NOT muted on loss. */
    val hasFocus: StateFlow<Boolean> = audioFocusManager.hasFocus

    /** Invoked after audio focus is regained so listeners (VoxEngine) can restart their AudioRecord. */
    var onFocusRegained: (() -> Unit)? = null

    private var bluetoothHeadset: BluetoothHeadset? = null
    private var scoConnectRetries = 0
    private val MAX_SCO_RETRIES = 3
    private var isScoStartRequested = false
    private var isStarted = false

    private var deviceChangeJob: Job? = null
    private var savedVoiceVol = -1

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            Log.d(TAG, "Audio devices added: ${addedDevices.map { it.type }}")
            scheduleReEvaluate()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            Log.d(TAG, "Audio devices removed: ${removedDevices.map { it.type }}")
            scheduleReEvaluate()
        }
    }

    private fun scheduleReEvaluate() {
        deviceChangeJob?.cancel()
        deviceChangeJob = scope.launch {
            delay(400)
            if (isStarted) reEvaluatePriority()
        }
    }

    private val wiredHeadsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_HEADSET_PLUG -> {
                    Log.d(TAG, "Headset plug event state=${intent.getIntExtra("state", -1)}")
                    scheduleReEvaluate()
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    Log.w(TAG, "Audio becoming noisy — forcing re-evaluation")
                    scheduleReEvaluate()
                }
                "android.hardware.usb.action.USB_AUDIO_ACCESSORY_PLUG",
                "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                    Log.d(TAG, "USB audio device event")
                    scheduleReEvaluate()
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
                // On API 26-30 the first evaluation runs before the proxy exists.
                if (isStarted) reEvaluatePriority()
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

        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
        if (hasBluetoothConnectPermission()) {
            try {
                btAdapter?.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.HEADSET)
            } catch (e: SecurityException) {
                Log.e(TAG, "BLUETOOTH_CONNECT not granted, skipping BT profile proxy", e)
            }
        }

        audioFocusManager.onFocusRegained = {
            Log.d(TAG, "Audio focus regained — re-evaluating route")
            reEvaluatePriority()
            onFocusRegained?.invoke()
        }
        audioFocusManager.requestFocus()

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        reEvaluatePriority()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        Log.d(TAG, "AudioDeviceRouter stopping")
        _routerState.value = RouterState.IDLE

        deviceChangeJob?.cancel()
        deviceChangeJob = null

        try { context.unregisterReceiver(wiredHeadsetReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(scoStateReceiver) } catch (_: Exception) {}
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
        btAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
        bluetoothHeadset = null

        audioFocusManager.onFocusRegained = null
        audioFocusManager.abandonFocus()

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

        // Priority order. TYPE_WIRED_HEADSET is a headset WITH a microphone
        // (headphone-only jacks report TYPE_WIRED_HEADPHONES and are never used as input).
        val candidates = mutableListOf<Pair<AudioDeviceInfo, AudioDevice>>()
        if (!skipBluetooth) {
            devices.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }.forEach {
                candidates += it to AudioDevice.BluetoothSco(it.productName?.toString() ?: "Bluetooth")
            }
        }
        devices.filter { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            .forEach { candidates += it to AudioDevice.WiredHeadset }
        devices.filter { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            .forEach { candidates += it to AudioDevice.UsbAudio }

        var chosen: AudioDevice? = null
        for ((info, dev) in candidates) {
            if (audioManager.setCommunicationDevice(info)) {
                Log.d(TAG, "Modern: routed to ${dev.displayName()} (type=${info.type})")
                chosen = dev
                break
            } else {
                Log.w(TAG, "setCommunicationDevice rejected type=${info.type}, trying next")
            }
        }

        if (chosen == null) {
            // Helmet / handlebar use: no headset -> loudspeaker, not earpiece.
            val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null && audioManager.setCommunicationDevice(speaker)) {
                Log.d(TAG, "Modern: speakerphone fallback")
            } else {
                Log.w(TAG, "Modern: speaker fallback unavailable, using isSpeakerphoneOn")
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
            chosen = AudioDevice.Earpiece
        }

        Log.d(TAG, "Communication device now: type=${audioManager.communicationDevice?.type}")
        _activeDevice.value = chosen
        _routerState.value = RouterState.ACTIVE
    }

    private fun reEvaluateLegacy(skipBluetooth: Boolean) {

        @Suppress("DEPRECATION")
        val isWiredConnected = audioManager.isWiredHeadsetOn

        when {
            !skipBluetooth && isBluetoothScoAvailableAndConnected() -> {
                Log.d(TAG, "Legacy: starting BT SCO")
                _routerState.value = RouterState.CONNECTING_BT
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                startBluetoothSco()

            }
            isWiredConnected -> {
                Log.d(TAG, "Legacy: wired headset")
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                _activeDevice.value = AudioDevice.WiredHeadset
                _routerState.value = RouterState.ACTIVE
            }
            else -> {
                Log.d(TAG, "Legacy: speakerphone fallback")
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
                _activeDevice.value = AudioDevice.Earpiece
                _routerState.value = RouterState.ACTIVE
            }
        }
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
        // Only the first call within the window saves the volume; a second call must not
        // capture the already-muted 0 and "restore" silence.
        if (savedVoiceVol == -1) {
            val prev = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (prev > 0) savedVoiceVol = prev
        }
        if (savedVoiceVol == -1) return
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        scope.launch {
            delay(200)
            val vol = savedVoiceVol
            savedVoiceVol = -1
            if (vol > 0) audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, vol, 0)
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
