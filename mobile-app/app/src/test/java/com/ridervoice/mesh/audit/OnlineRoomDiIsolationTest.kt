package com.ridervoice.mesh.audit

import com.ridervoice.mesh.GateDecision
import com.ridervoice.mesh.RoomHostSelection
import com.ridervoice.mesh.selectRoomHost
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineRoomDiIsolationTest {

    @Test
    fun selectRoomHost_returnsOfflineAware_onlyWhenGateEnabled() {
        val selection = selectRoomHost(GateDecision.ENABLED)
        assertEquals(RoomHostSelection.OFFLINE_AWARE, selection)
    }

    @Test
    fun selectRoomHost_returnsPlainOnline_whenDisabledByBuild() {
        val selection = selectRoomHost(GateDecision.DISABLED_BY_BUILD)
        assertEquals(RoomHostSelection.PLAIN_ONLINE, selection)
    }

    @Test
    fun selectRoomHost_returnsPlainOnline_whenDisabledByUser() {
        val selection = selectRoomHost(GateDecision.DISABLED_BY_USER)
        assertEquals(RoomHostSelection.PLAIN_ONLINE, selection)
    }
}
