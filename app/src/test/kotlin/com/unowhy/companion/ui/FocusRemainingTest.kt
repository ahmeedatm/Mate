package com.unowhy.companion.ui

import com.unowhy.companion.focus.FocusTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusRemainingTest {
    private val full = 25 * 60_000L

    private fun state(remaining: Long, running: Boolean) = FocusTimer.State(FocusTimer.Phase.FOCUS, full, remaining, running, 0)

    @Test
    fun `chrono neuf rien a dire`() {
        assertNull(focusRemaining(state(full, running = false)))
    }

    @Test
    fun `chrono lance le temps restant`() {
        assertEquals("25:00", focusRemaining(state(full, running = true)))
    }

    @Test
    fun `chrono en pause en cours de seance garde son temps`() {
        assertEquals("24:57", focusRemaining(state(full - 3_000, running = false)))
    }
}
