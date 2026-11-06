package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import app.mate.R
import app.mate.ui.themeColor
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cadran des minutes de l'Horloge (direction Cadran, #180) : 60 graduations, celles des minutes passées de l'heure
 * allumées depuis le haut. Redessiné une fois par minute ([show]), rien ne bouge entre deux.
 */
class ClockDial(context: Context) : View(context) {
    private val on = themeColor(context, R.attr.c_accent)
    private val off = themeColor(context, R.attr.c_graphic)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cos = FloatArray(ClockFace.TICKS) { cos(angle(it)) }
    private val sin = FloatArray(ClockFace.TICKS) { sin(angle(it)) }
    private var lit = 0

    /** Allume les [count] premières graduations ; même nombre : rien à redessiner. */
    fun show(count: Int) {
        if (count == lit) return
        lit = count
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) / 2f
        val stroke = radius / STROKE_PART
        val outer = radius - stroke
        val cx = width / 2f
        val cy = height / 2f
        paint.strokeWidth = stroke
        for (i in 0 until ClockFace.TICKS) {
            // Une graduation sur cinq plus longue : les repères des 5 minutes se lisent sans chiffres.
            val inner = outer * if (i % 5 == 0) MAJOR else MINOR
            paint.color = if (i < lit) on else off
            canvas.drawLine(cx + inner * cos[i], cy + inner * sin[i], cx + outer * cos[i], cy + outer * sin[i], paint)
        }
    }

    private fun angle(i: Int) = (i.toFloat() / ClockFace.TICKS * 2 * Math.PI - Math.PI / 2).toFloat()

    private companion object {
        /** Épaisseur d'une graduation : le rayon divisé par ce nombre. */
        const val STROKE_PART = 40f

        /** Début des graduations, en part du rayon extérieur. */
        const val MAJOR = 0.86f
        const val MINOR = 0.91f
    }
}
