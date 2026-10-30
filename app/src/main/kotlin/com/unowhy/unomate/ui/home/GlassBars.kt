package com.unowhy.unomate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import com.unowhy.unomate.R
import com.unowhy.unomate.ui.Look
import com.unowhy.unomate.ui.dp
import com.unowhy.unomate.ui.themeColor

/**
 * Les verres d'eau du jour : une barre de 8 × 30 dp par verre de l'objectif, remplie par le bas (échelle Y, ressort).
 * Un seul `View` ; il ne se redessine que pendant qu'une barre se remplit ou se vide.
 */
class GlassBars(context: Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    private val track = themeColor(context, R.attr.c_stroke)
    private val ok = themeColor(context, R.attr.c_ok)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fills = FloatArray(0)
    private var springs = arrayOfNulls<SpringAnimation>(0)
    private val barW = dp(8)
    private val barH = dp(30)
    private val gap = dp(5)

    /** Verres bus et objectif. `animate` : la barre qui change se remplit (ou se vide) sur un ressort. */
    fun show(glasses: Int, goal: Int, animate: Boolean) {
        if (goal != fills.size) {
            springs.forEach { it?.cancel() }
            fills = FloatArray(goal)
            springs = arrayOfNulls(goal)
            requestLayout()
        }
        for (i in 0 until goal) {
            val target = if (i < glasses) 1f else 0f
            if (fills[i] == target) continue
            springs[i]?.cancel()
            if (animate) {
                springs[i] = Look.spring(context, Look.SPATIAL_DEFAULT, fills[i], target, { fills[i] = it; invalidate() })
            } else {
                fills[i] = target
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        springs.forEach { it?.cancel() }
        for (i in fills.indices) fills[i] = fills[i].let { if (it >= 0.5f) 1f else 0f }
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (fills.isEmpty()) 0 else fills.size * barW + (fills.size - 1) * gap
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(barH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val top = (height - barH) / 2f
        val radius = barW / 2f
        for (i in fills.indices) {
            val left = i * (barW + gap).toFloat()
            paint.color = track
            canvas.drawRoundRect(left, top, left + barW, top + barH, radius, radius, paint)
            if (fills[i] > 0f) {
                paint.color = ok
                canvas.drawRoundRect(left, top + barH * (1f - fills[i]), left + barW, top + barH, radius, radius, paint)
            }
        }
    }
}
