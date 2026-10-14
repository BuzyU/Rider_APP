package com.ridervoice.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

class AudioDeviceRouterTest {

    private lateinit var fakeGateway: FakeAudioManagerGateway
    private lateinit var router: AudioDeviceRouter

    private val btAirPods = AudioDeviceDescriptor(
        id = 101,
        type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        productName = "AirPods Pro"
    )

    private val wiredHeadset = AudioDeviceDescriptor(
        id = 202,
        type = AudioDeviceInfo.TYPE_WIRED_HEADSET,
        productName = "Wired Headset"
    )

    private val speaker = AudioDeviceDescriptor(
        id = 999,
        type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        productName = "Built-in Speaker"
    )

    @Before
    fun setUp() {
        fakeGateway = FakeAudioManagerGateway()
        fakeGateway.modernRoutingSupported = true
        router = AudioDeviceRouter(gateway = fakeGateway, scope = CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun `request accepted but communicationDevice id does not match yields NOT active`() {
        fakeGateway.availableDevices.addAll(listOf(btAirPods, speaker))
        // AudioManager.setCommunicationDevice returns true (request accepted by framework)
        fakeGateway.setCommunicationDeviceReturn = true
        // But actual communicationDevice is NOT matching (hardware route hasn't confirmed yet)
        fakeGateway.currentCommunicationDevice = null

        router.reEvaluatePriority()

        // 1. setCommunicationDevice was called for AirPods (id 101)
        assertEquals(1, fakeGateway.setCommunicationDeviceCalls.size)
        assertEquals(101, fakeGateway.setCommunicationDeviceCalls.first().id)

        // 2. Requested device is Bluetooth
        assertEquals(AudioDevice.BluetoothSco("AirPods Pro"), router.requestedDevice.value)

        // 3. Router state is CONNECTING_BT, strictly NOT ACTIVE
        assertEquals(RouterState.CONNECTING_BT, router.routerState.value)
        assertNotEquals(RouterState.ACTIVE, router.routerState.value)

        // 4. Active device is NOT yet BluetoothSco
        assertFalse(
            "Device must not be marked ACTIVE until confirmed by hardware id match",
            router.activeDevice.value is AudioDevice.BluetoothSco
        )
    }

    @Test
    fun `matching communicationDevice id yields ACTIVE`() {
        fakeGateway.availableDevices.addAll(listOf(btAirPods, speaker))
        fakeGateway.setCommunicationDeviceReturn = true
        fakeGateway.currentCommunicationDevice = null

        router.reEvaluatePriority()
        assertEquals(RouterState.CONNECTING_BT, router.routerState.value)

        // Hardware callback fires with matching device id 101
        fakeGateway.currentCommunicationDevice = btAirPods
        router.onCommunicationDeviceChanged(btAirPods)

        // Device is now confirmed ACTIVE
        assertEquals(RouterState.ACTIVE, router.routerState.value)
        assertEquals(AudioDevice.BluetoothSco("AirPods Pro"), router.activeDevice.value)
        assertEquals("AirPods Pro", router.routedOutput.value.productName)
    }

    @Test
    fun `no confirmation within timeout yields next candidate then Speakerphone`() {
        fakeGateway.availableDevices.addAll(listOf(btAirPods, wiredHeadset, speaker))
        fakeGateway.setCommunicationDeviceReturn = true
        fakeGateway.currentCommunicationDevice = null

        router.reEvaluatePriority()
        assertEquals(101, fakeGateway.setCommunicationDeviceCalls.last().id)
        assertEquals(AudioDevice.BluetoothSco("AirPods Pro"), router.requestedDevice.value)

        // Timeout on first candidate (AirPods) -> advances to next candidate (Wired Headset)
        router.triggerConfirmationTimeoutForTest()

        assertEquals(202, fakeGateway.setCommunicationDeviceCalls.last().id)
        assertEquals(AudioDevice.WiredHeadset, router.requestedDevice.value)
        assertEquals(RouterState.CONNECTING_BT, router.routerState.value)

        // Timeout on second candidate -> candidates exhausted, falls back to Loudspeaker
        router.triggerConfirmationTimeoutForTest()

        assertEquals(RouterState.ACTIVE, router.routerState.value)
        assertEquals(AudioDevice.Speakerphone, router.activeDevice.value)
        assertNotEquals("Loudspeaker fallback must NEVER be Earpiece", AudioDevice.Earpiece, router.activeDevice.value)
        assertTrue(fakeGateway.isSpeakerphoneOn)
    }

    @Test
    fun `late confirmation yields republish requested`() {
        fakeGateway.availableDevices.addAll(listOf(btAirPods, speaker))
        fakeGateway.setCommunicationDeviceReturn = true
        fakeGateway.currentCommunicationDevice = null

        router.reEvaluatePriority()

        // Time out until fallback to Speakerphone
        router.triggerConfirmationTimeoutForTest()
        assertEquals(AudioDevice.Speakerphone, router.activeDevice.value)

        var republishCount = 0
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            router.republishRequests.collect { republishCount++ }
        }

        // Now Bluetooth hardware carrier finishes and late confirmation arrives for id 101
        fakeGateway.currentCommunicationDevice = btAirPods
        router.onCommunicationDeviceChanged(btAirPods)

        assertEquals(RouterState.ACTIVE, router.routerState.value)
        assertEquals(AudioDevice.BluetoothSco("AirPods Pro"), router.activeDevice.value)
        assertEquals("Late confirmation must trigger republish request", 1, republishCount)

        job.cancel()
    }
}

class FakeAudioManagerGateway : AudioManagerGateway {
    override var mode: Int = AudioManager.MODE_NORMAL
    override var isSpeakerphoneOn: Boolean = false
    override var isBluetoothScoOn: Boolean = false

    var modernRoutingSupported = true
    override fun isModernRoutingSupported(): Boolean = modernRoutingSupported

    val availableDevices = mutableListOf<AudioDeviceDescriptor>()
    var currentCommunicationDevice: AudioDeviceDescriptor? = null
    var setCommunicationDeviceReturn = true

    val setCommunicationDeviceCalls = mutableListOf<AudioDeviceDescriptor>()
    var clearCommunicationDeviceCalled = false

    var onDeviceChangedListener: ((AudioDeviceDescriptor?) -> Unit)? = null

    override fun getAvailableCommunicationDevices(): List<AudioDeviceDescriptor> = availableDevices
    override fun getCommunicationDevice(): AudioDeviceDescriptor? = currentCommunicationDevice

    override fun setCommunicationDevice(device: AudioDeviceDescriptor): Boolean {
        setCommunicationDeviceCalls.add(device)
        return setCommunicationDeviceReturn
    }

    override fun clearCommunicationDevice() {
        clearCommunicationDeviceCalled = true
        currentCommunicationDevice = null
    }

    override fun registerCommunicationDeviceListener(
        executor: Executor,
        onDeviceChanged: (AudioDeviceDescriptor?) -> Unit
    ) {
        onDeviceChangedListener = onDeviceChanged
    }

    override fun unregisterCommunicationDeviceListener() {
        onDeviceChangedListener = null
    }

    override fun startBluetoothSco() {}
    override fun stopBluetoothSco() {}
    override fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?) {}
    override fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback) {}
    override fun getStreamMaxVolume(streamType: Int): Int = 15
    override fun getStreamVolume(streamType: Int): Int = 10
    override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {}
}
