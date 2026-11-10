package app.mate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * Barres qui dansent dans l'îlot du lecteur ([MusicIsland]), comme dans la Dynamic Island : quatre barres qui montent
 * et descendent tant que ça joue, figées à mi-hauteur en pause ou sans animations. Ne se redessine que visible.
 */
class IslandBars @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Joue (et donc bouge) ; posé par l'îlot à chaque relecture. */
    var playing = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Animations permises (Réglages > Animations, et celles d'Android) : sinon, barres figées. */
    var animated = true

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val gap = width / (BARS * 3f + 1f)
        val bar = gap * 2f
        val moving = playing && animated
        val now = SystemClock.uptimeMillis() / 1000.0
        for (i in 0 until BARS) {
            val k = if (moving) (0.6 + 0.4 * sin(now * SPEED + i * PHASE)).toFloat() else REST
            val h = height * k.coerceIn(MIN, 1f)
            val left = gap + i * (bar + gap)
            canvas.drawRoundRect(left, height - h, left + bar, height.toFloat(), bar / 2, bar / 2, paint)
        }
        if (moving && isShown) postInvalidateOnAnimation()
    }

    private companion object {
        const val BARS = 4
        const val SPEED = 6.0
        const val PHASE = 1.9
        const val MIN = 0.25f
        const val REST = 0.5f
    }
}
