package com.ridervoice.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.Executor

data class RoutedDeviceInfo(
    val type: Int = 0,
    val typeName: String = "Built-in",
    val productName: String = "Phone Audio"
) {
    fun display(): String = if (productName.isNotBlank()) "$productName ($typeName)" else typeName
}

/**
 * Single owner of audio mode, communication device / SCO, speakerphone and audio focus.
 * Uses AudioRouteDecisionEngine to ensure devices become ACTIVE only when confirmed by the hardware.
 */
@Singleton
class AudioDeviceRouter(
    private val context: Context?,
    private val audioFocusManager: AudioFocusManager?,
    val gateway: AudioManagerGateway,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        audioFocusManager: AudioFocusManager,
        gateway: AudioManagerGateway
    ) : this(
        context = context,
        audioFocusManager = audioFocusManager,
        gateway = gateway,
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    )

    constructor(
        gateway: AudioManagerGateway,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    ) : this(
        context = null,
        audioFocusManager = null,
        gateway = gateway,
        scope = scope
    )

    private val TAG = "AudioDeviceRouter"

    val decisionEngine = AudioRouteDecisionEngine()

    var lastCandidateDescriptors: List<Pair<AudioDeviceDescriptor, CandidateDevice>> = emptyList()
        private set
    var lastSpeakerDesc: AudioDeviceDescriptor? = null
        private set

    private val _activeDevice = MutableStateFlow<AudioDevice>(AudioDevice.Earpiece)
    val activeDevice: StateFlow<AudioDevice> = _activeDevice.asStateFlow()

    private val _requestedDevice = MutableStateFlow<AudioDevice>(AudioDevice.Earpiece)
    val requestedDevice: StateFlow<AudioDevice> = _requestedDevice.asStateFlow()

    private val _routedInput = MutableStateFlow(RoutedDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone Mic", "Built-in Mic"))
    val routedInput: StateFlow<RoutedDeviceInfo> = _routedInput.asStateFlow()

    private val _routedOutput = MutableStateFlow(RoutedDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speakerphone", "Loudspeaker"))
    val routedOutput: StateFlow<RoutedDeviceInfo> = _routedOutput.asStateFlow()

    private val _routerState = MutableStateFlow(RouterState.IDLE)
    val routerState: StateFlow<RouterState> = _routerState.asStateFlow()

    private val _republishRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val republishRequests: SharedFlow<Unit> = _republishRequests.asSharedFlow()

    val hasFocus: StateFlow<Boolean> = audioFocusManager?.hasFocus ?: MutableStateFlow(true)

    var onFocusRegained: (() -> Unit)? = null

    private var bluetoothHeadset: BluetoothHeadset? = null
    private var scoConnectRetries = 0
    private val MAX_SCO_RETRIES = 3
    private var isScoStartRequested = false
    private var isStarted = false

    private var deviceChangeJob: Job? = null
    private var confirmationTimeoutJob: Job? = null
    private var verificationLoggingJob: Job? = null
    private var manualOverrideRoute: String? = null

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
                    Log.d(TAG, "Legacy SCO connected ✓")
                    scoConnectRetries = 0
                    isScoStartRequested = false
                    val name = getConnectedBluetoothName()
                    _activeDevice.value = AudioDevice.BluetoothSco(name)
                    _routedOutput.value = RoutedDeviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Bluetooth", name)
                    _routerState.value = RouterState.ACTIVE
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (isScoStartRequested && scoConnectRetries < MAX_SCO_RETRIES) {
                        Log.w(TAG, "Legacy SCO disconnected unexpectedly — retry ${scoConnectRetries + 1}/$MAX_SCO_RETRIES")
                        scoConnectRetries++
                        scope.launch {
                            delay(800L * scoConnectRetries)
                            gateway.startBluetoothSco()
                        }
                    } else {
                        Log.e(TAG, "Legacy SCO failed after $MAX_SCO_RETRIES retries — fallback to Speaker")
                        isScoStartRequested = false
                        gateway.stopBluetoothSco()
                        gateway.isBluetoothScoOn = false
                        reEvaluatePriority(skipBluetooth = true)
                    }
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    Log.e(TAG, "Legacy SCO error state")
                    isScoStartRequested = false
                    gateway.stopBluetoothSco()
                    gateway.isBluetoothScoOn = false
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
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            @Suppress("DEPRECATION")
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }

        if (context != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        }

        gateway.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))

        // API 31+: Register OnCommunicationDeviceChangedListener to receive actual hardware routing confirmations
        val executor = if (context != null) ContextCompat.getMainExecutor(context) else Executor { runnable -> runnable.run() }
        gateway.registerCommunicationDeviceListener(executor) { desc ->
            onCommunicationDeviceChanged(desc)
        }

        if (context != null && hasBluetoothConnectPermission()) {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val btAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            try {
                btAdapter?.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.HEADSET)
            } catch (e: SecurityException) {
                Log.e(TAG, "BLUETOOTH_CONNECT not granted, skipping BT profile proxy", e)
            }
        }

        audioFocusManager?.onFocusRegained = {
            Log.d(TAG, "Audio focus regained — re-evaluating route")
            reEvaluatePriority()
            onFocusRegained?.invoke()
        }
        audioFocusManager?.requestFocus()

        gateway.mode = AudioManager.MODE_IN_COMMUNICATION

        try {
            val maxVol = gateway.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val curVol = gateway.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (curVol <= 0 && maxVol > 0) {
                gateway.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.75f).toInt().coerceAtLeast(1), 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to verify call stream volume: ${e.message}")
        }

        reEvaluatePriority()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        Log.d(TAG, "AudioDeviceRouter stopping")
        _routerState.value = RouterState.IDLE

        deviceChangeJob?.cancel()
        deviceChangeJob = null
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        verificationLoggingJob?.cancel()
        verificationLoggingJob = null

        if (context != null) {
            try { context.unregisterReceiver(wiredHeadsetReceiver) } catch (_: Exception) {}
            try { context.unregisterReceiver(scoStateReceiver) } catch (_: Exception) {}
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val btAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            btAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
            bluetoothHeadset = null
        }
        try { gateway.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        gateway.unregisterCommunicationDeviceListener()

        gateway.clearCommunicationDevice()
        gateway.stopBluetoothSco()
        gateway.isBluetoothScoOn = false
        gateway.isSpeakerphoneOn = false

        audioFocusManager?.onFocusRegained = null
        audioFocusManager?.abandonFocus()

        gateway.mode = AudioManager.MODE_NORMAL
        _activeDevice.value = AudioDevice.Earpiece
        _requestedDevice.value = AudioDevice.Earpiece
    }

    fun setAudioRoute(routeType: String) {
        Log.d(TAG, "Setting manual audio route override: $routeType")
        manualOverrideRoute = routeType
        reEvaluatePriority()
    }

    fun reEvaluatePriority(skipBluetooth: Boolean = false) {
        if (gateway.mode != AudioManager.MODE_IN_COMMUNICATION) {
            gateway.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        if (gateway.isModernRoutingSupported()) {
            reEvaluateModern(skipBluetooth)
        } else {
            reEvaluateLegacy(skipBluetooth)
        }
    }

    private fun reEvaluateModern(skipBluetooth: Boolean) {
        confirmationTimeoutJob?.cancel()
        verificationLoggingJob?.cancel()

        val availableDevices = gateway.getAvailableCommunicationDevices()
        Log.d(TAG, "Available comm devices: ${availableDevices.map { "${it.type}(${it.productName})" }}")

        val candidateDescriptors = mutableListOf<Pair<AudioDeviceDescriptor, CandidateDevice>>()

        if (!skipBluetooth && manualOverrideRoute != "SPEAKER" && manualOverrideRoute != "WIRED") {
            availableDevices.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }.forEach {
                candidateDescriptors += it to CandidateDevice.Bluetooth(it.id, it.productName)
            }
        }

        if (manualOverrideRoute != "SPEAKER" && manualOverrideRoute != "BLUETOOTH") {
            availableDevices.filter { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                .forEach { candidateDescriptors += it to CandidateDevice.Wired(it.id, it.productName) }
            availableDevices.filter { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                .forEach { candidateDescriptors += it to CandidateDevice.Wired(it.id, it.productName) }
        }

        val speakerDesc = availableDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val speakerCandidate = CandidateDevice.Speaker(speakerDesc?.id ?: 999, "Speaker")

        if (manualOverrideRoute == "SPEAKER") {
            gateway.clearCommunicationDevice()
            gateway.isSpeakerphoneOn = true
            if (speakerDesc != null) {
                gateway.setCommunicationDevice(speakerDesc)
            }
            _activeDevice.value = AudioDevice.Speakerphone
            _requestedDevice.value = AudioDevice.Speakerphone
            _routedOutput.value = RoutedDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speakerphone", "Loudspeaker")
            _routerState.value = RouterState.ACTIVE
            return
        }

        val candidatesForEngine = candidateDescriptors.map { it.second }
        val decision = decisionEngine.startRouting(candidatesForEngine)

        lastCandidateDescriptors = candidateDescriptors
        lastSpeakerDesc = speakerDesc

        applyRoutingDecision(decision, candidateDescriptors, speakerDesc)
    }

    fun triggerConfirmationTimeoutForTest() {
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        onTimeoutFallbackToNext(lastCandidateDescriptors, lastSpeakerDesc)
    }

    private fun applyRoutingDecision(
        decision: RoutingDecision,
        candidateDescriptors: List<Pair<AudioDeviceDescriptor, CandidateDevice>>,
        speakerDesc: AudioDeviceDescriptor?
    ) {
        when (decision.state) {
            RoutingState.PENDING_CONFIRMATION -> {
                val target = decision.activeDevice
                val descriptorPair = candidateDescriptors.firstOrNull { it.second.id == target.id }
                if (descriptorPair == null) {
                    onTimeoutFallbackToNext(candidateDescriptors, speakerDesc)
                    return
                }

                val (desc, _) = descriptorPair
                val requested = mapDescriptorToAudioDevice(desc)
                _requestedDevice.value = requested
                _routerState.value = RouterState.CONNECTING_BT

                Log.d(TAG, "Requesting communicationDevice: ${desc.productName} (id=${desc.id}, type=${desc.type})")
                val requestAccepted = gateway.setCommunicationDevice(desc)

                if (!requestAccepted) {
                    Log.w(TAG, "setCommunicationDevice rejected id=${desc.id}, advancing to next candidate")
                    onTimeoutFallbackToNext(candidateDescriptors, speakerDesc)
                    return
                }

                // Launch 500ms, 1500ms, 3000ms logging as required by B7
                startVerificationLogging(desc)

                // Check if already matching
                val currentComm = gateway.getCommunicationDevice()
                if (currentComm?.id == desc.id) {
                    Log.d(TAG, "Communication device matched immediately: id=${desc.id}")
                    val confirmDecision = decisionEngine.confirm(desc.id)
                    handleConfirmedDecision(confirmDecision, desc)
                } else {
                    Log.d(TAG, "Request accepted; waiting for OnCommunicationDeviceChanged confirmation timeout ~3s")
                    confirmationTimeoutJob = scope.launch {
                        delay(3000)
                        Log.w(TAG, "Confirmation timed out after 3s for ${desc.productName} (id=${desc.id})")
                        onTimeoutFallbackToNext(candidateDescriptors, speakerDesc)
                    }
                }
            }
            RoutingState.CONFIRMED -> {
                val desc = candidateDescriptors.firstOrNull { it.second.id == decision.activeDevice.id }?.first
                handleConfirmedDecision(decision, desc)
            }
            RoutingState.FALLBACK, RoutingState.IDLE -> {
                fallbackToLoudspeaker(speakerDesc)
            }
        }
    }

    private fun onTimeoutFallbackToNext(
        candidateDescriptors: List<Pair<AudioDeviceDescriptor, CandidateDevice>>,
        speakerDesc: AudioDeviceDescriptor?
    ) {
        val nextDecision = decisionEngine.onTimeout()
        applyRoutingDecision(nextDecision, candidateDescriptors, speakerDesc)
    }

    private fun handleConfirmedDecision(decision: RoutingDecision, desc: AudioDeviceDescriptor?) {
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null

        val device = if (desc != null) mapDescriptorToAudioDevice(desc) else mapCandidateDeviceToAudio(decision.activeDevice)
        _activeDevice.value = device
        _routerState.value = RouterState.ACTIVE

        val typeName = getDeviceTypeName(desc?.type ?: AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        _routedOutput.value = RoutedDeviceInfo(
            type = desc?.type ?: AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            typeName = typeName,
            productName = desc?.productName ?: decision.activeDevice.name
        )

        Log.i(TAG, "Audio route CONFIRMED ACTIVE: ${device.displayName()} (id=${decision.activeDevice.id})")

        if (decision.requiresRepublish) {
            Log.i(TAG, "Late confirmation detected — triggering LiveKit audio track republish")
            _republishRequests.tryEmit(Unit)
        }
    }

    private fun fallbackToLoudspeaker(speakerDesc: AudioDeviceDescriptor?) {
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null

        Log.w(TAG, "All candidates exhausted — falling back to Loudspeaker (AudioDevice.Speakerphone, NEVER Earpiece)")
        gateway.clearCommunicationDevice()
        gateway.isSpeakerphoneOn = true
        if (speakerDesc != null) {
            gateway.setCommunicationDevice(speakerDesc)
        }

        _activeDevice.value = AudioDevice.Speakerphone
        _requestedDevice.value = AudioDevice.Speakerphone
        _routedOutput.value = RoutedDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speakerphone", "Loudspeaker")
        _routerState.value = RouterState.ACTIVE
    }

    /**
     * Called when framework OnCommunicationDeviceChangedListener fires.
     */
    fun onCommunicationDeviceChanged(device: AudioDeviceDescriptor?) {
        Log.d(TAG, "Communication device changed event: ${device?.productName} (id=${device?.id}, type=${device?.type})")
        if (device != null) {
            _routedOutput.value = RoutedDeviceInfo(
                type = device.type,
                typeName = getDeviceTypeName(device.type),
                productName = device.productName
            )
            val decision = decisionEngine.confirm(device.id)
            if (decision.state == RoutingState.CONFIRMED) {
                handleConfirmedDecision(decision, device)
            }
        }
    }

    /**
     * B7: Logging (tag AudioDeviceRouter): requested device, communicationDevice
     * after 500 ms / 1500 ms / 3000 ms, AudioRecord.routedDevice type and AudioTrack routedDevice type.
     */
    private fun startVerificationLogging(requested: AudioDeviceDescriptor) {
        verificationLoggingJob?.cancel()
        verificationLoggingJob = scope.launch {
            delay(500)
            logDiagnosticsCheck("500ms", requested)
            delay(1000)
            logDiagnosticsCheck("1500ms", requested)
            delay(1500)
            logDiagnosticsCheck("3000ms", requested)
        }
    }

    private fun logDiagnosticsCheck(elapsed: String, requested: AudioDeviceDescriptor) {
        val currentComm = gateway.getCommunicationDevice()
        Log.d(TAG, "[$elapsed Check] requested=${requested.productName}(id=${requested.id}), commDevice=${currentComm?.let { "${it.productName}(id=${it.id}, type=${it.type})" } ?: "none"}, routedInput=${_routedInput.value.typeName}(${_routedInput.value.productName}), routedOutput=${_routedOutput.value.typeName}(${_routedOutput.value.productName})")
    }

    private fun reEvaluateLegacy(skipBluetooth: Boolean) {
        val btConnected = !skipBluetooth && (hasConnectedBluetoothHeadset() || hasBluetoothA2dpOrHeadset())
        if (btConnected && manualOverrideRoute != "SPEAKER" && manualOverrideRoute != "WIRED") {
            _requestedDevice.value = AudioDevice.BluetoothSco(getConnectedBluetoothName())
            _routerState.value = RouterState.CONNECTING_BT
            isScoStartRequested = true
            scoConnectRetries = 0
            Log.d(TAG, "Legacy: requesting Bluetooth SCO carrier")
            gateway.startBluetoothSco()
        } else if (manualOverrideRoute == "WIRED") {
            gateway.stopBluetoothSco()
            gateway.isBluetoothScoOn = false
            gateway.isSpeakerphoneOn = false
            _activeDevice.value = AudioDevice.WiredHeadset
            _requestedDevice.value = AudioDevice.WiredHeadset
            _routedOutput.value = RoutedDeviceInfo(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Wired Headset", "Wired Audio")
            _routerState.value = RouterState.ACTIVE
        } else {
            gateway.stopBluetoothSco()
            gateway.isBluetoothScoOn = false
            gateway.isSpeakerphoneOn = true
            _activeDevice.value = AudioDevice.Speakerphone
            _requestedDevice.value = AudioDevice.Speakerphone
            _routedOutput.value = RoutedDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speakerphone", "Loudspeaker")
            _routerState.value = RouterState.ACTIVE
        }
    }

    fun onInputRoutingChanged(deviceInfo: AudioDeviceInfo?) {
        if (deviceInfo != null) {
            _routedInput.value = RoutedDeviceInfo(
                type = deviceInfo.type,
                typeName = getDeviceTypeName(deviceInfo.type),
                productName = deviceInfo.productName?.toString() ?: "Phone Mic"
            )
            Log.d(TAG, "AudioRecord input routedDevice: type=${deviceInfo.type} (${_routedInput.value.typeName}), name=${_routedInput.value.productName}")
        }
    }

    fun onOutputRoutingChanged(deviceInfo: AudioDeviceInfo?) {
        if (deviceInfo != null) {
            _routedOutput.value = RoutedDeviceInfo(
                type = deviceInfo.type,
                typeName = getDeviceTypeName(deviceInfo.type),
                productName = deviceInfo.productName?.toString() ?: "Phone Speaker"
            )
            Log.d(TAG, "AudioTrack output routedDevice: type=${deviceInfo.type} (${_routedOutput.value.typeName}), name=${_routedOutput.value.productName}")
        }
    }

    private fun mapDescriptorToAudioDevice(desc: AudioDeviceDescriptor): AudioDevice {
        return when (desc.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID -> AudioDevice.BluetoothSco(desc.productName)
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioDevice.WiredHeadset
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> AudioDevice.UsbAudio
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDevice.Speakerphone
            else -> AudioDevice.BluetoothSco(desc.productName)
        }
    }

    private fun mapCandidateDeviceToAudio(candidate: CandidateDevice): AudioDevice {
        return when (candidate) {
            is CandidateDevice.Bluetooth -> AudioDevice.BluetoothSco(candidate.name)
            is CandidateDevice.Wired -> AudioDevice.WiredHeadset
            is CandidateDevice.Speaker -> AudioDevice.Speakerphone
        }
    }

    fun getDeviceTypeName(type: Int?): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speakerphone"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone Mic"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            else -> "Built-in"
        }
    }

    private fun hasConnectedBluetoothHeadset(): Boolean {
        if (context == null || !hasBluetoothConnectPermission()) return false
        return try {
            bluetoothHeadset?.connectedDevices?.isNotEmpty() == true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun hasBluetoothA2dpOrHeadset(): Boolean {
        if (context == null || !hasBluetoothConnectPermission()) return false
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) return false

        return try {
            val a2dp = btAdapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
            val headset = btAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
            a2dp || headset
        } catch (_: SecurityException) {
            false
        }
    }

    private fun getConnectedBluetoothName(): String {
        if (context == null || !hasBluetoothConnectPermission()) return "Bluetooth"
        return try {
            bluetoothHeadset?.connectedDevices?.firstOrNull()?.name ?: "Bluetooth Headset"
        } catch (_: SecurityException) {
            "Bluetooth Headset"
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (context == null) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

// ── Data types ────────────────────────────────────────────────────────────────

sealed class AudioDevice {
    data class BluetoothSco(val deviceName: String) : AudioDevice()
    object WiredHeadset : AudioDevice()
    object UsbAudio : AudioDevice()
    object Earpiece : AudioDevice()
    object Speakerphone : AudioDevice()

    fun displayName(): String = when (this) {
        is BluetoothSco -> deviceName
        is WiredHeadset -> "Wired headset"
        is UsbAudio     -> "USB audio"
        is Earpiece     -> "Built-in earpiece"
        is Speakerphone -> "Phone speaker"
    }

    fun isHandsFree(): Boolean = this is BluetoothSco || this is WiredHeadset || this is UsbAudio
}

enum class RouterState { IDLE, SCANNING, CONNECTING_BT, ACTIVE, FAILED }
