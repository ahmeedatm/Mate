package app.mate.ui.home

import app.mate.R
import app.mate.net.AudioState
import app.mate.net.PresentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentLogicTest {

    @Test
    fun `le chrono s'ecrit mm ss, puis h mm ss a partir d'une heure`() {
        assertEquals("00:00", PresentLogic.clock(0))
        assertEquals("00:59", PresentLogic.clock(59_999))
        assertEquals("01:00", PresentLogic.clock(60_000))
        assertEquals("12:05", PresentLogic.clock(725_000))
        assertEquals("59:59", PresentLogic.clock(3_599_000))
        assertEquals("1:00:00", PresentLogic.clock(3_600_000))
        assertEquals("1:02:03", PresentLogic.clock(3_723_000))
        assertEquals("00:00", PresentLogic.clock(-5_000))
    }

    @Test
    fun `le chrono attend le premier appui sur Debut ou Suivante`() {
        val timer = PresentTimer()
        assertFalse(timer.running)
        assertEquals(0, timer.elapsed(10_000))
        timer.onAction(PresentAction.PREVIOUS, 1_000)
        assertFalse(timer.running)

        timer.onAction(PresentAction.NEXT, 2_000)
        assertTrue(timer.running)
        assertEquals(3_000, timer.elapsed(5_000))
    }

    @Test
    fun `Debut lance aussi le chrono, et un appui suivant ne le relance pas`() {
        val timer = PresentTimer()
        timer.onAction(PresentAction.START, 1_000)
        assertEquals(4_000, timer.elapsed(5_000))
        timer.onAction(PresentAction.NEXT, 4_000)
        timer.onAction(PresentAction.START, 4_500)
        timer.onAction(PresentAction.PREVIOUS, 4_800)
        assertEquals(4_000, timer.elapsed(5_000))
    }

    @Test
    fun `Fin remet le chrono a zero et l'arrete, le suivant repart de zero`() {
        val timer = PresentTimer()
        timer.onAction(PresentAction.NEXT, 1_000)
        timer.onAction(PresentAction.END, 9_000)
        assertFalse(timer.running)
        assertEquals(0, timer.elapsed(20_000))
        timer.onAction(PresentAction.NEXT, 30_000)
        assertEquals(2_000, timer.elapsed(32_000))
    }

    @Test
    fun `le micro est coupe, ouvert ou inconnu selon l'etat lu`() {
        fun audio(muted: Boolean) = AudioState(output = 40, muted = false, input = 70, micMuted = muted)
        assertEquals(PresentLogic.Mic.MUTED, PresentLogic.mic(audio(true)))
        assertEquals(PresentLogic.Mic.LIVE, PresentLogic.mic(audio(false)))
        assertEquals(PresentLogic.Mic.UNKNOWN, PresentLogic.mic(null))
        assertEquals(R.string.mic_off, PresentLogic.Mic.MUTED.label)
        assertEquals(R.drawable.ic_mic_off, PresentLogic.Mic.MUTED.icon)
        assertEquals(R.string.mic_on, PresentLogic.Mic.LIVE.label)
        assertEquals(R.drawable.ic_mic, PresentLogic.Mic.LIVE.icon)
    }

    @Test
    fun `les gestes ont la fin de route du compagnon`() {
        assertEquals(listOf("next", "previous", "start", "end"), listOf(PresentAction.NEXT, PresentAction.PREVIOUS, PresentAction.START, PresentAction.END).map { it.key })
    }
}
