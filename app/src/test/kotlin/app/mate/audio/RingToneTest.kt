package app.mate.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingToneTest {

    @Test
    fun `le motif dure une periode entiere, pour tourner en boucle sans raccord`() {
        assertEquals(RingTone.RATE * RingTone.PERIOD_MS / 1000, RingTone.samples().size)
    }

    @Test
    fun `le son est fort, presque a pleine echelle`() {
        val peak = RingTone.samples().maxOf { Math.abs(it.toInt()) }
        assertTrue("crete $peak", peak > Short.MAX_VALUE * 0.9)
    }

    @Test
    fun `le motif finit sur un silence, pour que la boucle se detache`() {
        val tail = RingTone.samples().takeLast(RingTone.RATE / 10)
        assertTrue(tail.all { it.toInt() == 0 })
    }

    @Test
    fun `la sonnerie s'arrete toute seule`() {
        assertTrue(RingTone.AUTO_STOP_MS in 30_000L..300_000L)
    }
}
