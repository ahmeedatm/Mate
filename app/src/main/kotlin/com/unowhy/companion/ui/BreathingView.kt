package com.unowhy.companion.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.unowhy.companion.R
import com.unowhy.companion.focus.Breathing
import kotlin.math.PI
import kotlin.math.sin

/**
 * Ondes de la respiration : trois vagues qui montent à l'inspiration, tiennent, et redescendent à l'expiration
 * ([Breathing.level]). Dessinées à la main toutes les [FRAME_MS] pendant l'exercice (le kiosque coupe les
 * animateurs d'Android) ; à l'arrêt, une image fixe et plus aucun dessin.
 */
class BreathingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Changement d'étape ou de seconde, sur le fil principal. */
    var onMoment: ((Breathing.Moment) -> Unit)? = null

    /** Fin de l'exercice : `true` à la durée choisie, `false` arrêté avant. */
    var onEnd: ((Boolean) -> Unit)? = null

    var running = false
        private set

    private var pattern = Breathing.Pattern.COHERENCE
    private var startedAt = 0L
    private var endMs = 0L
    private var last: Pair<Breathing.Step, Int>? = null

    private val hue = themeColor(context, R.attr.c_hue_cyan)
    private val paints = listOf(40, 70, 105).map { alpha ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(hue, alpha) }
    }
    private val path = Path()
    private val clip = Path()
    private val bounds = RectF()
    private val radius = 32 * resources.displayMetrics.density

    fun start(pattern: Breathing.Pattern, minutes: Int) {
        this.pattern = pattern
        startedAt = SystemClock.uptimeMillis()
        endMs = Breathing.endMs(pattern, minutes)
        last = null
        running = true
        Breathing.running = true
        invalidate()
    }

    fun stop() = end(finished = false)

    private fun end(finished: Boolean) {
        if (!running) return
        running = false
        Breathing.running = false
        last = null
        invalidate()
        onEnd?.invoke(finished)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val level = if (running) moment(now - startedAt) else IDLE_LEVEL
        val w = width.toFloat()
        val h = height.toFloat()
        // Ligne d'eau : basse poumons vides, haute poumons pleins.
        val base = h * (0.85f - 0.65f * level)
        canvas.save()
        canvas.clipPath(clip)
        paints.forEachIndexed { i, paint ->
            val amplitude = h * (0.025f + 0.012f * i)
            val wavelength = w / (1.1f + 0.45f * i)
            val shift = (now % 60_000L) / 1_000f * (0.35f + 0.2f * i) * if (i % 2 == 0) 1 else -1
            val offset = base + h * 0.04f * i
            path.reset()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + STEP) {
                path.lineTo(x, offset + amplitude * sin(2 * PI.toFloat() * x / wavelength + shift))
                x += STEP
            }
            path.lineTo(w, h)
            path.close()
            canvas.drawPath(path, paint)
        }
        canvas.restore()
        if (running && isShown) postInvalidateDelayed(FRAME_MS)
    }

    /** Niveau de l'onde, et l'étape annoncée quand elle change ; fin de l'exercice à la durée choisie. */
    private fun moment(elapsed: Long): Float {
        if (elapsed >= endMs) {
            post { end(finished = true) }
            return 0f
        }
        val m = Breathing.at(pattern, elapsed)
        val key = m.step to m.secondsLeft
        if (key != last) {
            last = key
            // Hors du dessin : changer un texte demande une mise en page.
            post { if (running) onMoment?.invoke(m) }
        }
        return m.level
    }

    /** Temps restant de l'exercice, en millisecondes. */
    fun remainingMs(): Long = if (running) (endMs - (SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0) else 0

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (running && isShown) invalidate()
    }

    private companion object {
        const val FRAME_MS = 33L
        const val STEP = 12f
        const val IDLE_LEVEL = 0.35f
    }
}
