package app.mate.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusAwakeTest {

    private fun state(phase: FocusTimer.Phase, running: Boolean, remainingMs: Long = 60_000L) =
        FocusTimer.State(phase, 25 * 60_000L, remainingMs, running, 0)

    @Test
    fun `l'ecran reste eveille pendant la concentration qui tourne`() {
        assertTrue(FocusAwake.wanted(state(FocusTimer.Phase.FOCUS, running = true)))
    }

    @Test
    fun `la veille revient en pause du chrono, a la pause et hors Focus`() {
        assertFalse(FocusAwake.wanted(state(FocusTimer.Phase.FOCUS, running = false)))
        assertFalse(FocusAwake.wanted(state(FocusTimer.Phase.BREAK, running = true)))
    }

    @Test
    fun `le temps restant est arrondi a la minute au dessus`() {
        assertEquals(1, FocusAwake.minutesLeft(state(FocusTimer.Phase.FOCUS, true, 1L)))
        assertEquals(2, FocusAwake.minutesLeft(state(FocusTimer.Phase.FOCUS, true, 60_001L)))
    }
}
