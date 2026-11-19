package com.ridervoice.network

import android.content.Context
import com.ridervoice.audio.AudioDevice
import com.ridervoice.audio.AudioDeviceRouter
import com.ridervoice.audio.VoxEngine
import com.ridervoice.state.ActiveRideRepository
import com.ridervoice.state.AppEvents
import com.ridervoice.utils.RvLog
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP-154 Online LiveKit Compatibility Regression Contract:
 * Verifies that audio route changes while actively speaking (VOX open or PTT held) OR within
 * the 3s cooldown NEVER interrupt active speech mid-sentence and NEVER silently drop the
 * pending route republish.
 */
class LiveKitRouteRepublishContractTest {

    @Before
    fun setUp() {
        RvLog.resetAudioCounters()
    }

    @Test
    fun routeChangeDuringActiveVox_isDeferredWithoutDroppingTrack_andExecutesAfterSilenceAndCooldown() {
        val context = mockk<Context>(relaxed = true)
        val audioDeviceRouter = mockk<AudioDeviceRouter>(relaxed = true)
        val voxEngine = mockk<VoxEngine>(relaxed = true)
        val apiService = mockk<ApiService>(relaxed = true)
        val activeRideRepo = mockk<ActiveRideRepository>(relaxed = true)
        val appEvents = AppEvents()

        val manager = LiveKitManager(
            context = context,
            audioDeviceRouter = audioDeviceRouter,
            voxEngine = voxEngine,
            apiService = apiService,
            activeRideRepository = activeRideRepo,
            appEvents = appEvents
        )

        val mockTrack = mockk<LocalAudioTrack>(relaxed = true)
        val newTrack = mockk<LocalAudioTrack>(relaxed = true)
        val mockLp = mockk<LocalParticipant>(relaxed = true)
        val mockRoom = mockk<Room>(relaxed = true)

        every { mockRoom.localParticipant } returns mockLp
        every { mockLp.createAudioTrack(name = any(), options = any()) } returns newTrack
        coEvery { mockLp.publishAudioTrack(any(), any()) } returns true

        // Inject active connected room + track + PHONE initial options with expired cooldown
        setField(manager, "room", mockRoom)
        setField(manager, "localAudioTrack", mockTrack)
        setField(manager, "lastOptions", LocalAudioTrackOptions(noiseSuppression = true, echoCancellation = true, autoGainControl = true, highPassFilter = true, typingNoiseDetection = false))
        setField(manager, "lastPublishTime", System.currentTimeMillis() - 5_000L)

        // Open VOX gate -> rider is actively speaking on PHONE route
        manager.onVoiceGate(true)

        // Route switches to Bluetooth SCO while VOX is open: must DEFER (not unpublish/stop active track mid-sentence!)
        val btDevice = AudioDevice.BluetoothSco(deviceName = "Cardo Packtalk")
        manager.applyRouteOptions(btDevice)

        verify(exactly = 0) { runCatching { mockTrack.stop() } }
        assertEquals(0L, RvLog.routeRepublishCount.get())

        // Rider finishes speaking (VOX gate closes) -> deferred job must wake up via txGateBusy and republish for BT_SCO
        manager.onVoiceGate(false)

        val deadline = System.currentTimeMillis() + 3_000L
        while (RvLog.routeRepublishCount.get() == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("Deferred route republish must execute after VOX closes", RvLog.routeRepublishCount.get() >= 1L)
        verify(atLeast = 1) { voxEngine.setOpenRatio(any()) }
    }

    @Test
    fun routeChangeWithin3sCooldown_isNotSilentlyDropped() {
        val context = mockk<Context>(relaxed = true)
        val audioDeviceRouter = mockk<AudioDeviceRouter>(relaxed = true)
        val voxEngine = mockk<VoxEngine>(relaxed = true)
        val apiService = mockk<ApiService>(relaxed = true)
        val activeRideRepo = mockk<ActiveRideRepository>(relaxed = true)
        val appEvents = AppEvents()

        val manager = LiveKitManager(context, audioDeviceRouter, voxEngine, apiService, activeRideRepo, appEvents)
        val mockTrack = mockk<LocalAudioTrack>(relaxed = true)
        val newTrack = mockk<LocalAudioTrack>(relaxed = true)
        val mockLp = mockk<LocalParticipant>(relaxed = true)
        val mockRoom = mockk<Room>(relaxed = true)

        every { mockRoom.localParticipant } returns mockLp
        every { mockLp.createAudioTrack(name = any(), options = any()) } returns newTrack
        coEvery { mockLp.publishAudioTrack(any(), any()) } returns true

        setField(manager, "room", mockRoom)
        setField(manager, "localAudioTrack", mockTrack)
        setField(manager, "lastOptions", LocalAudioTrackOptions(noiseSuppression = true, echoCancellation = true, autoGainControl = true, highPassFilter = true, typingNoiseDetection = false))
        // Simulate publish happened 2.6 seconds ago (still inside 3s cooldown!)
        setField(manager, "lastPublishTime", System.currentTimeMillis() - 2_600L)

        manager.applyRouteOptions(AudioDevice.BluetoothSco("Sena 50S"))

        // Immediately after call, within cooldown -> deferred
        val deadline = System.currentTimeMillis() + 2_000L
        while (RvLog.routeRepublishCount.get() == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("Route republish arriving within 3s cooldown must be retried after cooldown expires, not dropped", RvLog.routeRepublishCount.get() >= 1L)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
