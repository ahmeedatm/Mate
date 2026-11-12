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

    @Test
    fun `volume tape au pave numerique`() {
        assertEquals(35, MixerMath.typedVolume("35"))
        assertEquals(0, MixerMath.typedVolume("0"))
        assertEquals(100, MixerMath.typedVolume(" 100 "))
        assertEquals(null, MixerMath.typedVolume("101"))
        assertEquals(null, MixerMath.typedVolume("-3"))
        assertEquals(null, MixerMath.typedVolume(""))
        assertEquals(null, MixerMath.typedVolume("3,5"))
    }

    @Test
    fun `vumetre instrument monte en 120 ms et retombe en 300 ms`() {
        // 24 LED : 0,2 LED/ms en montée, 0,08 LED/ms en descente.
        assertEquals(12f, MixerMath.meterStep(0f, 24, 60, 24), 0.001f)
        assertEquals(24f, MixerMath.meterStep(0f, 24, 120, 24), 0.001f)
        assertEquals(10f, MixerMath.meterStep(20f, 0, 125, 24), 0.001f)
        assertEquals(0f, MixerMath.meterStep(20f, 0, 300, 24), 0.001f)
    }

    @Test
    fun `vumetre instrument ne depasse pas la cible et reste en place sans temps`() {
        assertEquals(8f, MixerMath.meterStep(5f, 8, 1000, 24), 0.001f)
        assertEquals(8f, MixerMath.meterStep(20f, 8, 1000, 24), 0.001f)
        assertEquals(5f, MixerMath.meterStep(5f, 8, 0, 24), 0.001f)
        assertEquals(5f, MixerMath.meterStep(5f, 8, -10, 24), 0.001f)
        assertEquals(8f, MixerMath.meterStep(8f, 8, 50, 24), 0.001f)
    }

    @Test
    fun `vumetre instrument avance aussi sur une image de 18 ms`() {
        // 55 Hz : 16 LED × 18 ms / 300 ms = 0,96 LED en descente, 2,4 en montée (pas 0 ni 2 par division entière).
        assertEquals(15.04f, MixerMath.meterStep(16f, 0, 18, 16), 0.001f)
        assertEquals(2.4f, MixerMath.meterStep(0f, 16, 18, 16), 0.001f)
    }
}
