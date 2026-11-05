package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Courbe des prochaines heures de la Météo (direction Cadran, #180) : un point par heure, relié, la température écrite
 * à côté. [across] : de gauche à droite (l'heure dessous si [withHours]) ; sinon de haut en bas, l'heure à gauche.
 * Autant d'heures que la place en laisse, une étiquette sur deux si elles se touchent. Redessinée seulement quand les
 * données changent ([show]).
 */
class TempCurve(context: Context) : View(context) {
    var across = true
    var withHours = true
    private var hours: List<Weather.Hour> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeJoin = Paint.Join.ROUND
        color = themeColor(context, R.attr.c_accent)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_accent) }
    private val temp = text(R.attr.c_text, R.attr.c_font_medium, TEMP_SP)
    private val hour = text(R.attr.c_text_faint, R.attr.c_font_regular, HOUR_SP)
    private val path = Path()
    private val radius = context.dp(3).toFloat()

    fun show(list: List<Weather.Hour>) {
        hours = list
        contentDescription = list.joinToString(", ") { context.getString(R.string.widget_weather_hour, it.hour, it.temperature.roundToInt()) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (hours.size < 2 || width == 0 || height == 0) return
        if (across) drawAcross(canvas) else drawDown(canvas)
    }

    private fun drawAcross(canvas: Canvas) {
        val labelW = temp.measureText("-88°")
        val n = count(width - labelW, context.dp(MIN_STEP_DP))
        val list = hours.take(n)
        val top = temp.textSize * LABEL_ROOM
        val bottom = height - if (withHours) hour.textSize * LABEL_ROOM else radius * 2
        val left = labelW / 2
        val step = (width - labelW) / (n - 1)
        val every = ceil(labelW * LABEL_GAP / step).toInt().coerceAtLeast(1)
        val ys = place(list, bottom, top)
        path.reset()
        list.indices.forEach { i -> if (i == 0) path.moveTo(left, ys[0]) else path.lineTo(left + i * step, ys[i]) }
        canvas.drawPath(path, line)
        list.forEachIndexed { i, h ->
            val x = left + i * step
            canvas.drawCircle(x, ys[i], radius, dot)
            if (i % every != 0 && i != n - 1) return@forEachIndexed
            canvas.drawText(degrees(h), x, ys[i] - radius * 2, temp.centered())
            if (withHours) canvas.drawText(context.getString(R.string.widget_weather_hour_short, h.hour), x, height - hour.descent(), hour.centered())
        }
    }

    private fun drawDown(canvas: Canvas) {
        val n = count(height - hour.textSize * 2, context.dp(MIN_ROW_DP))
        val list = hours.take(n)
        val hourW = hour.measureText("88h") + context.dp(8)
        val labelW = temp.measureText("-88°") + context.dp(8)
        val step = (height - hour.textSize * 2) / (n - 1)
        val xs = place(list, hourW + radius, width - labelW)
        path.reset()
        list.indices.forEach { i -> if (i == 0) path.moveTo(xs[0], hour.textSize) else path.lineTo(xs[i], hour.textSize + i * step) }
        canvas.drawPath(path, line)
        list.forEachIndexed { i, h ->
            val y = hour.textSize + i * step
            canvas.drawCircle(xs[i], y, radius, dot)
            canvas.drawText(context.getString(R.string.widget_weather_hour_short, h.hour), 0f, y + hour.textSize / 3, hour.start())
            canvas.drawText(degrees(h), xs[i] + radius * 3, y + temp.textSize / 3, temp.start())
        }
    }

    /** Nombre d'heures qui tiennent sur [length] px avec au moins [minStep] px entre deux. */
    private fun count(length: Float, minStep: Int) = (length / minStep).toInt().plus(1).coerceIn(2, hours.size)

    /** Position de chaque température entre [low] (la plus froide) et [high] (la plus chaude) ; toutes égales : au milieu. */
    private fun place(list: List<Weather.Hour>, low: Float, high: Float): FloatArray {
        val min = list.minOf { it.temperature }
        val max = list.maxOf { it.temperature }
        return FloatArray(list.size) { i -> if (max - min < FLAT) (low + high) / 2 else low + ((list[i].temperature - min) / (max - min)).toFloat() * (high - low) }
    }

    private fun degrees(h: Weather.Hour) = context.getString(R.string.widget_weather_temp, h.temperature.roundToInt())

    private fun text(colorAttr: Int, fontAttr: Int, sp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, colorAttr)
        typeface = themeFont(context, fontAttr)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
    }

    private fun Paint.centered() = apply { textAlign = Paint.Align.CENTER }
    private fun Paint.start() = apply { textAlign = Paint.Align.LEFT }

    private companion object {
        const val TEMP_SP = 13f
        const val HOUR_SP = 12f

        /** Écart minimal entre deux heures : en travers, puis de haut en bas. */
        const val MIN_STEP_DP = 34
        const val MIN_ROW_DP = 30

        /** Place d'une ligne d'étiquettes, en tailles de texte. */
        const val LABEL_ROOM = 1.6f

        /** Une étiquette de température prend sa largeur plus cette marge avant la suivante. */
        const val LABEL_GAP = 1.2f

        /** En dessous de cet écart (degrés), la courbe est plate. */
        const val FLAT = 0.5
    }
}
