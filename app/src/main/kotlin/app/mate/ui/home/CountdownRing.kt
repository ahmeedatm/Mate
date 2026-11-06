package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import app.mate.R
import app.mate.net.CalendarEvent
import app.mate.ui.Look
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Anneau de compte à rebours (direction Cadran, #180) : un cercle fin, la part [show] allumée en accent depuis le haut,
 * une valeur au centre (« 13 », « 16:00 ») et son unité dessous (« min », « demain »). Tailles prises sur le diamètre ;
 * tout se calcule quand la valeur ou la taille change, le dessin ne fait que tracer.
 */
class CountdownRing(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = themeColor(context, R.attr.c_graphic)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = themeColor(context, R.attr.c_accent)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, R.attr.c_text)
        typeface = themeFont(context, R.attr.c_font_light)
        textAlign = Paint.Align.CENTER
    }.also { Look.halo(context, it) }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, R.attr.c_text_dim)
        typeface = themeFont(context, R.attr.c_font_regular)
        textAlign = Paint.Align.CENTER
    }.also { Look.halo(context, it) }
    private val oval = RectF()
    private var fraction = 0f
    private var value = ""
    private var unit = ""
    private var valueY = 0f
    private var unitY = 0f

    /** Part allumée (0 à 1), valeur et unité ; mêmes : rien à redessiner. */
    fun show(fraction: Float, value: String, unit: String) {
        if (fraction == this.fraction && value == this.value && unit == this.unit) return
        this.fraction = fraction
        this.value = value
        this.unit = unit
        contentDescription = if (unit.isEmpty()) value else "$value $unit"
        plan()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        plan()
    }

    private fun plan() {
        val d = minOf(width, height).toFloat()
        if (d <= 0f) return
        val stroke = (d / STROKE_PART).coerceAtLeast(MIN_STROKE_PX)
        // L'arc est plus épais que la piste : la part allumée se voit aussi sans la couleur (Contraste élevé).
        track.strokeWidth = stroke * TRACK_PART
        arc.strokeWidth = stroke
        val inset = stroke / 2
        val left = (width - d) / 2 + inset
        val top = (height - d) / 2 + inset
        oval.set(left, top, left + d - stroke, top + d - stroke)
        // La valeur tient dans la partie intérieure du cercle ; l'unité reste lisible (jamais sous 14 sp).
        val inner = d * INNER_PART
        valuePaint.textSize = d * VALUE_PART
        val wide = valuePaint.measureText(value)
        if (wide > inner) valuePaint.textSize *= inner / wide
        unitPaint.textSize = (d * UNIT_PART).coerceAtLeast(sp(MIN_UNIT_SP))
        val cy = height / 2f
        val valueH = valuePaint.descent() - valuePaint.ascent()
        val unitH = if (unit.isEmpty()) 0f else unitPaint.descent() - unitPaint.ascent()
        val top0 = cy - (valueH + unitH) / 2
        valueY = top0 - valuePaint.ascent()
        unitY = top0 + valueH - unitPaint.ascent()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawOval(oval, track)
        if (fraction > 0f) canvas.drawArc(oval, START_ANGLE, SWEEP * fraction, false, arc)
        val cx = width / 2f
        canvas.drawText(value, cx, valueY, valuePaint)
        if (unit.isNotEmpty()) canvas.drawText(unit, cx, unitY, unitPaint)
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val START_ANGLE = -90f
        const val SWEEP = 360f

        /** Épaisseur : le diamètre divisé par ce nombre, au moins 3 px. */
        const val STROKE_PART = 22f
        const val MIN_STROKE_PX = 3f
        const val TRACK_PART = 0.4f

        /** Valeur et unité en part du diamètre ; la valeur tient dans [INNER_PART] du diamètre. */
        const val VALUE_PART = 0.32f
        const val UNIT_PART = 0.11f
        const val INNER_PART = 0.66f
        const val MIN_UNIT_SP = 14f
    }
}

/** L'anneau du rendez-vous [event] : la part qui reste ou qui passe, et au centre les minutes, ou l'heure et le jour. */
internal fun CountdownRing.showNext(event: CalendarEvent, now: Long) {
    val center = NextRing.center(event, now)
    val unit = when (center.unit) {
        NextRing.Unit.NONE -> ""
        NextRing.Unit.MIN -> context.getString(R.string.next_ring_min)
        NextRing.Unit.NOW -> context.getString(R.string.next_ring_now)
        NextRing.Unit.TOMORROW -> context.getString(R.string.next_ring_tomorrow)
        NextRing.Unit.DAY -> SimpleDateFormat("EEE", Locale.FRANCE).format(Date(event.startMs))
    }
    show(NextRing.fraction(event, now), center.value, unit)
}
