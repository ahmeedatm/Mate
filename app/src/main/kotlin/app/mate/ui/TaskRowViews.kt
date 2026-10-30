package app.mate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withTranslation
import androidx.dynamicanimation.animation.SpringAnimation
import app.mate.R

/**
 * Case ronde d'une tâche : 24 dp, contour `c_text_faint` ; cochée, aplat `c_ok` et coche qui se trace
 * (ressort 1600 / 1) pendant que la case reprend sa taille (ressort 1400 / 0,9). La zone touchable déborde de 12 dp
 * à droite et en hauteur ; seul le rond de 24 dp est dessiné, calé à gauche.
 */
class TaskCheck @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = themeColor(context, R.attr.c_text_faint)
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_ok) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * (16f / 24f) * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = themeColor(context, R.attr.c_surface)
    }

    /** Tracé de la coche du prototype (viewBox 24, affiché à 16 dp au centre du rond). */
    private val tickPath = Path().apply {
        val s = 16f / 24f * density
        val o = 4f * density
        moveTo(o + 6f * s, o + 12.5f * s)
        lineTo(o + 10f * s, o + 16.5f * s)
        lineTo(o + 18f * s, o + 7.5f * s)
    }
    private val tickMeasure = PathMeasure(tickPath, false)
    private val segment = Path()

    private var done = false
    private var progress = 0f
    private var grow = 1f
    private var springs = emptyList<SpringAnimation?>()

    fun setDone(done: Boolean, animate: Boolean) {
        springs.forEach { it?.cancel() }
        this.done = done
        progress = if (done) 1f else 0f
        grow = 1f
        if (done && animate) {
            springs = listOf(
                Look.spring(context, Look.EFFECTS_DEFAULT, 0f, 1f, { progress = it; invalidate() }),
                Look.spring(context, Look.SPATIAL_FAST, 0.7f, 1f, { grow = it; invalidate() }),
            )
            progress = 0f
            grow = 0.7f
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        springs.forEach { it?.cancel() }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = 12f * density
        val cy = height / 2f
        canvas.withTranslation(0f, cy - radius) {
            scale(grow, grow, radius, radius)
            if (done) {
                drawCircle(radius, radius, radius, disc)
                segment.rewind()
                tickMeasure.getSegment(0f, tickMeasure.length * progress.coerceIn(0f, 1f), segment, true)
                drawPath(segment, tick)
            } else {
                drawCircle(radius, radius, radius - ring.strokeWidth / 2, ring)
            }
        }
    }
}

/** Libellé d'une tâche : une barre de 1,5 dp se dessine de gauche à droite quand elle est faite (ressort 1400 / 0,9). */
class StrikeText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_text_faint) }
    private var strike = 0f
    private var spring: SpringAnimation? = null

    fun setStruck(struck: Boolean, animate: Boolean) {
        spring?.cancel()
        strike = if (struck) 1f else 0f
        if (struck && animate) spring = Look.spring(context, Look.SPATIAL_FAST, 0f, 1f, { strike = it; invalidate() })
        invalidate()
    }

    override fun onDetachedFromWindow() {
        spring?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = layout ?: return
        if (strike <= 0f || layout.lineCount == 0) return
        val density = resources.displayMetrics.density
        val y = totalPaddingTop + layout.getLineBaseline(0) - textSize * 0.3f
        val start = totalPaddingLeft.toFloat()
        canvas.drawRect(start, y - 0.75f * density, start + layout.getLineWidth(0) * strike, y + 0.75f * density, line)
    }
}
