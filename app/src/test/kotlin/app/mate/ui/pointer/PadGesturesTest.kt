package app.mate.ui.pointer

import app.mate.net.PointerButton
import app.mate.net.PointerGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadGesturesTest {

    private val out = ArrayList<PadCommand>()
    private val pad = PadGestures({ out += it }, padWidthPx = { 1000f })

    private fun touch(a: TouchAction, n: Int, x: Float, y: Float, t: Long) = pad.onTouch(a, n, x, y, t)

    @Test
    fun `un doigt lent deplace 1 pour 1`() {
        touch(TouchAction.DOWN, 1, 100f, 100f, 0)
        touch(TouchAction.MOVE, 1, 105f, 100f, 100)
        assertEquals(listOf<PadCommand>(PadCommand.Move(5, 0)), out)
    }

    @Test
    fun `un doigt rapide est amplifie`() {
        touch(TouchAction.DOWN, 1, 0f, 0f, 0)
        touch(TouchAction.MOVE, 1, 60f, 0f, 10)
        val move = out.single() as PadCommand.Move
        assertTrue(move.dx > 60)
    }

    @Test
    fun `le gain croit avec la vitesse et reste borne`() {
        val gains = listOf(0f, 0.1f, 0.3f, 0.8f, 1.5f, 3f, 10f).map { padGain(it) }
        assertEquals(gains.sorted(), gains)
        assertEquals(MIN_GAIN, gains.first(), 0f)
        assertEquals(MAX_GAIN, gains.last(), 0f)
        assertTrue(padGain(1.5f) > padGain(0.8f))
    }

    @Test
    fun `tape courte d un doigt fait un clic gauche`() {
        touch(TouchAction.DOWN, 1, 50f, 50f, 0)
        touch(TouchAction.UP, 0, 50f, 50f, 120)
        assertEquals(listOf<PadCommand>(PadCommand.Click(PointerButton.LEFT)), out)
    }

    @Test
    fun `contact trop long n est pas une tape`() {
        touch(TouchAction.DOWN, 1, 50f, 50f, 0)
        touch(TouchAction.UP, 0, 50f, 50f, TAP_MAX_MS + 1)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `contact qui a bouge au dela du seuil n est pas une tape`() {
        touch(TouchAction.DOWN, 1, 50f, 50f, 0)
        touch(TouchAction.MOVE, 1, 50f + TAP_SLOP_PX + 5, 50f, 50)
        out.clear()
        touch(TouchAction.UP, 0, 80f, 50f, 100)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `petit tremblement reste une tape`() {
        touch(TouchAction.DOWN, 1, 50f, 50f, 0)
        touch(TouchAction.MOVE, 1, 54f, 50f, 40)
        touch(TouchAction.UP, 0, 54f, 50f, 100)
        assertEquals(PadCommand.Click(PointerButton.LEFT), out.last())
    }

    @Test
    fun `tape a deux doigts fait un clic droit`() {
        touch(TouchAction.DOWN, 1, 50f, 50f, 0)
        touch(TouchAction.DOWN, 2, 60f, 50f, 10)
        touch(TouchAction.UP, 1, 50f, 50f, 100)
        touch(TouchAction.UP, 0, 50f, 50f, 110)
        assertEquals(listOf<PadCommand>(PadCommand.Click(PointerButton.RIGHT)), out)
    }

    @Test
    fun `defilement a deux doigts est naturel vers le haut`() {
        touch(TouchAction.DOWN, 1, 100f, 300f, 0)
        touch(TouchAction.DOWN, 2, 100f, 300f, 5)
        touch(TouchAction.MOVE, 2, 100f, 180f, 50)
        // 120 px vers le haut : le document descend, sy négatif.
        assertEquals(listOf<PadCommand>(PadCommand.Scroll(0, -2)), out)
    }

    @Test
    fun `defilement vers le bas donne sy positif`() {
        touch(TouchAction.DOWN, 2, 100f, 100f, 0)
        touch(TouchAction.MOVE, 2, 100f, 220f, 50)
        assertEquals(listOf<PadCommand>(PadCommand.Scroll(0, 2)), out)
    }

    @Test
    fun `doigts vers la gauche donnent sx positif`() {
        touch(TouchAction.DOWN, 2, 300f, 100f, 0)
        touch(TouchAction.MOVE, 2, 180f, 100f, FLICK_MAX_MS + 50)
        assertEquals(listOf<PadCommand>(PadCommand.Scroll(2, 0)), out)
    }

    @Test
    fun `le reste du defilement est reporte`() {
        touch(TouchAction.DOWN, 2, 0f, 0f, 0)
        // 4 x 20 px = 80 px = 1 cran + 20 px de reste ; le cran sort au 3e pas (60 px).
        for (i in 1..4) touch(TouchAction.MOVE, 2, 0f, 20f * i, 10L * i)
        assertEquals(listOf<PadCommand>(PadCommand.Scroll(0, 1)), out)
        touch(TouchAction.MOVE, 2, 0f, 100f, 60)
        touch(TouchAction.MOVE, 2, 0f, 120f, 70)
        assertEquals(2, out.size)
        assertEquals(PadCommand.Scroll(0, 1), out[1])
    }

    @Test
    fun `lever un doigt apres un defilement n emet plus de deplacement`() {
        touch(TouchAction.DOWN, 2, 0f, 0f, 0)
        touch(TouchAction.MOVE, 2, 0f, 60f, 20)
        out.clear()
        touch(TouchAction.UP, 1, 0f, 60f, 30)
        touch(TouchAction.MOVE, 1, 0f, 200f, 40)
        touch(TouchAction.UP, 0, 0f, 200f, 50)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `double tape puis glisser enfonce le bouton jusqu a la levee`() {
        touch(TouchAction.DOWN, 1, 50f, 50f, 0)
        touch(TouchAction.UP, 0, 50f, 50f, 80)
        out.clear()
        touch(TouchAction.DOWN, 1, 50f, 50f, 200)
        touch(TouchAction.MOVE, 1, 90f, 50f, 260)
        touch(TouchAction.MOVE, 1, 120f, 50f, 320)
        touch(TouchAction.UP, 0, 120f, 50f, 380)
        assertEquals(PadCommand.Button(PointerButton.LEFT, true), out.first())
        assertTrue(out[1] is PadCommand.Move)
        assertEquals(PadCommand.Button(PointerButton.LEFT, false), out.last())
        assertEquals(1, out.count { it is PadCommand.Button && it.down })
        assertTrue(out.none { it is PadCommand.Click })
    }

    @Test
    fun `second contact trop tardif n arme pas le glisser`() {
        touch(TouchAction.DOWN, 1, 50f, 50f, 0)
        touch(TouchAction.UP, 0, 50f, 50f, 80)
        out.clear()
        touch(TouchAction.DOWN, 1, 50f, 50f, 80 + DOUBLE_TAP_MS + 10)
        touch(TouchAction.MOVE, 1, 120f, 50f, 600)
        assertTrue(out.none { it is PadCommand.Button })
    }

    @Test
    fun `double tape sans glisser fait deux clics gauches`() {
        touch(TouchAction.DOWN, 1, 50f, 50f, 0)
        touch(TouchAction.UP, 0, 50f, 50f, 80)
        touch(TouchAction.DOWN, 1, 50f, 50f, 160)
        touch(TouchAction.UP, 0, 50f, 50f, 230)
        assertEquals(listOf<PadCommand>(PadCommand.Click(PointerButton.LEFT), PadCommand.Click(PointerButton.LEFT)), out)
    }

    @Test
    fun `annulation pendant un glisser relache le bouton`() {
        touch(TouchAction.DOWN, 1, 50f, 50f, 0)
        touch(TouchAction.UP, 0, 50f, 50f, 80)
        touch(TouchAction.DOWN, 1, 50f, 50f, 150)
        touch(TouchAction.MOVE, 1, 120f, 50f, 200)
        out.clear()
        touch(TouchAction.CANCEL, 0, 0f, 0f, 220)
        assertEquals(listOf<PadCommand>(PadCommand.Button(PointerButton.LEFT, false)), out)
    }

    @Test
    fun `le reste du deplacement est reporte`() {
        // Gain 1 à vitesse lente : 0,5 px par pas, un pixel tous les deux pas.
        touch(TouchAction.DOWN, 1, 0f, 0f, 0)
        for (i in 1..4) touch(TouchAction.MOVE, 1, 0.5f * i, 0f, 100L * i)
        assertEquals(2, out.filterIsInstance<PadCommand.Move>().sumOf { it.dx })
    }

    private fun gestures() = out.filterIsInstance<PadCommand.Gesture>().map { it.gesture }

    private fun two(a: TouchAction, x: Float, y: Float, t: Long, spread: Float = 100f) = pad.onTouch(a, 2, x, y, t, spread)

    // Pincement

    @Test
    fun `ecarter les doigts emet un zoom-in par cran de 12 pour cent`() {
        two(TouchAction.DOWN, 500f, 500f, 0)
        for (s in listOf(105f, 111f, 124f, 137f)) two(TouchAction.MOVE, 500f, 500f, 50, s)
        // 100 -> 137 : crans à 112, 125,4 et 140,5 pas encore atteint => 2 crans.
        assertEquals(listOf(PointerGesture.ZOOM_IN, PointerGesture.ZOOM_IN), gestures())
        assertTrue(out.none { it is PadCommand.Scroll })
    }

    @Test
    fun `pincer les doigts emet un zoom-out`() {
        two(TouchAction.DOWN, 500f, 500f, 0)
        two(TouchAction.MOVE, 500f, 500f, 50, 85f)
        assertEquals(listOf(PointerGesture.ZOOM_OUT), gestures())
    }

    @Test
    fun `un ecart sous 10 pour cent n est pas un pincement`() {
        two(TouchAction.DOWN, 500f, 500f, 0)
        two(TouchAction.MOVE, 500f, 500f, 50, 108f)
        assertTrue(gestures().isEmpty())
    }

    @Test
    fun `le zoom reste verrouille jusqu a la levee et bloque le defilement`() {
        two(TouchAction.DOWN, 500f, 500f, 0)
        two(TouchAction.MOVE, 500f, 500f, 50, 113f)
        out.clear()
        // L'écart revient presque à l'initial et le centre glisse : toujours pas de défilement ni de balayage.
        two(TouchAction.MOVE, 500f, 700f, 100, 101f)
        two(TouchAction.MOVE, 500f, 900f, 150, 101f)
        assertTrue(out.isEmpty())
        pad.onTouch(TouchAction.UP, 1, 500f, 900f, 200)
        pad.onTouch(TouchAction.UP, 0, 500f, 900f, 210)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `defilement vertical avec ecart stable n est pas un zoom`() {
        two(TouchAction.DOWN, 500f, 500f, 0)
        two(TouchAction.MOVE, 500f, 380f, 50, 103f)
        assertEquals(listOf<PadCommand>(PadCommand.Scroll(0, -2)), out)
    }

    // Balayage rapide à 2 doigts

    @Test
    fun `balayage rapide vers la droite emet back sans defilement`() {
        two(TouchAction.DOWN, 300f, 500f, 0)
        two(TouchAction.MOVE, 400f, 505f, 100)
        two(TouchAction.MOVE, 580f, 510f, 200)
        assertEquals(listOf<PadCommand>(PadCommand.Gesture(PointerGesture.BACK)), out)
        two(TouchAction.MOVE, 700f, 510f, 250)
        pad.onTouch(TouchAction.UP, 0, 700f, 510f, 300)
        assertEquals(1, out.size)
    }

    @Test
    fun `balayage rapide vers la gauche emet forward`() {
        two(TouchAction.DOWN, 700f, 500f, 0)
        two(TouchAction.MOVE, 600f, 500f, 100)
        two(TouchAction.MOVE, 440f, 500f, 200)
        assertEquals(listOf<PadCommand>(PadCommand.Gesture(PointerGesture.FORWARD)), out)
    }

    @Test
    fun `balayage horizontal trop lent defile`() {
        two(TouchAction.DOWN, 300f, 500f, 0)
        two(TouchAction.MOVE, 420f, 500f, 200)
        two(TouchAction.MOVE, 540f, 500f, 400)
        two(TouchAction.MOVE, 660f, 500f, 600)
        assertTrue(gestures().isEmpty())
        // Le défilement mis en attente sort dès que ce n'est plus un balayage : 120 + 120 + 120 px = 6 crans.
        assertEquals(-6, out.filterIsInstance<PadCommand.Scroll>().sumOf { it.sx })
    }

    @Test
    fun `balayage horizontal trop court defile`() {
        two(TouchAction.DOWN, 300f, 500f, 0)
        two(TouchAction.MOVE, 420f, 500f, 100)
        two(TouchAction.MOVE, 480f, 500f, 200)
        two(TouchAction.MOVE, 480f, 500f, 500)
        assertTrue(gestures().isEmpty())
        assertEquals(-3, out.filterIsInstance<PadCommand.Scroll>().sumOf { it.sx })
    }

    @Test
    fun `glissement surtout vertical defile et ne revient pas en arriere`() {
        two(TouchAction.DOWN, 300f, 500f, 0)
        two(TouchAction.MOVE, 340f, 380f, 60)
        two(TouchAction.MOVE, 600f, 300f, 120)
        assertTrue(gestures().isEmpty())
        assertTrue(out.any { it is PadCommand.Scroll })
    }

    // 3 et 4 doigts

    private fun multi(n: Int, x: Float, y: Float, endX: Float, endY: Float) {
        pad.onTouch(TouchAction.DOWN, n, x, y, 0)
        pad.onTouch(TouchAction.MOVE, n, (x + endX) / 2, (y + endY) / 2, 60)
        pad.onTouch(TouchAction.MOVE, n, endX, endY, 120)
        // Un mouvement de plus après le seuil ne doit rien redéclencher.
        pad.onTouch(TouchAction.MOVE, n, endX + (endX - x), endY + (endY - y), 180)
        pad.onTouch(TouchAction.UP, 0, endX, endY, 240)
    }

    @Test
    fun `3 doigts vers le haut emet task-view`() {
        multi(3, 500f, 500f, 500f, 350f)
        assertEquals(listOf(PointerGesture.TASK_VIEW), gestures())
    }

    @Test
    fun `3 doigts vers le bas emet desktop`() {
        multi(3, 500f, 300f, 500f, 450f)
        assertEquals(listOf(PointerGesture.DESKTOP), gestures())
    }

    @Test
    fun `3 doigts vers la gauche emet app-previous et vers la droite app-next`() {
        multi(3, 500f, 400f, 350f, 400f)
        assertEquals(listOf(PointerGesture.APP_PREVIOUS), gestures())
        out.clear()
        multi(3, 400f, 400f, 550f, 400f)
        assertEquals(listOf(PointerGesture.APP_NEXT), gestures())
    }

    @Test
    fun `4 doigts gauche et droite changent de bureau`() {
        multi(4, 500f, 400f, 350f, 400f)
        assertEquals(listOf(PointerGesture.DESKTOP_PREVIOUS), gestures())
        out.clear()
        multi(4, 400f, 400f, 550f, 400f)
        assertEquals(listOf(PointerGesture.DESKTOP_NEXT), gestures())
    }

    @Test
    fun `4 doigts vers le haut ne fait rien`() {
        multi(4, 500f, 500f, 500f, 350f)
        assertTrue(gestures().isEmpty())
    }

    @Test
    fun `petit mouvement a 3 doigts ne declenche rien`() {
        multi(3, 500f, 500f, 500f, 470f)
        assertTrue(out.isEmpty() || gestures().isEmpty())
    }

    @Test
    fun `tape a 3 doigts emet search et a 4 doigts notifications`() {
        pad.onTouch(TouchAction.DOWN, 1, 500f, 500f, 0)
        pad.onTouch(TouchAction.DOWN, 2, 500f, 500f, 10)
        pad.onTouch(TouchAction.DOWN, 3, 500f, 500f, 20)
        pad.onTouch(TouchAction.UP, 2, 500f, 500f, 100)
        pad.onTouch(TouchAction.UP, 1, 500f, 500f, 110)
        pad.onTouch(TouchAction.UP, 0, 500f, 500f, 120)
        assertEquals(listOf(PointerGesture.SEARCH), gestures())
        out.clear()
        for (i in 1..4) pad.onTouch(TouchAction.DOWN, i, 500f, 500f, 1000L + i)
        for (i in 3 downTo 0) pad.onTouch(TouchAction.UP, i, 500f, 500f, 1100L + i)
        assertEquals(listOf(PointerGesture.NOTIFICATIONS), gestures())
        assertTrue(out.none { it is PadCommand.Click })
    }

    @Test
    fun `un balayage a 3 doigts ne fait pas aussi une tape`() {
        multi(3, 500f, 500f, 500f, 350f)
        assertEquals(1, out.size)
    }
}
