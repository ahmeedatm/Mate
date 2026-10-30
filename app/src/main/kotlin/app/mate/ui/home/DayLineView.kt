package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import app.mate.R
import app.mate.net.CalendarEvent
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlin.math.roundToInt

/**
 * La ligne du jour (DA Cadran) : de 8 h à 20 h, les rendez-vous en barres, le passé effacé, un point rouge pour
 * maintenant. Un seul `View` dessiné à la main, sans enfant, qui ne se redessine qu'au battement de la minute et à
 * l'arrivée de l'écran. Il ne prend aucun toucher : l'appui long du mode Modifier et le balayage des pages restent libres.
 */
class DayLineView(context: Context) : View(context) {

    private val trackColor = themeColor(context, R.attr.c_graphic)
    private val fadedColor = themeColor(context, R.attr.c_stroke)
    private val pastColor = themeColor(context, R.attr.c_text_faint)
    private val nowColor = themeColor(context, R.attr.c_accent)
    private val laterColor = themeColor(context, R.attr.c_text_dim)
    private val haloColor = themeColor(context, R.attr.c_bg)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // Thèmes à tubes (Rétro, Cadran) : tubes derrière, textes un peu plus grands et détourés.
    private val retro = themeBool(context, R.attr.c_flow)
    private val hourPaint = textPaint(if (retro) 13f else 12f)
    private val eventPaint = textPaint(if (retro) 14f else 13f)

    private var events: List<CalendarEvent> = emptyList()
    private var now = System.currentTimeMillis()
    private var bars: List<DayLineModel.Bar> = emptyList()
    private var labels: Array<CharSequence> = emptyArray()
    private var barAlpha = FloatArray(0)
    /** « 8 h », « 10 h »… « 20 h » : une toutes les 2 h. */
    private val hourLabels = Array((DayLineModel.TO_HOUR - DayLineModel.FROM_HOUR) / 2 + 1) {
        context.getString(R.string.dayline_hour, DayLineModel.FROM_HOUR + it * 2)
    }

    // Ce qui bouge : tout le reste est fixe entre deux battements.
    private var markerX = 0f
    private var markerAlpha = 1f
    private var passScale = 1f
    private var ondeScale = 1f
    private var ondeAlpha = 0f
    private val springs = ArrayList<SpringAnimation>()
    private var markerSpring: SpringAnimation? = null
    private var ondeSprings: List<SpringAnimation> = emptyList()
    private val delayed = ArrayList<Runnable>()

    /** L'ordinateur ne répond plus : les rendez-vous affichés peuvent dater, ils sont atténués. */
    var stale = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        contentDescription = context.getString(R.string.widget_dayline)
    }

    private fun textPaint(sp: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = themeFont(context, R.attr.c_font_medium)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        Look.halo(context, this)
    }

    /** Agenda et heure : les barres sont recalculées. `animate` : le point avance sur son ressort et émet l'onde. */
    fun show(events: List<CalendarEvent>, now: Long, animate: Boolean) {
        this.events = events
        this.now = now
        rebuild()
        val target = markerTarget()
        markerSpring?.cancel()
        if (animate && markerX != target) {
            markerSpring = Look.spring(context, Look.SPATIAL_DEFAULT, markerX, target, { markerX = it; invalidate() })
            onde()
        } else {
            markerX = target
        }
        invalidate()
    }

    /** Arrivée de l'écran : le trait du passé se dessine, les barres apparaissent l'une après l'autre, puis le point et son onde. */
    fun arrive() {
        cancelAll()
        passScale = 0f
        barAlpha.fill(0f)
        markerAlpha = 0f
        invalidate()
        run(0) { track(Look.spring(context, Look.SPATIAL_SLOW, 0f, 1f, { passScale = it; invalidate() })) }
        for (i in barAlpha.indices) {
            run(120L + i * 60L) { track(Look.spring(context, Look.EFFECTS_DEFAULT, 0f, 1f, { barAlpha[i] = it; invalidate() })) }
        }
        run(520) {
            track(Look.spring(context, Look.EFFECTS_DEFAULT, 0f, 1f, { markerAlpha = it; invalidate() }))
            onde()
        }
    }

    private fun run(delayMs: Long, action: () -> Unit) {
        val runnable = Runnable(action)
        delayed += runnable
        postDelayed(runnable, delayMs)
    }

    private fun track(spring: SpringAnimation?) {
        if (spring != null) springs += spring
    }

    /** Onde du battement : un anneau qui grandit de 1 à 3,2 en s'effaçant. */
    private fun onde() {
        ondeSprings.forEach { it.cancel() }
        ondeSprings = listOfNotNull(
            Look.spring(context, Look.EFFECTS_SLOW, 1f, 3.2f, { ondeScale = it; invalidate() }),
            Look.spring(context, Look.EFFECTS_SLOW, 0.9f, 0f, { ondeAlpha = it; invalidate() }),
        )
    }

    private fun cancelAll() {
        delayed.forEach { removeCallbacks(it) }
        delayed.clear()
        (springs + listOfNotNull(markerSpring) + ondeSprings).forEach { it.cancel() }
        springs.clear()
        markerSpring = null
        ondeSprings = emptyList()
        passScale = 1f
        markerAlpha = 1f
        ondeAlpha = 0f
        barAlpha.fill(1f)
        markerX = markerTarget()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild()
        markerX = markerTarget()
    }

    private fun dayStart() = DayLineModel.dayStart(now)

    private fun markerTarget() = DayLineModel.fraction(now, dayStart()) * width

    private fun rebuild() {
        bars = DayLineModel.bars(events, dayStart(), now)
        if (barAlpha.size != bars.size) barAlpha = FloatArray(bars.size) { 1f }
        val room = DayLineModel.labelRoom(bars, width.toFloat(), dp(8).toFloat())
        labels = Array(bars.size) { TextUtils.ellipsize(bars[it].event.title, eventPaint, room[it], TextUtils.TruncateAt.END) }
    }

    /** Ordonnée de la ligne de base d'un texte centré dans une ligne de 16 dp qui commence à `top` dp. */
    private fun baseline(paint: Paint, topDp: Int): Float {
        val m = paint.fontMetrics
        return dp(topDp) + (dp(16) - (m.descent - m.ascent)) / 2 - m.ascent
    }

    private fun color(base: Int, alpha: Float): Int = (base and 0x00FFFFFF) or (((base ushr 24) * alpha.coerceIn(0f, 1f)).roundToInt() shl 24)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val trackTop = dp(46).toFloat()
        val thick = dp(2).toFloat()
        fill.color = trackColor
        canvas.drawRoundRect(0f, trackTop, w, trackTop + thick, thick / 2, thick / 2, fill)
        val start = dayStart()
        val past = DayLineModel.fraction(now, start) * w * passScale
        if (past > 0f) {
            fill.color = pastColor
            canvas.drawRoundRect(0f, trackTop, past, trackTop + thick, thick / 2, thick / 2, fill)
        }

        hourPaint.color = pastColor
        val hoursY = baseline(hourPaint, 72)
        for (i in hourLabels.indices) {
            hourPaint.textAlign = when (i) { 0 -> Paint.Align.LEFT; hourLabels.lastIndex -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(hourLabels[i], i * 2 / 12f * w, hoursY, hourPaint)
        }

        val labelY = baseline(eventPaint, 12)
        val barTop = dp(42).toFloat()
        val barH = dp(8).toFloat()
        for ((i, bar) in bars.withIndex()) {
            val alpha = barAlpha[i] * if (stale) STALE_ALPHA else 1f
            val text = when (bar.state) {
                DayLineModel.State.PAST -> pastColor
                DayLineModel.State.NEXT -> nowColor
                DayLineModel.State.LATER -> laterColor
            }
            val barColor = when (bar.state) {
                DayLineModel.State.PAST -> fadedColor
                DayLineModel.State.NEXT -> nowColor
                DayLineModel.State.LATER -> pastColor
            }
            val left = bar.from * w
            val right = bar.to * w - dp(4)
            eventPaint.color = color(text, alpha)
            canvas.drawText(labels[i], 0, labels[i].length, left, labelY, eventPaint)
            if (right > left) {
                fill.color = color(barColor, alpha)
                canvas.drawRoundRect(left, barTop, right, barTop + barH, barH / 2, barH / 2, fill)
            }
        }

        if (!DayLineModel.inWindow(now, start) || markerAlpha <= 0f) return
        val x = markerX
        fill.color = color(nowColor, markerAlpha)
        canvas.drawRoundRect(x - dp(1), dp(22).toFloat(), x + dp(1), dp(70).toFloat(), dp(1).toFloat(), dp(1).toFloat(), fill)
        val cy = dp(47).toFloat()
        fill.color = color(haloColor, markerAlpha)
        canvas.drawCircle(x, cy, dp(9).toFloat(), fill)
        fill.color = color(nowColor, markerAlpha)
        canvas.drawCircle(x, cy, dp(6).toFloat(), fill)
        if (ondeAlpha > 0f) {
            ring.strokeWidth = dp(2) * ondeScale
            ring.color = color(nowColor, ondeAlpha)
            canvas.drawCircle(x, cy, dp(5) * ondeScale, ring)
        }
    }

    private companion object {
        const val STALE_ALPHA = 0.45f
    }
}
