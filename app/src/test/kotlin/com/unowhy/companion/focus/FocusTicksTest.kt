package com.unowhy.companion.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusTicksTest {

    private val min = 60_000L
    private val session = 25 * min

    @Test
    fun `cadran eteint tant que le chrono ne tourne pas`() {
        assertEquals(0, FocusTicks.lit(session, session, running = false))
        assertEquals(0, FocusTicks.lit(18 * min, session, running = false))
    }

    @Test
    fun `une graduation par minute entamee`() {
        assertEquals(25, FocusTicks.lit(session, session, running = true))
        assertEquals(19, FocusTicks.lit(18 * min + 5_000, session, running = true))
        assertEquals(18, FocusTicks.lit(18 * min, session, running = true))
        assertEquals(1, FocusTicks.lit(1_000, session, running = true))
        assertEquals(0, FocusTicks.lit(0, session, running = true))
    }

    @Test
    fun `seance d une autre duree ramenee aux 25 graduations`() {
        assertEquals(25, FocusTicks.lit(5 * min, 5 * min, running = true))
        assertEquals(13, FocusTicks.lit(150_000, 5 * min, running = true))
    }

    @Test
    fun `duree nulle sans plantage`() {
        assertEquals(0, FocusTicks.lit(0, 0, running = true))
    }
}
