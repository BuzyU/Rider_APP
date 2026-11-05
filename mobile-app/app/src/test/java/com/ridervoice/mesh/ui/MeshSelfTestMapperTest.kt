package com.ridervoice.mesh.ui

import com.ridervoice.mesh.link.MeshLinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshSelfTestMapperTest {

    @Test
    fun whenZeroNeighboursFor20SecondsOrLess_noWarningHint() {
        val input = MeshSelfTestInput(
            linkState = MeshLinkState.RUNNING,
            localIp = "192.168.43.10",
            broadcastAddress = "192.168.43.255",
            authenticatedNeighboursHeardLast10s = 0,
            secondsWithZeroNeighbours = 20L
        )

        val state = MeshSelfTestMapper.map(input)

        assertEquals("RUNNING", state.linkStateText)
        assertEquals("Local: 192.168.43.10 | Broadcast: 192.168.43.255", state.localAddressesText)
        assertEquals(0, state.authenticatedNeighboursCount)
        assertNull(state.isolationWarningHint)
    }

    @Test
    fun whenZeroNeighboursForMoreThan20Seconds_showsIsolationWarningHint() {
        val input = MeshSelfTestInput(
            linkState = MeshLinkState.RUNNING,
            localIp = "192.168.43.10",
            broadcastAddress = "192.168.43.255",
            authenticatedNeighboursHeardLast10s = 0,
            secondsWithZeroNeighbours = 21L
        )

        val state = MeshSelfTestMapper.map(input)

        assertEquals(MeshSelfTestMapper.HINT_TEXT, state.isolationWarningHint)
    }

    @Test
    fun whenNeighboursGreaterThanZero_evenAfterLongDuration_noWarningHint() {
        val input = MeshSelfTestInput(
            linkState = MeshLinkState.RUNNING,
            localIp = "192.168.43.10",
            broadcastAddress = "192.168.43.255",
            authenticatedNeighboursHeardLast10s = 2,
            secondsWithZeroNeighbours = 100L
        )

        val state = MeshSelfTestMapper.map(input)

        assertNull(state.isolationWarningHint)
        assertEquals(2, state.authenticatedNeighboursCount)
    }

    @Test
    fun formatsLinkStateAndAddressesCorrectly() {
        val states = mapOf(
            MeshLinkState.STOPPED to "STOPPED",
            MeshLinkState.STARTING to "STARTING",
            MeshLinkState.RUNNING to "RUNNING",
            MeshLinkState.FAILED to "FAILED"
        )

        for ((linkState, expectedText) in states) {
            val state = MeshSelfTestMapper.map(
                MeshSelfTestInput(
                    linkState = linkState,
                    localIp = "10.0.0.5",
                    broadcastAddress = "10.0.0.255",
                    authenticatedNeighboursHeardLast10s = 1,
                    secondsWithZeroNeighbours = 0L
                )
            )
            assertEquals(expectedText, state.linkStateText)
        }
    }

    @Test
    fun handlesNullAddressesGracefully() {
        val input = MeshSelfTestInput(
            linkState = MeshLinkState.STOPPED,
            localIp = null,
            broadcastAddress = null,
            authenticatedNeighboursHeardLast10s = 0,
            secondsWithZeroNeighbours = 0L
        )

        val state = MeshSelfTestMapper.map(input)

        assertEquals("Local: None | Broadcast: None", state.localAddressesText)
    }
}

