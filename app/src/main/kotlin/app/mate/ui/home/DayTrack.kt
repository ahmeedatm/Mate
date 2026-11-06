package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import app.mate.R
import app.mate.net.CalendarEvent
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La journée de 8 h à 20 h en ligne de temps (direction Cadran, #180) : les rendez-vous en barres (passés effacés, le
 * prochain en accent), un point pour maintenant, les heures aux bouts. [across] : couchée ; sinon debout, les heures à
 * gauche. [titles] : le titre de chaque rendez-vous, coupé à la place qu'il a. Tout se calcule quand les rendez-vous, la
 * minute ou la taille changent ; le dessin ne fait que tracer.
 */
class DayTrack(context: Context) : View(context) {
    var across = true
        set(value) { if (field != value) { field = value; replan() } }
    var titles = false
        set(value) { if (field != value) { field = value; replan() } }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_stroke) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_text) }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_bg) }
    private val hourPaint = text(R.attr.c_text_faint, R.attr.c_font_regular, HOUR_SP)
    private val titlePaint = text(R.attr.c_text, R.attr.c_font_regular, TITLE_SP)
    private val pastText = themeColor(context, R.attr.c_text_faint)
    private val laterText = themeColor(context, R.attr.c_text)
    private val colors = mapOf(
        DayLineModel.State.PAST to themeColor(context, R.attr.c_stroke),
        DayLineModel.State.NEXT to themeColor(context, R.attr.c_accent),
        DayLineModel.State.LATER to themeColor(context, R.attr.c_graphic),
    )
    private val clock = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val thick = context.dp(BAR_DP).toFloat()
    private val dot = context.dp(DOT_DP).toFloat()

    private var events: List<CalendarEvent> = emptyList()
    private var now = 0L

    /** Ce que [onDraw] trace, calculé par [plan]. */
    private val track = RectF()
    private var rects = emptyArray<RectF>()
    private var barColors = IntArray(0)
    private var labels = emptyArray<String>()
    private var labelX = FloatArray(0)
    private var labelY = FloatArray(0)
    private var labelColors = IntArray(0)
    private var hours = emptyArray<String>()
    private var hourX = FloatArray(0)
    private var hourY = FloatArray(0)
    private var nowX = -1f
    private var nowY = -1f

    /** Rendez-vous et minute ; mêmes rendez-vous et même minute : rien à refaire. */
    fun show(events: List<CalendarEvent>, now: Long) {
        val minute = now / MINUTE
        if (events == this.events && minute == this.now / MINUTE) return
        this.events = events
        this.now = now
        replan()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        plan()
    }

    private fun replan() {
        plan()
        invalidate()
    }

    private fun plan() {
        rects = emptyArray()
        if (width == 0 || height == 0) return
        val start = DayLineModel.dayStart(now)
        val bars = DayLineModel.bars(events, start, now)
        if (across) planAcross(bars) else planDown(bars)
        val f = DayLineModel.fraction(now, start)
        val inWindow = DayLineModel.inWindow(now, start)
        nowX = if (!inWindow) -1f else if (across) track.left + f * track.width() else track.centerX()
        nowY = if (!inWindow) -1f else if (across) track.centerY() else track.top + f * track.height()
        contentDescription = bars.joinToString(", ") { "${clock.format(Date(it.event.startMs))} ${it.event.title}" }
    }

    /** Couchée : la ligne en haut (ou au milieu sans titres), les titres sous les barres, « 8 h » et « 20 h » aux bouts en bas. */
    private fun planAcross(bars: List<DayLineModel.Bar>) {
        val hourH = hourPaint.descent() - hourPaint.ascent()
        val titleH = titlePaint.descent() - titlePaint.ascent()
        val y = if (titles) dot + thick / 2 else (height - hourH) / 2f
        track.set(dot, y - thick / 2, width - dot, y + thick / 2)
        rects = Array(bars.size) { RectF(track.left + bars[it].from * track.width(), track.top, track.left + bars[it].to * track.width(), track.bottom) }
        barColors = IntArray(bars.size) { colors.getValue(bars[it].state) }
        val room = DayLineModel.labelRoom(bars, track.width(), context.dp(LABEL_GAP_DP).toFloat())
        val shown = if (titles && height >= y + thick + titleH + hourH) bars.indices.filter { room[it] >= titleH * 2 } else emptyList()
        labels = Array(shown.size) { ellipsize(bars[shown[it]].event.title, room[shown[it]]) }
        labelX = FloatArray(shown.size) { rects[shown[it]].left }
        labelY = FloatArray(shown.size) { track.bottom + context.dp(LABEL_GAP_DP) - titlePaint.ascent() }
        labelColors = IntArray(shown.size) { if (bars[shown[it]].state == DayLineModel.State.PAST) pastText else laterText }
        hourPaint.textAlign = Paint.Align.LEFT
        hours = arrayOf(hourText(DayLineModel.FROM_HOUR), hourText(DayLineModel.TO_HOUR))
        val base = height - hourPaint.descent()
        hourX = floatArrayOf(track.left, track.right - hourPaint.measureText(hours[1]))
        hourY = floatArrayOf(base, base)
    }

    /** Debout : les heures à gauche toutes les 2 h (si elles tiennent), la ligne, puis l'heure et le titre de chaque rendez-vous. */
    private fun planDown(bars: List<DayLineModel.Bar>) {
        val hourH = hourPaint.descent() - hourPaint.ascent()
        val titleH = titlePaint.descent() - titlePaint.ascent()
        val hourW = hourPaint.measureText(hourText(DayLineModel.TO_HOUR)) + context.dp(LABEL_GAP_DP)
        val x = hourW + dot
        track.set(x - thick / 2, hourH / 2, x + thick / 2, height - hourH / 2)
        rects = Array(bars.size) { RectF(track.left, track.top + bars[it].from * track.height(), track.right, track.top + bars[it].to * track.height()) }
        barColors = IntArray(bars.size) { colors.getValue(bars[it].state) }
        val span = DayLineModel.TO_HOUR - DayLineModel.FROM_HOUR
        val every = if (track.height() / span * 2 >= hourH * 1.5f) 2 else span
        val marks = (DayLineModel.FROM_HOUR..DayLineModel.TO_HOUR step every).toList()
        hourPaint.textAlign = Paint.Align.LEFT
        hours = Array(marks.size) { hourText(marks[it]) }
        hourX = FloatArray(marks.size) { 0f }
        hourY = FloatArray(marks.size) { track.top + (marks[it] - DayLineModel.FROM_HOUR).toFloat() / span * track.height() - (hourPaint.ascent() + hourPaint.descent()) / 2 }
        val textLeft = track.right + dot + context.dp(LABEL_GAP_DP)
        val room = DayLineModel.labelRoom(bars, track.height(), 0f)
        val shown = if (titles) bars.indices.filter { room[it] >= titleH } else emptyList()
        labels = Array(shown.size) {
            val e = bars[shown[it]].event
            ellipsize("${clock.format(Date(e.startMs))}  ${e.title}", width - textLeft)
        }
        labelX = FloatArray(shown.size) { textLeft }
        labelY = FloatArray(shown.size) { rects[shown[it]].top - titlePaint.ascent() }
        labelColors = IntArray(shown.size) { if (bars[shown[it]].state == DayLineModel.State.PAST) pastText else laterText }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val r = thick / 2
        canvas.drawRoundRect(track, r, r, trackPaint)
        for (i in rects.indices) {
            barPaint.color = barColors[i]
            canvas.drawRoundRect(rects[i], r, r, barPaint)
        }
        for (i in hours.indices) canvas.drawText(hours[i], hourX[i], hourY[i], hourPaint)
        for (i in labels.indices) {
            titlePaint.color = labelColors[i]
            canvas.drawText(labels[i], labelX[i], labelY[i], titlePaint)
        }
        if (nowX >= 0f) {
            canvas.drawCircle(nowX, nowY, dot + context.dp(HALO_DP), haloPaint)
            canvas.drawCircle(nowX, nowY, dot, nowPaint)
        }
    }

    private fun ellipsize(text: String, room: Float) = TextUtils.ellipsize(text, titlePaint, room.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()

    private fun hourText(hour: Int) = context.getString(R.string.dayline_hour, hour)

    private fun text(colorAttr: Int, fontAttr: Int, sp: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, colorAttr)
        typeface = themeFont(context, fontAttr)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val BAR_DP = 8
        const val DOT_DP = 6
        const val HALO_DP = 3
        const val LABEL_GAP_DP = 6
        const val HOUR_SP = 13f
        const val TITLE_SP = 15f
    }
}
