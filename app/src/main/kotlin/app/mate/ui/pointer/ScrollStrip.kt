package app.mate.ui.pointer

import app.mate.net.PointerButton
import kotlin.math.abs

/**
 * Une bande du widget Pavé tactile en Ligne ou en Colonne (#183) : un doigt qui glisse le long de la bande fait défiler
 * l'ordinateur sur un seul axe (de haut en bas pour une Colonne, de côté pour une Ligne), dans le même sens et au même
 * pas que le défilement à deux doigts du pavé ([PadGestures]). Une tape sans glisser clique si [tapClicks].
 */
class ScrollStrip(
    private val vertical: Boolean,
    private val tapClicks: Boolean,
    private val emit: (PadCommand) -> Unit,
    private val slopPx: Float = TAP_SLOP_PX,
    private val pxPerNotch: Float = PX_PER_NOTCH,
) {
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scrolling = false
    private var down = false
    private var rest = 0f

    fun onTouch(action: TouchAction, x: Float, y: Float) {
        when (action) {
            TouchAction.DOWN -> {
                down = true
                scrolling = false
                rest = 0f
                startX = x
                startY = y
                lastX = x
                lastY = y
            }
            TouchAction.MOVE -> if (down) move(x, y)
            TouchAction.UP -> {
                if (down && !scrolling && tapClicks) emit(PadCommand.Click(PointerButton.LEFT))
                down = false
            }
            TouchAction.CANCEL -> down = false
        }
    }

    private fun move(x: Float, y: Float) {
        if (!scrolling && abs(along(x - startX, y - startY)) < slopPx && abs(across(x - startX, y - startY)) < slopPx) return
        scrolling = true
        // Même signe que le pavé : glisser vers le bas descend, glisser vers la gauche avance.
        rest += if (vertical) (y - lastY) / pxPerNotch else -(x - lastX) / pxPerNotch
        lastX = x
        lastY = y
        val notches = rest.toInt()
        if (notches == 0) return
        rest -= notches
        emit(if (vertical) PadCommand.Scroll(0, notches) else PadCommand.Scroll(notches, 0))
    }

    private fun along(dx: Float, dy: Float) = if (vertical) dy else dx

    private fun across(dx: Float, dy: Float) = if (vertical) dx else dy
}
