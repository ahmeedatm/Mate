package com.unowhy.unomate.ui

import android.content.Context
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.unowhy.unomate.R

/**
 * Un grand carré coupé en croix : ses quatre enfants en 2 × 2 (dans l'ordre : haut gauche, haut droite, bas gauche, bas
 * droite), séparés par un sillon, centrés dans la place. Chaque case prend la tuile de l'Accueil (`c_home_card_bg` :
 * translucide en Rétro, les tubes passent derrière), arrondie en grand vers l'extérieur et à peine vers la croix : on lit
 * un seul bloc.
 */
class CrossSquareLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val gap = dp(GAP_DP)
    private val outer = dp(OUTER_DP).toFloat()
    private val inner = dp(INNER_DP).toFloat()

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) getChildAt(i).background = quarterBackground(i)
    }

    /** La tuile du thème, ses formes (repos, appui) arrondies selon le coin du carré qu'elle occupe. */
    private fun quarterBackground(index: Int) = ContextCompat.getDrawable(context, themeRes(context, R.attr.c_home_card_bg))?.mutate()?.also { tile ->
        val (top, left) = (index < 2) to (index % 2 == 0)
        // Coins dans l'ordre d'Android : haut gauche, haut droite, bas droite, bas gauche (deux valeurs chacun).
        val corners = listOf(top && left, top && !left, !top && !left, !top && left).flatMap { out ->
            List(2) { if (out) outer else inner }
        }.toFloatArray()
        val shapes = (tile.constantState as? DrawableContainer.DrawableContainerState)?.children?.filterNotNull() ?: listOf(tile)
        shapes.filterIsInstance<GradientDrawable>().forEach { it.cornerRadii = corners }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val side = minOf(width, height)
        val cell = MeasureSpec.makeMeasureSpec((side - gap) / 2, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cell, cell)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val side = minOf(r - l, b - t)
        val cell = (side - gap) / 2
        val left = (r - l - side) / 2
        val top = (b - t - side) / 2
        for (i in 0 until minOf(childCount, 4)) {
            val x = left + (i % 2) * (cell + gap)
            val y = top + (i / 2) * (cell + gap)
            getChildAt(i).layout(x, y, x + cell, y + cell)
        }
    }

    private companion object {
        const val GAP_DP = 12
        const val OUTER_DP = 40
        const val INNER_DP = 12
    }
}
