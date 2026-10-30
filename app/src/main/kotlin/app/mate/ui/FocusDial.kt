package app.mate.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import app.mate.R
import app.mate.focus.FocusTicks
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cadran du Focus (DA Cadran) : une graduation par minute de la séance, les dernières allumées.
 * Un changement passe en cascade, 18 ms d'une graduation à l'autre (pause : elles s'éteignent, reprise : elles se
 * rallument). Redessiné seulement pendant la cascade : rien ne bouge le reste du temps.
 */
class FocusDial @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val off = themeColor(context, R.attr.c_graphic)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val evaluator = ArgbEvaluator()
    private val cos = FloatArray(FocusTicks.COUNT) { cos(angle(it)) }
    private val sin = FloatArray(FocusTicks.COUNT) { sin(angle(it)) }
    private val start = IntArray(FocusTicks.COUNT) { off }
    private val end = IntArray(FocusTicks.COUNT) { off }
    private val delay = LongArray(FocusTicks.COUNT)
    private var elapsed = Long.MAX_VALUE
    private var animator: ValueAnimator? = null
    private var lit = 0

    /** Couleur des graduations allumées (rouge en concentration, vert en pause). */
    var color = themeColor(context, R.attr.c_accent)
        set(value) {
            if (value != field) { field = value; show(lit, animate = false, force = true) }
        }

    /** Allume les [count] dernières graduations, en cascade si [animate]. Même nombre : rien (cascade en cours gardée). */
    fun show(count: Int, animate: Boolean, force: Boolean = false) {
        val target = count.coerceIn(0, FocusTicks.COUNT)
        if (target == lit && !force) return
        val first = FocusTicks.COUNT - target
        val lighting = target > lit
        var order = 0
        val indices = if (lighting) (0 until FocusTicks.COUNT) else (FocusTicks.COUNT - 1 downTo 0)
        for (i in indices) {
            start[i] = current(i)
            end[i] = if (i >= first) color else off
            delay[i] = if (start[i] != end[i]) (order++) * STAGGER else 0
        }
        lit = target
        if (order == 0) return
        animator?.cancel()
        if (!animate) {
            elapsed = Long.MAX_VALUE
            invalidate()
            return
        }
        val total = (order - 1) * STAGGER + FADE
        animator = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
            duration = total
            interpolator = LinearInterpolator()
            addUpdateListener {
                elapsed = (it.animatedValue as Float).toLong()
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) / 2f
        val stroke = radius / 18f
        val outer = radius - stroke
        val inner = outer * 0.84f
        val cx = width / 2f
        val cy = height / 2f
        paint.strokeWidth = stroke
        for (i in 0 until FocusTicks.COUNT) {
            paint.color = current(i)
            canvas.drawLine(cx + inner * cos[i], cy + inner * sin[i], cx + outer * cos[i], cy + outer * sin[i], paint)
        }
    }

    private fun current(i: Int): Int {
        if (elapsed == Long.MAX_VALUE || start[i] == end[i]) return end[i]
        val t = ((elapsed - delay[i]).toFloat() / FADE).coerceIn(0f, 1f)
        return evaluator.evaluate(t, start[i], end[i]) as Int
    }

    private fun angle(i: Int) = (i.toFloat() / FocusTicks.COUNT * 2 * Math.PI - Math.PI / 2).toFloat()

    private companion object {
        const val STAGGER = 18L
        const val FADE = 160L
    }
}
