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

/**
 * The central audio device manager for Rider Voice.
 *
 * Priority order (highest first):
 *   1. Bluetooth SCO headset (Cardo Packtalk, Sena, etc.)
 *   2. Wired headset with mic (3.5mm or USB-C)
 *   3. USB audio device with mic
 *   4. Built-in earpiece (never speaker — prevents accidental wind blast)
 *
 * On any device change the router re-evaluates priority and switches
 * automatically. The LiveKitBridge is notified so it can rebuild the
 * audio track with the right processing profile for the new device.
 */
@Singleton
class AudioDeviceRouter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "AudioDeviceRouter"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Public state ──────────────────────────────────────────────────────────

    private val _activeDevice = MutableStateFlow<AudioDevice>(AudioDevice.Earpiece)
    val activeDevice: StateFlow<AudioDevice> = _activeDevice

    private val _routerState = MutableStateFlow(RouterState.IDLE)
    val routerState: StateFlow<RouterState> = _routerState

    // ── Bluetooth SCO state machine ───────────────────────────────────────────

    private var bluetoothHeadset: BluetoothHeadset? = null
    private var scoConnectRetries = 0
    private val MAX_SCO_RETRIES = 3
    private var isScoStartRequested = false
    private var isStarted = false

    // ── Broadcast receivers ───────────────────────────────────────────────────

    /**
     * Listens for wired headset plug/unplug events (ACTION_HEADSET_PLUG)
     * and USB audio device attach/detach (ACTION_AUDIO_BECOMING_NOISY,
     * ACTION_USB_AUDIO_DEVICE_PLUG).
     */
    private val wiredHeadsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    val hasMic = intent.getIntExtra("microphone", 0) == 1
                    if (state == 1) {
                        Log.d(TAG, "Wired headset connected (hasMic=$hasMic)")
                        // Wired always wins over earpiece, loses to BT
                        if (_activeDevice.value !is AudioDevice.BluetoothSco) {
                            switchToWired(hasMic)
                        }
                    } else if (state == 0) {
                        Log.d(TAG, "Wired headset disconnected — re-evaluating")
                        reEvaluatePriority()
                    }
                }
                // When BT or another device steals audio, Android fires NOISY.
                // We use this as a signal to re-check what's available.
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

    /**
     * Tracks Bluetooth SCO connection state transitions:
     * CONNECTING → CONNECTED or DISCONNECTED.
     * This is the only reliable way to know SCO actually succeeded on pre-12 Android.
     */
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
                        // Mute vol to 0 before fallback to prevent speaker blast
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

    /**
     * BluetoothProfile.ServiceListener — needed to get the BluetoothHeadset
     * proxy object so we can call isAudioConnected() on devices.
     */
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Call this when entering a voice session (e.g. joinRoom).
     * Registers all receivers and starts the initial device scan.
     */
    fun start() {
        if (isStarted) return
        isStarted = true
        Log.d(TAG, "AudioDeviceRouter starting")
        _routerState.value = RouterState.SCANNING

        // Register wired/USB receiver
        val wiredFilter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction("android.hardware.usb.action.USB_AUDIO_ACCESSORY_PLUG")
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
        }
        context.registerReceiver(wiredHeadsetReceiver, wiredFilter)

        // Register SCO state receiver
        context.registerReceiver(
            scoStateReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )

        // Acquire BluetoothHeadset profile proxy
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        btAdapter?.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.HEADSET)

        // Set communication mode immediately
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        // Initial scan
        reEvaluatePriority()
    }

    /**
     * Call this when leaving the voice session. Tears down everything cleanly.
     */
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

    // ── Priority evaluation ───────────────────────────────────────────────────

    /**
     * Scans connected devices in priority order and routes to the best one.
     * Safe to call from any state — it compares against current device first.
     */
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
        // On pre-12 Android we must manually check and start SCO
        val isWiredConnected = audioManager.isWiredHeadsetOn

        when {
            !skipBluetooth && isBluetoothScoAvailableAndConnected() -> {
                Log.d(TAG, "Legacy: starting BT SCO")
                _routerState.value = RouterState.CONNECTING_BT
                startBluetoothSco()
                // _activeDevice updated by scoStateReceiver on success
            }
            isWiredConnected -> {
                Log.d(TAG, "Legacy: wired headset")
                @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = false
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

    // ── Device-specific switch helpers ────────────────────────────────────────

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
        isScoStartRequested = true
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
    }

    private fun isBluetoothScoAvailableAndConnected(): Boolean {
        if (!audioManager.isBluetoothScoAvailableOffCall) return false
        // Check if any paired device is in HEADSET profile and audio-connected
        return bluetoothHeadset?.connectedDevices?.isNotEmpty() == true
    }

    private fun getConnectedBluetoothName(): String {
        return bluetoothHeadset?.connectedDevices?.firstOrNull()?.name ?: "Bluetooth headset"
    }

    /**
     * Silently zero voice call volume before routing change to prevent
     * a sudden blast of audio through the wrong device (e.g. earpiece → speaker).
     * Restores the volume ~200 ms after the switch.
     */
    private fun safelyMuteVoiceCall() {
        val prev = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
        audioManager.isSpeakerphoneOn = false
        scope.launch {
            delay(200)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prev, 0)
        }
    }
}

// ── Data types ────────────────────────────────────────────────────────────────

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

enum class RouterState { IDLE, SCANNING, CONNECTING_BT, ACTIVE }
