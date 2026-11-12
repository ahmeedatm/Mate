package app.mate.ui.home

import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
import android.widget.SeekBar
import kotlin.math.hypot

/**
 * Double toucher sur un même widget (agrandir dans [WidgetGrid], rendre sa taille dans [WidgetZoom]) : deux touchers
 * brefs, sur la même cible, à moins de [timeoutMs] et [slopPx] l'un de l'autre. Le premier toucher n'est pas retenu
 * (il va au widget comme d'habitude) ; seul le second est pris. Logique pure, sans vue : testée par `DoubleTapTest`.
 */
class DoubleTap(private val timeoutMs: Long, private val slopPx: Float) {
    private var lastUpAt = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastTarget: Any? = null

    /** Doigt posé : vrai si c'est le second toucher d'un double toucher sur [target] (l'attente repart alors de zéro). */
    fun down(now: Long, x: Float, y: Float, target: Any?): Boolean {
        val second = target != null && target == lastTarget && now - lastUpAt <= timeoutMs &&
            hypot(x - lastX, y - lastY) <= slopPx
        if (second || now - lastUpAt > timeoutMs) lastTarget = null
        return second
    }

    /**
     * Doigt levé : un toucher bref et immobile sur [target] (posé à [downAt]) peut ouvrir un double toucher ; un geste
     * long, glissé, repris par la grille ou sans cible l'annule.
     */
    fun up(now: Long, x: Float, y: Float, target: Any?, downAt: Long, moved: Boolean) {
        if (target == null || moved || now - downAt > timeoutMs) {
            lastTarget = null
            return
        }
        lastUpAt = now
        lastX = x
        lastY = y
        lastTarget = target
    }

    fun reset() {
        lastTarget = null
    }
}

/**
 * Vrai si, sous (x, y) dans les coordonnées du parent de [root], une vue de [root] (lui compris) prend déjà le toucher : bouton,
 * case, curseur, champ, liste à éléments touchables, touche qui garde l'appui long. Un double toucher là irait au
 * bouton : il n'agrandit pas.
 */
fun interactiveAt(root: View, x: Float, y: Float): Boolean {
    fun walk(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (x < view.left || x >= view.right || y < view.top || y >= view.bottom) return false
        if (view.isClickable || view.isLongClickable || view is SeekBar || view is EditText) return true
        if (view is GestureOwner && view.ownsLongPress) return true
        if (view is AdapterView<*> && view.onItemClickListener != null) return true
        if (view is ViewGroup) {
            val lx = x - view.left + view.scrollX
            val ly = y - view.top + view.scrollY
            for (i in view.childCount - 1 downTo 0) if (walk(view.getChildAt(i), lx, ly)) return true
        }
        return false
    }
    return walk(root, x, y)
}
