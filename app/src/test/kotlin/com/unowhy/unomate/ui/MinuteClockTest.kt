package com.unowhy.unomate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinuteClockTest {
    /** 12:00:00.000 d'un jour quelconque : multiple de 60 000 ms, quel que soit le fuseau. */
    private val noon = 1_800_000_000_000L

    @Test
    fun `pile sur la minute il reste une minute entiere`() {
        assertEquals(60_000L, MinuteClock.msToNextMinute(noon))
    }

    @Test
    fun `a la fin de la minute il reste une milliseconde`() {
        assertEquals(1L, MinuteClock.msToNextMinute(noon + 59_999))
    }

    @Test
    fun `a la moitie il reste trente secondes`() {
        assertEquals(30_000L, MinuteClock.msToNextMinute(noon + 30_000))
    }

    @Test
    fun `jamais zero ni plus d'une minute`() {
        for (offset in 0L until 120_000L step 997) {
            val ms = MinuteClock.msToNextMinute(noon + offset)
            assertTrue("offset $offset : $ms", ms in 1..60_000)
        }
    }
}
