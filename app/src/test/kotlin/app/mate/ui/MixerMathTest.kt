package app.mate.ui

import app.mate.ui.mixer.MixerMath
import org.junit.Assert.assertEquals
import org.junit.Test

class MixerMathTest {
    @Test
    fun `silence et plein niveau`() {
        assertEquals(0, MixerMath.ledsLit(0, 20))
        assertEquals(20, MixerMath.ledsLit(100, 20))
        assertEquals(20, MixerMath.ledsLit(140, 20))
    }

    @Test
    fun `échelle en décibels - une voix à 10 % allume plus de la moitié`() {
        // 10 % = -20 dB, sur une échelle de -48 à 0 : 28/48 des LED.
        assertEquals(12, MixerMath.ledsLit(10, 20))
        assertEquals(0, MixerMath.ledsLit(0, 20))
        assertEquals(3, MixerMath.ledsLit(1, 20))
    }

    @Test
    fun `glisser vers le haut monte le volume, borné à 0-100`() {
        assertEquals(75, MixerMath.dragVolume(50, -100f, 400f))
        assertEquals(25, MixerMath.dragVolume(50, 100f, 400f))
        assertEquals(100, MixerMath.dragVolume(90, -400f, 400f))
        assertEquals(0, MixerMath.dragVolume(10, 400f, 400f))
        assertEquals(40, MixerMath.dragVolume(40, 10f, 0f))
    }

    @Test
    fun `couleurs des LED de bas en haut`() {
        val zones = (0 until 20).map { MixerMath.ledZone(it, 20) }
        assertEquals(List(13) { 0 } + List(4) { 1 } + List(3) { 2 }, zones)
    }

    @Test
    fun `vumetre continu - des tranches de couleur coupees au niveau`() {
        // 24 LED : vert jusqu'à 17/24, jaune jusqu'à 21/24, rouge au-dessus.
        assertEquals(emptyList<MixerMath.Span>(), MixerMath.meterSpans(0f, 24))
        assertEquals(listOf(MixerMath.Span(0f, 0.5f, 0)), MixerMath.meterSpans(12f, 24))
        assertEquals(
            listOf(MixerMath.Span(0f, 17 / 24f, 0), MixerMath.Span(17 / 24f, 19 / 24f, 1)),
            MixerMath.meterSpans(19f, 24),
        )
        assertEquals(
            listOf(MixerMath.Span(0f, 17 / 24f, 0), MixerMath.Span(17 / 24f, 21 / 24f, 1), MixerMath.Span(21 / 24f, 1f, 2)),
            MixerMath.meterSpans(24f, 24),
        )
    }
}
