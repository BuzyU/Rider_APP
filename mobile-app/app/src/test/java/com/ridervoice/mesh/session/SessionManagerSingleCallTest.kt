package com.ridervoice.mesh.session

import com.ridervoice.mesh.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SessionManagerSingleCallTest {

    @Test
    fun admitAndTouch_admitsBothDevicesInSingleCall() {
        var now = 10_000L
        val session = OfflineSession.create(10_000L)
        val selfId = UUID.randomUUID().toString()
        val peer1 = UUID.randomUUID().toString()
        val peer2 = UUID.randomUUID().toString()

        val sm = SessionManager(session, selfId, clock = { now }, monotonicClock = { now })

        // Joint call admits both
        val admission = sm.admitAndTouch(peer1, peer2)
        assertEquals(SessionManager.Admission.ADMITTED, admission)
        assertTrue("Peer 1 must be admitted", sm.members().contains(peer1))
        assertTrue("Peer 2 must be admitted", sm.members().contains(peer2))

        // Subsequent call updates touch time and returns ALREADY_MEMBER
        now += 500L
        val already = sm.admitAndTouch(peer1, peer2)
        assertEquals(SessionManager.Admission.ALREADY_MEMBER, already)

        // Advance past MEMBER_TIMEOUT_MS and >= 1s purge interval
        now += MeshConfig.MEMBER_TIMEOUT_MS + 2_000L
        val membersAfterPurge = sm.members()
        assertTrue("Stale members must be purged", !membersAfterPurge.contains(peer1) && !membersAfterPurge.contains(peer2))
        assertEquals("Only self must remain", setOf(selfId), membersAfterPurge)
    }
}

