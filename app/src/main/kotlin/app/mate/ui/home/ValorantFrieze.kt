package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.net.ValorantRound
import app.mate.net.ValorantRoundEnd
import app.mate.ui.dp
import app.mate.ui.themeColor

/**
 * Frise des rounds, peinte en une passe (une vue, pas une par case : rien à mesurer sur la tablette) : gagné à la couleur
 * de mon équipe et pleine hauteur, perdu en rouge et plus bas (le sens ne tient pas qu'à la couleur), ordre inconnu en
 * gris, pas encore joué en case vide (`empty`) ; un filet clair détache chaque case du voile sombre. Mi-temps marquée par
 * un écart après le 12e. `icons` : la fin de chaque round dessinée dans sa case (tableau de fin), en noir ou en blanc
 * selon la case. Cases alignées à gauche et en bas, au plus `maxRatio` fois plus larges que hautes ; le sens est redit
 * par `contentDescription` (posé par le bandeau).
 */
@SuppressLint("ViewConstructor")
class ValorantFrieze(
    context: Context,
    private val cells: List<ValorantRound?>,
    private val heightDp: Int,
    private val icons: Boolean,
    private val empty: Int,
    private val maxRatio: Float = 1.7f,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = ColorUtils.setAlphaComponent(themeColor(context, R.attr.c_frame_text), EDGE_ALPHA)
    }
    private val rect = RectF()
    private val won = themeColor(context, R.attr.c_hue_cyan)
    private val lost = themeColor(context, R.attr.c_hue_red)
    private val unknown = themeColor(context, R.attr.c_graphic)
    private val light = themeColor(context, R.attr.c_frame_text)
    private val dark = themeColor(context, R.attr.c_frame_bg)
    private val drawables: Map<ValorantRoundEnd, Drawable> = if (!icons) emptyMap() else ICONS.mapNotNull { (end, res) ->
        ContextCompat.getDrawable(context, res)?.mutate()?.let { end to it }
    }.toMap()

    /** Couleur d'icône la plus lisible sur la case : le texte des cadres photo, ou leur fond si la case est claire. */
    private fun ink(cell: Int) = if (Color.alpha(cell) < OPAQUE || ColorUtils.calculateContrast(light, cell) >= MIN_CONTRAST) light else dark

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(heightDp))
    }

    override fun onDraw(canvas: Canvas) {
        if (cells.isEmpty()) return
        val h = height.toFloat()
        val gap = (h / 5f).coerceAtLeast(context.dp(2).toFloat())
        val half = if (cells.size > HALF) gap * 3 else 0f
        val cellW = ((width - half - gap * (cells.size - 1)) / cells.size).coerceAtMost(h * maxRatio)
        val radius = (h / 5f).coerceAtMost(context.dp(5).toFloat())
        val minIcon = context.dp(MIN_ICON)
        var x = 0f
        for (i in cells.indices) {
            val cell = cells[i]
            if (i == HALF) x += half
            val color = when (cell?.won) {
                true -> won
                false -> lost
                null -> if (cell == null) empty else unknown
            }
            val top = if (cell?.won == false) h * (1 - LOST_SHARE) else 0f
            paint.color = color
            rect.set(x, top, x + cellW, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (cell != null) canvas.drawRoundRect(rect, radius, radius, edge)
            val end = cell?.end
            val icon = if (end == null) null else drawables[end]
            val side = (minOf(cellW, h - top) * ICON_SHARE).toInt()
            if (icon != null && side >= minIcon) {
                val left = (x + (cellW - side) / 2).toInt()
                val iconTop = (top + (h - top - side) / 2).toInt()
                icon.setTint(ink(color))
                icon.setBounds(left, iconTop, left + side, iconTop + side)
                icon.draw(canvas)
            }
            x += cellW + gap
        }
    }

    companion object {
        private const val HALF = 12
        private const val ICON_SHARE = 0.62f
        private const val MIN_ICON = 9
        private const val EDGE_ALPHA = 0x4D
        private const val MIN_CONTRAST = 3.0
        private const val OPAQUE = 255

        /** Hauteur d'une case perdue, en part de celle d'une gagnée. */
        const val LOST_SHARE = 0.72f

        val ICONS = listOf(
            ValorantRoundEnd.ELIMINATION to R.drawable.ic_round_elimination,
            ValorantRoundEnd.DETONATE to R.drawable.ic_round_detonate,
            ValorantRoundEnd.DEFUSE to R.drawable.ic_round_defuse,
            ValorantRoundEnd.TIME to R.drawable.ic_round_time,
        )
    }
}
