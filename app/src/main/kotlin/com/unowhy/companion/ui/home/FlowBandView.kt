package com.unowhy.companion.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.unowhy.companion.R
import com.unowhy.companion.ui.themeBool
import com.unowhy.companion.ui.themeColor
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Bande du thème Rétro à gauche de l'Accueil, d'après le dessin d'Eliot (328 × 800, toute la hauteur) : deux tubes
 * de trois bandes qui font une boucle tous les 400, détourée d'un liseré du fond. Un liquide y coule lentement vers
 * le bas : une vague plus claire glisse dans chaque bande (dégradé linéaire décalé dans les tiges, dégradé circulaire
 * qui tourne dans les boucles, en phase avec la tige au point où ils se touchent). Rien que des rectangles et des
 * disques, 30 images/s, arrêtée dès qu'elle n'est plus à l'écran. Masquée hors thème Rétro (`c_flow`).
 */
class FlowBandView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /**
     * Un tube, en unités du dessin : centre des boucles en x, première boucle en y, couleurs de la bande intérieure
     * vers l'extérieure. [side] = côté de la tige (+1 : à droite de la boucle, qui tourne alors dans le sens des
     * aiguilles d'une montre). [lowerOver] : la tige du dessous passe sur la boucle (sinon celle du dessus).
     */
    private inner class Tube(val cx: Float, val firstLoop: Float, val colors: IntArray, val side: Int, val lowerOver: Boolean) {
        val stems = Array(3) { k -> LinearGradient(0f, 0f, 0f, WAVE, wave(k), null, Shader.TileMode.REPEAT) }
        val loops = Array(3) { k -> SweepGradient(0f, 0f, IntArray(waves(k) * 2 + 1) { wave(k)[it % 2] }, null) }

        private fun wave(k: Int): IntArray {
            val color = colors[k]
            return intArrayOf(color, ColorUtils.blendARGB(color, if (color == cream) paper else cream, TINT), color)
        }
    }

    private val bg = themeColor(context, R.attr.c_bg)
    private val paper = themeColor(context, R.attr.c_paper)
    private val cream = themeColor(context, R.attr.c_flow_cream)
    private val tubes = listOf(
        Tube(
            82f, 153f,
            intArrayOf(themeColor(context, R.attr.c_flow_dark), themeColor(context, R.attr.c_flow_red), themeColor(context, R.attr.c_flow_orange)),
            side = 1, lowerOver = false,
        ),
        Tube(
            278f, 270f,
            intArrayOf(cream, themeColor(context, R.attr.c_flow_orange), themeColor(context, R.attr.c_flow_red)),
            side = -1, lowerOver = true,
        ),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var running = false

    init {
        visibility = if (themeBool(context, R.attr.c_flow)) VISIBLE else GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension((height * WIDTH / HEIGHT).toInt(), height)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible
        if (isVisible) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val scale = height / HEIGHT
        canvas.save()
        canvas.scale(scale, scale)
        val flowed = ((SystemClock.uptimeMillis() * SPEED / 1000.0) % WAVE).toFloat()
        for (tube in tubes) drawTube(canvas, tube, flowed)
        canvas.restore()
        paint.shader = null
        if (running) postInvalidateDelayed(FRAME_MS)
    }

    /** Boucles visibles de [nMin] à [nMax], tiges de [nMin] - 1 à [nMax], dans l'ordre où elles se recouvrent. */
    private fun drawTube(canvas: Canvas, tube: Tube, flowed: Float) {
        val nMin = ceil((-OUTLINE - tube.firstLoop) / PERIOD).toInt()
        val nMax = floor((HEIGHT + OUTLINE - tube.firstLoop) / PERIOD).toInt()
        if (tube.lowerOver) {
            drawStem(canvas, tube, nMin - 1, flowed)
            for (n in nMin..nMax) {
                drawLoop(canvas, tube, n, flowed)
                drawStem(canvas, tube, n, flowed)
            }
        } else {
            drawStem(canvas, tube, nMax, flowed)
            for (n in nMax downTo nMin) {
                drawLoop(canvas, tube, n, flowed)
                drawStem(canvas, tube, n - 1, flowed)
            }
        }
    }

    /** Tige entre la boucle [n] et la suivante : trois bandes droites où la vague descend. */
    private fun drawStem(canvas: Canvas, tube: Tube, n: Int, flowed: Float) {
        val top = tube.firstLoop + n * PERIOD
        matrix.setTranslate(0f, flowed)
        for (k in 0 until 3) {
            val x = tube.cx + tube.side * RADII[k]
            tube.stems[k].setLocalMatrix(matrix)
            paint.shader = tube.stems[k]
            canvas.drawRect(x - BAND / 2, top, x + BAND / 2, top + PERIOD, paint)
        }
    }

    /**
     * Boucle [n] : liseré du fond qui coupe la tige passant dessous, puis disques concentriques dont la vague tourne
     * dans le sens du tube. Au point de jonction (angle de départ), la phase est celle de la tige à la même hauteur.
     */
    private fun drawLoop(canvas: Canvas, tube: Tube, n: Int, flowed: Float) {
        val cy = tube.firstLoop + n * PERIOD
        paint.shader = null
        paint.color = bg
        canvas.drawCircle(tube.cx, cy, OUTLINE, paint)
        val start = if (tube.side > 0) 0f else 180f
        val phase = (cy - flowed) / WAVE
        for (k in 2 downTo 0) {
            matrix.setRotate(start - tube.side * 360f * phase / waves(k))
            matrix.postTranslate(tube.cx, cy)
            tube.loops[k].setLocalMatrix(matrix)
            paint.shader = tube.loops[k]
            canvas.drawCircle(tube.cx, cy, RADII[k] + BAND / 2, paint)
        }
        // Le centre du disque intérieur, où le dégradé circulaire se resserre en moulinet : couleur pleine.
        paint.shader = null
        paint.color = tube.colors[0]
        canvas.drawCircle(tube.cx, cy, RADII[0] - BAND / 2, paint)
    }

    companion object {
        private const val WIDTH = 328f
        private const val HEIGHT = 800f
        private const val PERIOD = 400f
        private const val BAND = 24f
        private const val OUTLINE = 82f + BAND
        private val RADII = floatArrayOf(22f, 46f, 70f)

        /** Longueur d'une vague, éclaircissement en son milieu. */
        private const val WAVE = 150f
        private const val TINT = 0.4f

        /** Vagues sur le tour de la boucle de la bande [k] : un nombre entier, pour qu'elle se referme sans raccord. */
        private fun waves(k: Int) = maxOf(1, (2 * PI * RADII[k] / WAVE).roundToInt())

        /** Vitesse du liquide, en unités du dessin par seconde. */
        private const val SPEED = 22.0
        private const val FRAME_MS = 33L
    }
}
