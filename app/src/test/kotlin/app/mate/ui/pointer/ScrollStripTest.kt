package app.mate.ui.pointer

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollStripTest {
    private fun strip(vertical: Boolean): Pair<ScrollStrip, MutableList<PadCommand>> {
        val sent = mutableListOf<PadCommand>()
        return ScrollStrip(vertical, { sent += it }, slopPx = 20f, pxPerNotch = 60f) to sent
    }

    @Test
    fun `une bande verticale defile d'un cran tous les 60 px, dans le sens du pave`() {
        val (s, sent) = strip(vertical = true)
        s.onTouch(TouchAction.DOWN, 100f, 100f)
        s.onTouch(TouchAction.MOVE, 100f, 190f)
        s.onTouch(TouchAction.MOVE, 100f, 250f)
        s.onTouch(TouchAction.UP, 100f, 250f)
        assertEquals(listOf(PadCommand.Scroll(0, 1), PadCommand.Scroll(0, 1)), sent)
    }

    @Test
    fun `une bande horizontale ne defile que de cote, le geste vertical est ignore`() {
        val (s, sent) = strip(vertical = false)
        s.onTouch(TouchAction.DOWN, 100f, 100f)
        s.onTouch(TouchAction.MOVE, 40f, 300f)
        s.onTouch(TouchAction.UP, 40f, 300f)
        assertEquals(listOf(PadCommand.Scroll(1, 0)), sent)
    }

    @Test
    fun `une tape sans glisser n'envoie rien`() {
        val (s, sent) = strip(vertical = true)
        s.onTouch(TouchAction.DOWN, 100f, 100f)
        s.onTouch(TouchAction.MOVE, 105f, 110f)
        s.onTouch(TouchAction.UP, 105f, 110f)
        assertEquals(emptyList<PadCommand>(), sent)
    }

    @Test
    fun `un glisse ne clique pas au lacher, et un geste annule n'envoie rien de plus`() {
        val (s, sent) = strip(vertical = true)
        s.onTouch(TouchAction.DOWN, 100f, 100f)
        s.onTouch(TouchAction.MOVE, 100f, 130f)
        s.onTouch(TouchAction.UP, 100f, 130f)
        assertEquals(emptyList<PadCommand>(), sent)
        s.onTouch(TouchAction.DOWN, 100f, 100f)
        s.onTouch(TouchAction.CANCEL, 100f, 100f)
        assertEquals(emptyList<PadCommand>(), sent)
    }
}
