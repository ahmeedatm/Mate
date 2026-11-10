package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import app.mate.R
import app.mate.net.ValorantRound
import app.mate.net.ValorantRoundEnd
import app.mate.ui.dp
import app.mate.ui.themeColor

/**
 * Frise des rounds, peinte en une passe (une vue, pas une par case : rien à mesurer sur la tablette) : gagné à la couleur
 * de mon équipe, perdu en rouge, ordre inconnu en gris, pas encore joué en case vide (`empty`). Mi-temps marquée par un
 * écart après le 12e. `icons` : la fin de chaque round dessinée dans sa case (tableau de fin). Cases alignées à gauche,
 * au plus `maxRatio` fois plus larges que hautes ; le sens est redit par `contentDescription` (posé par le bandeau).
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
    private val rect = RectF()
    private val won = themeColor(context, R.attr.c_hue_cyan)
    private val lost = themeColor(context, R.attr.c_hue_red)
    private val unknown = themeColor(context, R.attr.c_graphic)
    private val drawables: Map<ValorantRoundEnd, Drawable> = if (!icons) emptyMap() else ICONS.mapNotNull { (end, res) ->
        ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(themeColor(context, R.attr.c_frame_text)) }?.let { end to it }
    }.toMap()

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
        var x = 0f
        cells.forEachIndexed { i, cell ->
            if (i == HALF) x += half
            paint.color = when (cell?.won) {
                true -> won
                false -> lost
                null -> if (cell == null) empty else unknown
            }
            rect.set(x, 0f, x + cellW, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
            val icon = cell?.end?.let(drawables::get)
            val side = (minOf(cellW, h) * ICON_SHARE).toInt()
            if (icon != null && side >= context.dp(MIN_ICON)) {
                val left = (x + (cellW - side) / 2).toInt()
                val top = ((h - side) / 2).toInt()
                icon.setBounds(left, top, left + side, top + side)
                icon.draw(canvas)
            }
            x += cellW + gap
        }
    }

    companion object {
        private const val HALF = 12
        private const val ICON_SHARE = 0.62f
        private const val MIN_ICON = 10

        val ICONS = listOf(
            ValorantRoundEnd.ELIMINATION to R.drawable.ic_round_elimination,
            ValorantRoundEnd.DETONATE to R.drawable.ic_round_detonate,
            ValorantRoundEnd.DEFUSE to R.drawable.ic_round_defuse,
            ValorantRoundEnd.TIME to R.drawable.ic_round_time,
        )
    }
}
