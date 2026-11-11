package app.mate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.view.doOnLayout
import app.mate.AnimFamily
import app.mate.R

/** Un écran qui trace ses cartes principales à son arrivée (thèmes Instrument) : MainActivity les confie au tracé partagé. */
interface TracedScreen {
    /** Les cartes à tracer, dans l'ordre ; vide si l'écran n'en montre pas pour l'instant. */
    fun tracedCards(): List<View>
}

/**
 * Tracé des cadres à l'arrivée d'un écran des thèmes Instrument : un seul calque transparent posé sur la grille, qui dessine
 * le cadre de chaque module au trait fin ([PathMeasure.getSegment]) avec une petite plume c_signal en tête. Le contenu du
 * module (ses vues) est à alpha 0 jusqu'à son heure, puis apparaît : fondu, ou paliers pour une fenêtre LCD. Délais et
 * plafond : [FrameTracerModel]. Une seule animation pilote tout ; `invalidate` ne part que pendant le tracé, le calque se
 * retire à la fin. Coupé : [wanted] est faux, l'appelant ne l'utilise pas et rien ne passe à alpha 0.
 */
class FrameTracer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Item(val view: View, val frame: FrameTracerModel.Frame, val lcd: Boolean) {
        val rect = RectF()
        var radius = 0f
        val path = Path()
        var length = 0f
        var alpha = -1f
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * resources.displayMetrics.density
        color = themeColor(context, R.attr.c_text)
    }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_signal) }
    private val segment = Path()
    private val measure = PathMeasure()
    private val pos = FloatArray(2)
    private var items = emptyList<Item>()
    private var clock: ValueAnimator? = null
    private var elapsed = 0L
    private var generation = 0

    /**
     * Trace les cadres de [views] (dans l'ordre : un par module), à partir de [lead] ms. Leur contenu passe à alpha 0 tout de
     * suite. [lcd] dit quelles vues sont des fenêtres LCD. Remplace un tracé en cours.
     */
    fun play(views: List<View>, lcd: (View) -> Boolean, lead: Long = 0L) {
        stop()
        val plan = FrameTracerModel.plan(views.size, lead, enabled = true)
        if (plan.isEmpty()) return
        items = views.mapIndexed { i, v -> Item(v, plan[i], lcd(v)) }
        items.forEach { it.view.alpha = 0f }
        visibility = VISIBLE
        val turn = ++generation
        doOnLayout { if (turn == generation) begin(FrameTracerModel.totalMs(plan)) }
    }

    private fun begin(total: Long) {
        val origin = IntArray(2).also { getLocationOnScreen(it) }
        val at = IntArray(2)
        items.forEach { item ->
            item.view.getLocationOnScreen(at)
            val half = stroke.strokeWidth / 2
            item.rect.set(
                at[0] - origin[0] + half, at[1] - origin[1] + half,
                at[0] - origin[0] + item.view.width * item.view.scaleX - half, at[1] - origin[1] + item.view.height * item.view.scaleY - half,
            )
            item.radius = ((item.view.background?.current as? GradientDrawable)?.cornerRadius ?: 0f).coerceAtLeast(context.dp(MIN_RADIUS_DP).toFloat())
            item.path.reset()
            item.path.addRoundRect(item.rect, item.radius, item.radius, Path.Direction.CW)
            measure.setPath(item.path, false)
            item.length = measure.length
        }
        elapsed = 0L
        clock = ValueAnimator.ofInt(0, total.toInt()).apply {
            duration = total
            interpolator = LinearInterpolator()
            addUpdateListener {
                elapsed = (it.animatedValue as Int).toLong()
                step()
            }
            // Fin comme annulation : tout est visible et le calque ne redessine plus.
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = settle()
            })
            start()
        }
    }

    private fun step() {
        items.forEach { item ->
            val a = FrameTracerModel.contentAlpha(elapsed, item.frame.startMs, item.lcd, item.frame.traced)
            if (a != item.alpha) {
                item.alpha = a
                item.view.alpha = a
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        items.forEach { item ->
            if (!item.frame.traced) return@forEach
            val line = FrameTracerModel.lineAlpha(elapsed, item.frame.startMs)
            val p = EASE.getInterpolation(FrameTracerModel.progress(elapsed, item.frame.startMs))
            if (line <= 0f || p <= 0f) return@forEach
            measure.setPath(item.path, false)
            segment.reset()
            measure.getSegment(0f, item.length * p, segment, true)
            stroke.alpha = (line * FULL).toInt()
            canvas.drawPath(segment, stroke)
            if (p < 1f) {
                measure.getPosTan(item.length * p, pos, null)
                canvas.drawCircle(pos[0], pos[1], context.dp(PEN_DP).toFloat(), pen)
            }
        }
    }

    /** Arrête le tracé : tout est visible, le calque se retire (plus aucun dessin). */
    fun stop() {
        generation++
        clock?.let {
            clock = null
            it.cancel()
        }
        settle()
    }

    private fun settle() {
        items.forEach { it.view.alpha = 1f }
        items = emptyList()
        visibility = GONE
        elapsed = 0L
    }

    companion object {
        private const val STROKE_DP = 1.5f
        private const val PEN_DP = 3
        private const val MIN_RADIUS_DP = 4
        private const val FULL = 255

        /** Courbe du tracé (maquette : la même que l'aplat). */
        private val EASE = PathInterpolator(0.65f, 0f, 0.35f, 1f)

        /** Thème Instrument (rangée de touches) et animations de l'Accueil jouées. */
        fun wanted(context: Context): Boolean = FrameTracerModel.enabled(
            instrument = themePx(context, R.attr.c_dock_height) > 0,
            homeOn = Look.on(context, AnimFamily.HOME),
            reduced = Look.reduced(context),
        )
    }
}
