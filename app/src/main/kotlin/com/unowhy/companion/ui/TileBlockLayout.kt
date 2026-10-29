package com.unowhy.companion.ui

import android.content.Context
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.unowhy.companion.R

/**
 * Un seul grand bloc coupé par des sillons : ses enfants en cases carrées, 2 par rangée jusqu'à 4, 3 au-delà (écran
 * Depuis l'ordi : 3 × 2), dans l'ordre de lecture, centrés dans la place. Chaque case prend la tuile de l'Accueil
 * (`c_home_card_bg` : translucide en Rétro, les tubes passent derrière), arrondie en grand aux quatre coins du bloc et à
 * peine le long des sillons : on lit un seul bloc.
 */
class TileBlockLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val gap = dp(GAP_DP)
    private val outer = dp(OUTER_DP).toFloat()
    private val inner = dp(INNER_DP).toFloat()

    private val columns get() = if (childCount > 4) 3 else 2
    private val rows get() = (childCount + columns - 1) / columns

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) getChildAt(i).background = cellBackground(i)
    }

    /** La tuile du thème, ses formes (repos, appui) arrondies en grand seulement aux coins du bloc. */
    private fun cellBackground(index: Int) = ContextCompat.getDrawable(context, themeRes(context, R.attr.c_home_card_bg))?.mutate()?.also { tile ->
        val col = index % columns
        val row = index / columns
        val top = row == 0
        val bottom = row == rows - 1
        val left = col == 0
        val right = col == columns - 1
        // Coins dans l'ordre d'Android : haut gauche, haut droite, bas droite, bas gauche (deux valeurs chacun).
        val corners = listOf(top && left, top && right, bottom && right, bottom && left).flatMap { out ->
            List(2) { if (out) outer else inner }
        }.toFloatArray()
        val shapes = (tile.constantState as? DrawableContainer.DrawableContainerState)?.children?.filterNotNull() ?: listOf(tile)
        shapes.filterIsInstance<GradientDrawable>().forEach { it.cornerRadii = corners }
    }

    /** Côté d'une case : le plus grand carré qui tient, en largeur comme en hauteur. */
    private fun cell(width: Int, height: Int) =
        minOf((width - gap * (columns - 1)) / columns, (height - gap * (rows - 1)) / rows)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val spec = MeasureSpec.makeMeasureSpec(cell(width, height), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount == 0) return
        val side = cell(r - l, b - t)
        val left = (r - l - (side * columns + gap * (columns - 1))) / 2
        val top = (b - t - (side * rows + gap * (rows - 1))) / 2
        for (i in 0 until childCount) {
            val x = left + (i % columns) * (side + gap)
            val y = top + (i / columns) * (side + gap)
            getChildAt(i).layout(x, y, x + side, y + side)
        }
    }

    private companion object {
        const val GAP_DP = 12
        const val OUTER_DP = 40
        const val INNER_DP = 12
    }
}
