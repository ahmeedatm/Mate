package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import app.mate.R
import app.mate.focus.FocusTicks
import app.mate.ui.themeColor

/**
 * Les 25 graduations du cadran Focus, à plat (ligne et colonne de [FocusWidget]) : une barre fine par minute, les
 * dernières allumées de la teinte de la phase, comme sur le cadran. Couchée, elle se vide de gauche à droite ; debout,
 * de haut en bas. Une seule vue, dessinée à la main.
 */
class FocusBand(context: Context, private val vertical: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val off = themeColor(context, R.attr.c_graphic)
    private val rect = RectF()
    private var lit = 0
    private var color = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(lit: Int, color: Int) {
        if (lit == this.lit && color == this.color) return
        this.lit = lit
        this.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = FocusTicks.COUNT
        val thick = resources.displayMetrics.density * TICK_DP
        val length = if (vertical) height.toFloat() else width.toFloat()
        val step = (length - thick) / (n - 1)
        val r = thick / 2
        for (i in 0 until n) {
            val start = i * step
            if (vertical) rect.set(0f, start, width.toFloat(), start + thick) else rect.set(start, 0f, start + thick, height.toFloat())
            paint.color = if (i >= n - lit) color else off
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    private companion object {
        const val TICK_DP = 4
    }
}
