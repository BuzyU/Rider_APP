package com.ridervoice.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {

    @Test
    fun newSequences_accepted() {
        val window = ReplayWindow(maxEntries = 16)
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 1))
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 2))
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 3))
    }

    @Test
    fun duplicates_rejected() {
        val window = ReplayWindow(maxEntries = 16)
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 10))
        assertTrue(window.isDuplicateOrOld(srcShort = 1, epoch = 10, type = 0, seq = 10))
        assertEquals(ReplayResult.DUPLICATE, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 10))
    }

    @Test
    fun reorderInside128_acceptedOnce_thenDuplicate() {
        val window = ReplayWindow(maxEntries = 16)
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 50))
        // Reordered frame behind highest (d = -5, inside 128)
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 45))
        // Second time it must be rejected as duplicate
        assertEquals(ReplayResult.DUPLICATE, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 45))
    }

    @Test
    fun outOfWindow_rejectedAsTooOld() {
        val window = ReplayWindow(maxEntries = 16)
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 200))
        // seq 72 is 128 slots behind 200 (d = -128) -> TOO_OLD
        assertEquals(ReplayResult.TOO_OLD, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 72))
        // seq 50 is 150 slots behind 200 (d = -150) -> TOO_OLD
        assertEquals(ReplayResult.TOO_OLD, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 50))
        assertTrue(window.isDuplicateOrOld(srcShort = 1, epoch = 10, type = 0, seq = 50))
    }

    @Test
    fun epochSeparation_independentWindows() {
        val window = ReplayWindow(maxEntries = 16)
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 100))
        // Same sequence number under different epoch is NEW
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 20, type = 0, seq = 100))
    }

    @Test
    fun typeSeparation_independentWindows() {
        val window = ReplayWindow(maxEntries = 16)
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 5)) // VOICE
        // Same sequence under different packet type (e.g. HELLO = 1) is NEW
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 1, seq = 5))
    }

    @Test
    fun sequence24BitWrapAround_handlesSeamlessly() {
        val window = ReplayWindow(maxEntries = 16)
        val nearWrap = 0xFFFFFE // 16,777,214
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = nearWrap))
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 0xFFFFFF))
        // Wraps to 0
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 0))
        assertEquals(ReplayResult.NEW, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 1))
        // Reordered frame right before wrap
        assertEquals(ReplayResult.DUPLICATE, window.accept(srcShort = 1, epoch = 10, type = 0, seq = 0xFFFFFF))
        // Out of window relative to seq = 1: diff modulo 2^24
        val tooOldBeforeWrap = (1 - 130) and 0xFFFFFF
        assertEquals(ReplayResult.TOO_OLD, window.accept(srcShort = 1, epoch = 10, type = 0, seq = tooOldBeforeWrap))
    }

    @Test
    fun boundedMemory_maxEntriesEnforced() {
        val window = ReplayWindow(maxEntries = 16)
        for (i in 1..20) {
            window.accept(srcShort = i, epoch = 1, type = 0, seq = 1)
        }
        assertEquals("ReplayWindow must bound entry count to maxEntries", 16, window.size())
    }
}

