package app.mate.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.OvershootInterpolator
import app.mate.AnimFamily
import app.mate.R
import app.mate.ui.Look
import app.mate.ui.themeColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cadran à aiguille du widget Performances en thème Instrument (variante « C · Cadrans », #243) : un demi-cercle de 11
 * graduations, la zone rouge du seuil d'alerte ([DIAL_ALERT_FROM]) à 100 %, l'aiguille ([dialAngle]) et son moyeu orange.
 * Un relevé toutes les 2 s : l'aiguille y va en 300 ms avec un léger rebond, ou d'un coup si les animations sont coupées
 * (écran à 55 Hz : docs/tablette-4.2.md). Dessin seul, rien ne tourne en boucle.
 */
class PerfDialView(context: Context) : View(context) {
    private val tick = stroke(R.attr.c_graphic)
    private val zone = stroke(R.attr.c_signal)
    private val needle = stroke(R.attr.c_text)
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_signal) }
    private val arc = RectF()
    private var angle = dialAngle(0)
    private var shown = false
    private var animator: ValueAnimator? = null

    private fun stroke(colorAttr: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, colorAttr)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Pose la mesure ([fraction] de 0 à 1) ; [tint] colore l'aiguille (neutre, rouge d'alerte, ou grisée si périmée). */
    fun set(fraction: Float, tint: Int) {
        needle.color = tint
        val target = dialAngle((fraction * 100).roundToInt())
        animator?.cancel()
        if (!shown || !isAttachedToWindow || !Look.on(context, AnimFamily.DETAILS) || Look.reduced(context)) {
            shown = true
            angle = target
            invalidate()
            return
        }
        if (target == angle) return
        animator = ValueAnimator.ofFloat(angle, target).apply {
            duration = NEEDLE_MS
            interpolator = OvershootInterpolator(NEEDLE_OVERSHOOT)
            addUpdateListener {
                angle = it.animatedValue as Float
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
        // Le demi-cercle et son moyeu, centrés dans la vue : rayon r, moyeu sous le centre.
        val r = min(width / 2f, height / (1f + HUB_SHARE)) * RADIUS_SHARE
        if (r <= 0f) return
        val cx = width / 2f
        val cy = (height + r * (1f - HUB_SHARE)) / 2f
        tick.strokeWidth = r * TICK_WIDTH
        zone.strokeWidth = r * ZONE_WIDTH
        needle.strokeWidth = r * NEEDLE_WIDTH
        arc.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arc, 180f + DIAL_ALERT_FROM * 1.8f, (100 - DIAL_ALERT_FROM) * 1.8f, false, zone)
        for (i in 0..TICKS) {
            val a = PI * (1 - i.toDouble() / TICKS)
            val inner = r * (if (i % 5 == 0) 1f - MAJOR_TICK else 1f - MINOR_TICK) - zone.strokeWidth
            val outer = r - zone.strokeWidth
            canvas.drawLine(cx + (cos(a) * inner).toFloat(), cy - (sin(a) * inner).toFloat(), cx + (cos(a) * outer).toFloat(), cy - (sin(a) * outer).toFloat(), tick)
        }
        val rad = Math.toRadians(angle.toDouble())
        val length = r * NEEDLE_SHARE
        canvas.drawLine(cx, cy, cx + (sin(rad) * length).toFloat(), cy - (cos(rad) * length).toFloat(), needle)
        canvas.drawCircle(cx, cy, r * HUB_SHARE, hub)
    }

    private companion object {
        const val NEEDLE_MS = 300L
        const val NEEDLE_OVERSHOOT = 0.8f
        const val TICKS = 10

        /** Proportions du dessin, en part du rayon. */
        const val RADIUS_SHARE = 0.94f
        const val HUB_SHARE = 0.09f
        const val TICK_WIDTH = 0.035f
        const val ZONE_WIDTH = 0.045f
        const val NEEDLE_WIDTH = 0.04f
        const val NEEDLE_SHARE = 0.8f
        const val MAJOR_TICK = 0.2f
        const val MINOR_TICK = 0.11f
    }
}
