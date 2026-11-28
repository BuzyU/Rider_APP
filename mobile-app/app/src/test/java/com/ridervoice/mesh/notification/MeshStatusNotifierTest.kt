package com.ridervoice.mesh.notification

import android.content.Context
import com.ridervoice.R
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshStatusNotifierTest {

    private fun testStrings(resId: Int, args: Array<out Any>): String = when (resId) {
        R.string.mesh_status_notif_channel_name -> "Convoy Status Alerts"
        R.string.mesh_convoy_starting_offline -> "Switching convoy to offline voice…"
        R.string.mesh_convoy_offline_active -> "Convoy switched to offline voice"
        R.string.mesh_convoy_starting_online -> "Reconnecting convoy to online voice…"
        R.string.mesh_convoy_online_active -> "Convoy back on online voice"
        R.string.mesh_convoy_wifi_off -> "Wi-Fi is off — offline voice stopped"
        R.string.mesh_convoy_link_failed -> "Automatic link failed after ${args.firstOrNull()} attempts"
        else -> ""
    }

    @Test
    fun notify_wifiOff_triggersCriticalHapticAndVoiceStreamCue() {
        val posted = mutableListOf<Pair<String, String>>()
        val pulses = mutableListOf<LongArray>()
        val cues = mutableListOf<Triple<MeshStatusEvent, String, Boolean>>()

        val notifier = MeshStatusNotifier(
            context = mockk<Context>(relaxed = true),
            audioCuePlayer = object : MeshAudioCuePlayer {
                override fun playCue(event: MeshStatusEvent, spokenText: String, isDeafened: Boolean) {
                    cues.add(Triple(event, spokenText, isDeafened))
                }
            },
            notificationPort = object : StatusNotificationPort {
                override fun notify(title: String, body: String) {
                    posted.add(title to body)
                }
            },
            hapticPort = object : HapticPort {
                override fun pulse(timingsMs: LongArray) {
                    pulses.add(timingsMs)
                }
            },
            stringResolver = ::testStrings
        )

        notifier.notify(MeshStatusEvent.WIFI_OFF, isDeafened = false)

        assertEquals(1, posted.size)
        assertEquals("Convoy Status Alerts", posted[0].first)
        assertEquals("Wi-Fi is off — offline voice stopped", posted[0].second)
        assertEquals(1, pulses.size)
        assertArrayEquals(longArrayOf(0L, 50L, 60L, 50L, 60L, 50L), pulses[0])
        assertEquals(1, cues.size)
        assertEquals(MeshStatusEvent.WIFI_OFF, cues[0].first)
        assertEquals(false, cues[0].third)
    }

    @Test
    fun notify_offlineActive_suppressedAudioWhenDeafened() {
        val posted = mutableListOf<Pair<String, String>>()
        val cues = mutableListOf<Boolean>()
        val context = mockk<Context>(relaxed = true)
        val audioCuePlayer = AndroidMeshAudioCuePlayer(context)

        val notifier = MeshStatusNotifier(
            context = context,
            audioCuePlayer = object : MeshAudioCuePlayer {
                override fun playCue(event: MeshStatusEvent, spokenText: String, isDeafened: Boolean) {
                    cues.add(isDeafened)
                    audioCuePlayer.playCue(event, spokenText, isDeafened)
                }
            },
            notificationPort = object : StatusNotificationPort {
                override fun notify(title: String, body: String) {
                    posted.add(title to body)
                }
            },
            hapticPort = object : HapticPort {
                override fun pulse(timingsMs: LongArray) {}
            },
            stringResolver = ::testStrings
        )

        notifier.notify(MeshStatusEvent.OFFLINE_ACTIVE, isDeafened = true)
        assertEquals(1, posted.size)
        assertEquals(listOf(true), cues)
        audioCuePlayer.release()
    }

    @Test
    fun notify_linkFailed_passesAttemptCountToString() {
        val posted = mutableListOf<String>()
        val pulses = mutableListOf<LongArray>()
        val notifier = MeshStatusNotifier(
            context = mockk<Context>(relaxed = true),
            audioCuePlayer = NoOpMeshAudioCuePlayer,
            notificationPort = object : StatusNotificationPort {
                override fun notify(title: String, body: String) {
                    posted.add(body)
                }
            },
            hapticPort = object : HapticPort {
                override fun pulse(timingsMs: LongArray) {
                    pulses.add(timingsMs)
                }
            },
            stringResolver = ::testStrings
        )

        notifier.notify(MeshStatusEvent.LINK_FAILED, attempts = 8)
        assertTrue(posted.single().contains("8"))
        assertArrayEquals(longArrayOf(0L, 60L, 80L, 60L), pulses.single())
    }
}
