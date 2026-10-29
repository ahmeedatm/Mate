package com.unowhy.unomate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.unowhy.unomate.R
import com.unowhy.unomate.net.AgentState
import kotlin.math.PI
import kotlin.math.cos

/**
 * État d'une session, dessiné comme dans Orca : une roue orange qui tourne (travaille), un écran jaune qui pulse
 * (surveille une tâche de fond), un point rouge qui pulse (attend), une coche verte (fini), un point bleu (limite),
 * un cercle gris (inactif).
 *
 * L'animation est dessinée à la main, 20 images par seconde, à partir de l'horloge : le kiosque coupe les animateurs
 * d'Android (`animator_duration_scale 0`, docs/kiosque.md) et une roue animée par eux resterait figée. Elle ne tourne que
 * tant que l'icône est à l'écran.
 */
class AgentStatusIcon @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var state = AgentState.IDLE
    private var monitoring = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val arc = RectF()
    private val path = Path()

    fun bind(state: AgentState, monitoring: Boolean) {
        if (this.state == state && this.monitoring == monitoring) return
        this.state = state
        this.monitoring = monitoring
        invalidate()
    }

    private fun color() = themeColor(
        context,
        when {
            state == AgentState.WORKING && monitoring -> R.attr.c_agent_monitor
            state == AgentState.WORKING -> R.attr.c_agent_work
            state == AgentState.WAITING -> R.attr.c_agent_wait
            state == AgentState.DONE -> R.attr.c_agent_done
            state == AgentState.LIMIT -> R.attr.c_agent_limit
            else -> R.attr.c_text_faint
        },
    )

    private val animated get() = state == AgentState.WORKING || state == AgentState.WAITING

    /** Opacité d'une pulsation de `periodMs` : de 35 % à 100 %, en douceur. */
    private fun pulse(now: Long, periodMs: Long): Int {
        val phase = (now % periodMs).toDouble() / periodMs
        return (255 * (0.35 + 0.65 * (0.5 + 0.5 * cos(2 * PI * phase)))).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        // Réduire les animations : roue arrêtée, voyant allumé en plein (pulsation à son maximum), pas d'image suivante.
        val still = Look.still(context)
        val now = if (still) 0L else SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        paint.color = color()
        paint.strokeWidth = s * 0.13f
        when {
            state == AgentState.WORKING && monitoring -> {
                // Écran : un cadre arrondi et son pied, qui pulse.
                paint.alpha = pulse(now, 1_600)
                paint.style = Paint.Style.STROKE
                arc.set(w / 2 - s * 0.42f, h / 2 - s * 0.36f, w / 2 + s * 0.42f, h / 2 + s * 0.18f)
                canvas.drawRoundRect(arc, s * 0.08f, s * 0.08f, paint)
                canvas.drawLine(w / 2, h / 2 + s * 0.18f, w / 2, h / 2 + s * 0.36f, paint)
                canvas.drawLine(w / 2 - s * 0.2f, h / 2 + s * 0.38f, w / 2 + s * 0.2f, h / 2 + s * 0.38f, paint)
            }
            state == AgentState.WORKING -> {
                // Roue : un arc de trois quarts qui fait un tour en 0,9 s.
                paint.style = Paint.Style.STROKE
                arc.set(w / 2 - s * 0.38f, h / 2 - s * 0.38f, w / 2 + s * 0.38f, h / 2 + s * 0.38f)
                canvas.drawArc(arc, (now % SPIN_MS) * 360f / SPIN_MS, 270f, false, paint)
            }
            state == AgentState.DONE -> {
                paint.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(w / 2 - s * 0.36f, h / 2 + s * 0.02f)
                path.lineTo(w / 2 - s * 0.1f, h / 2 + s * 0.28f)
                path.lineTo(w / 2 + s * 0.38f, h / 2 - s * 0.26f)
                canvas.drawPath(path, paint)
            }
            state == AgentState.IDLE -> {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(w / 2, h / 2, s * 0.3f, paint)
            }
            else -> {
                if (state == AgentState.WAITING) paint.alpha = pulse(now, 900)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(w / 2, h / 2, s * 0.32f, paint)
            }
        }
        if (animated && isShown && !still) postInvalidateDelayed(FRAME_MS)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (animated && isShown) invalidate()
    }

    private companion object {
        const val SPIN_MS = 900L
        const val FRAME_MS = 50L
    }
}
