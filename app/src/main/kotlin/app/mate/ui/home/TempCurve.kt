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
import kotlin.math.roundToInt

/**
 * Courbe des prochaines heures de la Météo (direction Cadran, #180) : un point par heure, relié, la température écrite
 * à côté. [across] : de gauche à droite (l'heure dessous si [withHours]) ; sinon de haut en bas, l'heure à gauche.
 * Autant d'heures que la place en laisse, une étiquette sur deux si elles se touchent. Tout se calcule quand les données
 * ou la taille changent ([plan]) ; le dessin ne fait que tracer ce qui est prêt.
 */
class TempCurve(context: Context) : View(context) {
    var across = true
        set(value) { if (field != value) { field = value; replan() } }
    var withHours = true
        set(value) { if (field != value) { field = value; replan() } }

    /** Nombre d'heures au plus (la Pastille en garde 5, comme sur la maquette). */
    var maxPoints = Weather.HOURS
        set(value) { if (field != value) { field = value; replan() } }
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

    /** Ce que [onDraw] trace, calculé par [plan] : points, textes et leur place. Vide : rien à dessiner. */
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var temps = emptyArray<String>()
    private var hourTexts = emptyArray<String>()
    private var labeled = BooleanArray(0)

    fun show(list: List<Weather.Hour>) {
        if (sameHours(list)) return
        hours = list
        replan()
    }

    /** Mêmes heures, mêmes températures : rien à recalculer (la Météo repasse chaque minute). */
    private fun sameHours(list: List<Weather.Hour>) =
        list.size == hours.size && list.indices.all { list[it].at == hours[it].at && list[it].temperature == hours[it].temperature }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        plan()
    }

    private fun replan() {
        contentDescription = hours.take(maxPoints).joinToString(", ") { context.getString(R.string.widget_weather_hour, it.hour, it.temperature.roundToInt()) }
        plan()
        invalidate()
    }

    private fun plan() {
        xs = FloatArray(0)
        // Trop petite (Pastille d'une rangée fine) : rien plutôt qu'un trait illisible.
        val room = if (across) height else width
        if (hours.size < 2 || width == 0 || height == 0 || room < context.dp(MIN_ROOM_DP)) return
        // Étiquettes à la taille de la courbe : lisibles de loin en grand, discrètes en Pastille.
        temp.textSize = (room * LABEL_PART).coerceIn(sp(TEMP_SP), sp(TEMP_MAX_SP))
        hour.textSize = temp.textSize * HOUR_PART
        if (across) planAcross() else planDown()
    }

    private fun planAcross() {
        val labelW = temp.measureText("-88°")
        val n = CurvePlan.count(width - labelW, context.dp(MIN_STEP_DP).toFloat(), minOf(hours.size, maxPoints))
        if (n == 0) return
        val list = hours.take(n)
        val top = temp.textSize * LABEL_ROOM
        val bottom = height - if (withHours) hour.textSize * LABEL_ROOM else radius * 2
        val step = (width - labelW) / (n - 1)
        val every = CurvePlan.every(labelW * LABEL_GAP, step)
        temp.textAlign = Paint.Align.CENTER
        hour.textAlign = Paint.Align.CENTER
        fill(list, FloatArray(n) { labelW / 2 + it * step }, CurvePlan.place(list.map { it.temperature }, bottom, top)) { CurvePlan.labeled(it, n, every) }
    }

    private fun planDown() {
        val hourW = hour.measureText("88h") + context.dp(8)
        val labelW = temp.measureText("-88°") + context.dp(8)
        // Trop étroite : la courbe s'inverserait et les étiquettes se chevaucheraient.
        if (width - labelW <= hourW + radius) return
        val n = CurvePlan.count(height - hour.textSize * 2, context.dp(MIN_ROW_DP).toFloat(), minOf(hours.size, maxPoints))
        if (n == 0) return
        val list = hours.take(n)
        val step = (height - hour.textSize * 2) / (n - 1)
        temp.textAlign = Paint.Align.LEFT
        hour.textAlign = Paint.Align.LEFT
        fill(list, CurvePlan.place(list.map { it.temperature }, hourW + radius, width - labelW), FloatArray(n) { hour.textSize + it * step }) { true }
    }

    private fun fill(list: List<Weather.Hour>, x: FloatArray, y: FloatArray, label: (Int) -> Boolean) {
        temps = Array(list.size) { context.getString(R.string.widget_weather_temp, list[it].temperature.roundToInt()) }
        hourTexts = Array(list.size) { context.getString(R.string.widget_weather_hour_short, list[it].hour) }
        labeled = BooleanArray(list.size) { label(it) }
        path.reset()
        for (i in list.indices) if (i == 0) path.moveTo(x[0], y[0]) else path.lineTo(x[i], y[i])
        ys = y
        xs = x
    }

    override fun onDraw(canvas: Canvas) {
        if (xs.isEmpty()) return
        canvas.drawPath(path, line)
        for (i in xs.indices) {
            canvas.drawCircle(xs[i], ys[i], radius, dot)
            if (!across) {
                canvas.drawText(hourTexts[i], 0f, ys[i] + hour.textSize / 3, hour)
                canvas.drawText(temps[i], xs[i] + radius * 3, ys[i] + temp.textSize / 3, temp)
            } else if (labeled[i]) {
                canvas.drawText(temps[i], xs[i], ys[i] - radius * 2, temp)
                if (withHours) canvas.drawText(hourTexts[i], xs[i], height - hour.descent(), hour)
            }
        }
    }

    private fun text(colorAttr: Int, fontAttr: Int, sp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, colorAttr)
        typeface = themeFont(context, fontAttr)
        textSize = sp(sp)
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private companion object {
        const val TEMP_SP = 14f
        const val HOUR_SP = 13f
        const val TEMP_MAX_SP = 22f

        /** Taille des températures en part de la hauteur (de la largeur debout) ; les heures un peu plus petites. */
        const val LABEL_PART = 0.07f
        const val HOUR_PART = 0.9f

        /** En dessous de cette place, la courbe ne se dessine pas. */
        const val MIN_ROOM_DP = 48

        /** Écart minimal entre deux heures : en travers, puis de haut en bas. */
        const val MIN_STEP_DP = 34
        const val MIN_ROW_DP = 30

        /** Place d'une ligne d'étiquettes, en tailles de texte. */
        const val LABEL_ROOM = 1.6f

        /** Une étiquette de température prend sa largeur plus cette marge avant la suivante. */
        const val LABEL_GAP = 1.2f
    }
}
