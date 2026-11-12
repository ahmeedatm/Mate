package app.mate.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.mate.ui.home.KeyCaps
import app.mate.ui.home.WindowCaps

/**
 * Capuchons de fenêtres d'un écran (thème Instrument) : autant de colonnes que la largeur en tient
 * ([WindowCaps.columns]), chacun de la hauteur [KeyCaps.CELL_DP]. La hauteur suit le nombre de rangées : l'écran défile.
 */
class CapGrid(context: Context) : ViewGroup(context) {
    private val gap = dp(WindowCaps.GAP_DP)
    private val cell = dp(KeyCaps.CELL_DP)

    private fun columns(width: Int) = WindowCaps.columns(width, resources.displayMetrics.density)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val columns = columns(width)
        val each = (width - gap * (columns - 1)) / columns
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(each, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY))
        }
        val rows = (childCount + columns - 1) / columns
        setMeasuredDimension(width, if (rows == 0) 0 else rows * cell + (rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val columns = columns(r - l)
        for (i in 0 until childCount) {
            val child: View = getChildAt(i)
            val left = (i % columns) * (child.measuredWidth + gap)
            val top = (i / columns) * (cell + gap)
            child.layout(left, top, left + child.measuredWidth, top + cell)
        }
    }
}
