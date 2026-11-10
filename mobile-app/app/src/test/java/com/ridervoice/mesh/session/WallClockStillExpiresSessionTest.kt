package com.ridervoice.mesh.session

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WallClockStillExpiresSessionTest {

    @Test
    fun wallClockAdvancingPastExpiry_expiresSession() {
        var wallClock = 1_000_000L
        var monotonicClock = 10_000L

        val session = OfflineSession.create(now = wallClock)
        val selfId = UUID.randomUUID().toString()
        val peerId = UUID.randomUUID().toString()

        val sm = SessionManager(
            session = session,
            selfId = selfId,
            clock = { wallClock },
            monotonicClock = { monotonicClock }
        )

        assertFalse("Session must not be expired initially", session.isExpired(wallClock))
        assertEquals(SessionManager.Admission.ADMITTED, sm.admit(peerId))

        // Advance wall clock past the 8-hour session lifetime
        wallClock += MeshConfig.SESSION_LIFETIME_MS + 1000L
        monotonicClock += 60_000L

        assertTrue("Session must be expired by wall clock", session.isExpired(wallClock))
        val newPeer = UUID.randomUUID().toString()
        assertEquals("SessionManager must reject admission when expired", SessionManager.Admission.REJECTED_EXPIRED, sm.admit(newPeer))
    }
}

