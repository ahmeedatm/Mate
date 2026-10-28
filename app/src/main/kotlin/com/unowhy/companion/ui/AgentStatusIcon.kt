package com.unowhy.companion.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.unowhy.companion.R
import com.unowhy.companion.net.AgentState

/**
 * État d'une session, dessiné comme dans Orca : une roue orange qui tourne (travaille), un écran jaune qui pulse
 * (surveille une tâche de fond), un point rouge qui pulse (attend), une coche verte (fini), un point bleu (limite),
 * un cercle gris (inactif). Les animations ne touchent que la rotation ou l'opacité (fil de rendu) et s'arrêtent hors écran.
 */
class AgentStatusIcon @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var state = AgentState.IDLE
    private var monitoring = false
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val arc = RectF()
    private val path = Path()

    fun bind(state: AgentState, monitoring: Boolean) {
        if (this.state == state && this.monitoring == monitoring) return
        this.state = state
        this.monitoring = monitoring
        invalidate()
        restart()
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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        val stroke = s * 0.13f
        paint.color = color()
        paint.strokeWidth = stroke
        when {
            state == AgentState.WORKING && monitoring -> {
                // Écran : un cadre arrondi et son pied.
                paint.style = Paint.Style.STROKE
                arc.set(w / 2 - s * 0.42f, h / 2 - s * 0.36f, w / 2 + s * 0.42f, h / 2 + s * 0.18f)
                canvas.drawRoundRect(arc, s * 0.08f, s * 0.08f, paint)
                canvas.drawLine(w / 2, h / 2 + s * 0.18f, w / 2, h / 2 + s * 0.36f, paint)
                canvas.drawLine(w / 2 - s * 0.2f, h / 2 + s * 0.38f, w / 2 + s * 0.2f, h / 2 + s * 0.38f, paint)
            }
            state == AgentState.WORKING -> {
                // Roue : un arc de trois quarts, que l'animation fait tourner.
                paint.style = Paint.Style.STROKE
                arc.set(w / 2 - s * 0.38f, h / 2 - s * 0.38f, w / 2 + s * 0.38f, h / 2 + s * 0.38f)
                canvas.drawArc(arc, 0f, 270f, false, paint)
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
                paint.style = Paint.Style.FILL
                canvas.drawCircle(w / 2, h / 2, s * 0.32f, paint)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) restart()
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        rotation = 0f
        alpha = 1f
    }

    private fun restart() {
        stopAnimation()
        if (!isAttachedToWindow || !isShown || Look.reduced(context)) return
        animator = when {
            state == AgentState.WORKING && !monitoring -> ObjectAnimator.ofFloat(this, ROTATION, 0f, 360f).apply {
                duration = 900
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
            }
            state == AgentState.WORKING || state == AgentState.WAITING -> ObjectAnimator.ofFloat(this, ALPHA, 1f, 0.35f).apply {
                duration = if (state == AgentState.WAITING) 700 else 1_400
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
            else -> null
        }?.also { it.start() }
    }
}
