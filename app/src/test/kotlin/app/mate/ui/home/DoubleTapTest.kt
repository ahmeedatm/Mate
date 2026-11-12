package app.mate.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapTest {
    private val clock = "horloge"
    private val agenda = "agenda"

    private fun tap(d: DoubleTap, at: Long, target: Any?, x: Float = 100f, y: Float = 100f): Boolean {
        val second = d.down(at, x, y, target)
        if (!second) d.up(at + 60, x, y, target, downAt = at, moved = false)
        return second
    }

    @Test
    fun twoQuickTapsOnTheSameWidget() {
        val d = DoubleTap(300, 50f)
        assertFalse(tap(d, 0, clock))
        assertTrue(tap(d, 200, clock))
    }

    @Test
    fun aThirdTapStartsOver() {
        val d = DoubleTap(300, 50f)
        tap(d, 0, clock)
        assertTrue(tap(d, 200, clock))
        assertFalse(tap(d, 350, clock))
    }

    @Test
    fun tooSlowTooFarOrAnotherWidget() {
        val slow = DoubleTap(300, 50f)
        tap(slow, 0, clock)
        assertFalse(tap(slow, 500, clock))

        val far = DoubleTap(300, 50f)
        tap(far, 0, clock)
        assertFalse(tap(far, 200, clock, x = 200f))

        val other = DoubleTap(300, 50f)
        tap(other, 0, clock)
        assertFalse(tap(other, 200, agenda))
    }

    @Test
    fun aButtonADragOrALongPressDoesNotCount() {
        val button = DoubleTap(300, 50f)
        tap(button, 0, null)
        assertFalse(tap(button, 200, clock))

        val drag = DoubleTap(300, 50f)
        drag.down(0, 100f, 100f, clock)
        drag.up(60, 100f, 100f, clock, downAt = 0, moved = true)
        assertFalse(tap(drag, 200, clock))

        val held = DoubleTap(300, 50f)
        held.down(0, 100f, 100f, clock)
        held.up(600, 100f, 100f, clock, downAt = 0, moved = false)
        assertFalse(tap(held, 700, clock))
    }

    @Test
    fun resetForgetsTheFirstTap() {
        val d = DoubleTap(300, 50f)
        tap(d, 0, clock)
        d.reset()
        assertFalse(tap(d, 200, clock))
    }
}
