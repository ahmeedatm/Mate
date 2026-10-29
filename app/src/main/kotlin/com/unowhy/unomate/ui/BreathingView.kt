package com.unowhy.unomate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.unowhy.unomate.R
import com.unowhy.unomate.focus.Breathing
import kotlin.math.min

/**
 * Sphère de la respiration : elle gonfle à l'inspiration, tient, et dégonfle à l'expiration ([Breathing.level]) ;
 * pendant l'exercice, des ondes s'en échappent doucement. Éclairée en haut à gauche (dégradé), sans cadre : elle prend
 * la place libre. Dessinée à la main toutes les [FRAME_MS] pendant l'exercice (le kiosque coupe les animateurs
 * d'Android) ; à l'arrêt, une image fixe et plus aucun dessin.
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
    private val sphere = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(hue, 36) }
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
        color = hue
    }

    /** Dégradé calculé une fois pour le plus grand rayon, ramené au rayon du moment par une matrice. */
    private val scale = Matrix()
    private var maxRadius = 0f

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
        // Place pour les ondes autour de la sphère pleine.
        maxRadius = min(w, h) / 2f * 0.78f
        // Pâle (la consigne, foncée, se lit dessus de loin) : reflet presque blanc, bord à la teinte de la fonction.
        val white = 0xFFFFFFFF.toInt()
        sphere.shader = RadialGradient(
            w / 2f - maxRadius * 0.35f, h / 2f - maxRadius * 0.4f, maxRadius * 1.5f,
            intArrayOf(ColorUtils.blendARGB(hue, white, 0.85f), ColorUtils.blendARGB(hue, white, 0.55f), ColorUtils.blendARGB(hue, white, 0.15f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val level = if (running) moment(now - startedAt) else IDLE_LEVEL
        val cx = width / 2f
        val cy = height / 2f
        val radius = maxRadius * (MIN_SIZE + (1 - MIN_SIZE) * level)
        if (running) {
            // Ondes : trois cercles qui partent de la sphère et s'effacent en s'éloignant.
            for (i in 0 until RIPPLES) {
                val t = ((now + i * RIPPLE_MS / RIPPLES) % RIPPLE_MS) / RIPPLE_MS.toFloat()
                ripple.alpha = (90 * (1 - t)).toInt()
                canvas.drawCircle(cx, cy, radius + maxRadius * 0.25f * t, ripple)
            }
        }
        canvas.drawCircle(cx, cy, radius * 1.08f, halo)
        val s = radius / maxRadius
        scale.setScale(s, s, cx, cy)
        sphere.shader?.setLocalMatrix(scale)
        canvas.drawCircle(cx, cy, radius, sphere)
        if (running && isShown) postInvalidateDelayed(FRAME_MS)
    }

    /** Niveau de la sphère, et l'étape annoncée quand elle change ; fin de l'exercice à la durée choisie. */
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
        const val IDLE_LEVEL = 0.35f

        /** Sphère vide : cette part du rayon plein. */
        const val MIN_SIZE = 0.45f
        const val RIPPLES = 3
        const val RIPPLE_MS = 3_000L
    }
}
