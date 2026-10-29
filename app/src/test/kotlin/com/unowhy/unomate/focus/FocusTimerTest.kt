package com.unowhy.unomate.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sur la JVM, SystemClock reste à 0 : le temps ne s'écoule pas, seules les transitions sont vérifiées. */
class FocusTimerTest {

    @Before
    fun reset() {
        FocusTimer.setPreset(25, 5)
    }

    @Test
    fun `etat initial`() {
        val s = FocusTimer.state
        assertEquals(FocusTimer.Phase.FOCUS, s.phase)
        assertEquals(25 * 60_000L, s.durationMs)
        assertEquals(25 * 60_000L, s.remainingMs)
        assertFalse(s.running)
    }

    @Test
    fun `toggle demarre puis met en pause en gardant le temps restant`() {
        FocusTimer.toggle()
        assertTrue(FocusTimer.state.running)
        FocusTimer.toggle()
        assertFalse(FocusTimer.state.running)
        assertEquals(25 * 60_000L, FocusTimer.state.remainingMs)
    }

    @Test
    fun `skip passe a la pause et la lance si le chrono tournait`() {
        FocusTimer.toggle()
        FocusTimer.skip()
        val s = FocusTimer.state
        assertEquals(FocusTimer.Phase.BREAK, s.phase)
        assertEquals(5 * 60_000L, s.durationMs)
        assertTrue(s.running)
        FocusTimer.skip()
        assertEquals(FocusTimer.Phase.FOCUS, FocusTimer.state.phase)
    }

    @Test
    fun `skip a l'arret laisse le chrono arrete`() {
        FocusTimer.skip()
        assertEquals(FocusTimer.Phase.BREAK, FocusTimer.state.phase)
        assertFalse(FocusTimer.state.running)
    }

    @Test
    fun `setPreset change les durees et revient a la concentration`() {
        FocusTimer.skip()
        FocusTimer.setPreset(50, 10)
        assertEquals(50, FocusTimer.focusMinutes)
        assertEquals(10, FocusTimer.breakMinutes)
        assertEquals(FocusTimer.Phase.FOCUS, FocusTimer.state.phase)
        assertEquals(50 * 60_000L, FocusTimer.state.remainingMs)
    }

    @Test
    fun `reset arrete et remet la duree`() {
        FocusTimer.toggle()
        FocusTimer.skip()
        FocusTimer.reset()
        assertEquals(FocusTimer.Phase.FOCUS, FocusTimer.state.phase)
        assertFalse(FocusTimer.state.running)
        assertEquals(25 * 60_000L, FocusTimer.state.remainingMs)
    }

    @Test
    fun `les observateurs recoivent l'etat`() {
        val seen = mutableListOf<Boolean>()
        val listener: (FocusTimer.State) -> Unit = { seen += it.running }
        FocusTimer.observe(listener)
        FocusTimer.toggle()
        FocusTimer.unobserve(listener)
        FocusTimer.toggle()
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `format arrondit a la seconde superieure`() {
        assertEquals("25:00", FocusTimer.format(25 * 60_000L))
        assertEquals("00:01", FocusTimer.format(1))
        assertEquals("00:00", FocusTimer.format(0))
        assertEquals("01:30", FocusTimer.format(89_001))
    }

    @Test
    fun `restore ne s'applique qu'une fois par processus`() {
        FocusTimer.restore(40, 8)
        val first = FocusTimer.focusMinutes to FocusTimer.breakMinutes
        FocusTimer.toggle()
        FocusTimer.restore(15, 3)
        // Une activité recréée ne remet pas le chrono à zéro.
        assertTrue(FocusTimer.state.running)
        assertEquals(first, FocusTimer.focusMinutes to FocusTimer.breakMinutes)
        FocusTimer.toggle()
    }
}
