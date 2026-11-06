package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.mate.AnimFamily
import app.mate.R
import app.mate.net.CalendarEvent
import app.mate.ui.Look
import app.mate.ui.themeFont
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ligne du jour de l'Accueil (DA Console douce), sans fond. Une mise en page par famille ([DayLineFace], direction Cadran,
 * #180) : la Ligne garde [DayLineView], qui avance d'un battement chaque minute ; ailleurs, ce qui se passe maintenant
 * ([DayNow] : le rendez-vous en cours, ou libre jusqu'au prochain) au-dessus de la journée en ligne de temps ([DayTrack]).
 */
class DayLineWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val line = DayLineView(context)
    private val track = DayTrack(context)
    private val nowTitle = label(TITLE_SP).apply {
        typeface = themeFont(context, R.attr.c_font_medium)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val nowUntil = label(UNTIL_SP, R.attr.c_text_dim).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    override val view = FrameLayout(context)
    private var face = DayLineFace.of(placed.size)
    private var active = false
    private val clock = SimpleDateFormat("HH:mm", Locale.FRANCE)

    /** Aperçu du catalogue : la journée d'exemple à la place de l'agenda de l'ordinateur. */
    private var sample: List<CalendarEvent>? = null
    private val events get() = sample ?: host.events

    override fun applySize() {
        face = DayLineFace.of(placed.size)
        view.removeAllViews()
        for (part in listOf(line, track, nowTitle, nowUntil, stack)) (part.parent as? ViewGroup)?.removeView(part)
        if (face.band) {
            view.addView(line, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val pill = family == ShapeFamily.PILL
            nowTitle.textSize = when (family) {
                ShapeFamily.PILL -> PILL_TITLE_SP
                ShapeFamily.FULL, ShapeFamily.LARGE -> BIG_TITLE_SP
                else -> TITLE_SP
            }
            stack.gravity = if (pill) Gravity.CENTER_VERTICAL else Gravity.TOP
            stack.addView(nowTitle, LinearLayout.LayoutParams(MATCH, WRAP))
            stack.addView(nowUntil, LinearLayout.LayoutParams(MATCH, WRAP))
            track.across = face.line != DayLineFace.Line.DOWN
            track.titles = face.line != DayLineFace.Line.MINI
            val trackLp = when (face.line) {
                DayLineFace.Line.MINI -> LinearLayout.LayoutParams(MATCH, dp(MINI_DP))
                DayLineFace.Line.ACROSS -> LinearLayout.LayoutParams(MATCH, dp(ACROSS_DP))
                DayLineFace.Line.DOWN -> LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            stack.addView(track, trackLp.apply { topMargin = dp(if (pill) PILL_GAP_DP else GAP_DP) })
            view.addView(stack, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        render(animate = false)
    }

    override fun onCalendar() = render(animate = false)

    /** Le battement : le point avance sur son ressort, sauf page cachée (rien ne se voit, rien ne bouge). */
    override fun onMinute() = render(animate = active)

    override fun setActive(active: Boolean) {
        this.active = active
        if (active) render(animate = false)
    }

    private fun render(animate: Boolean) {
        val now = System.currentTimeMillis()
        if (face.band) return line.show(events, now, animate)
        track.show(events, now)
        when (val state = DayNow.of(events, now)) {
            is DayNow.Busy -> {
                nowTitle.text = state.title
                nowUntil.text = context.getString(R.string.dayline_until, clock.format(Date(state.untilMs)))
                nowUntil.setTextColor(color(R.attr.c_accent))
            }
            is DayNow.Free -> {
                nowTitle.setText(R.string.dayline_free)
                nowUntil.text = state.untilMs?.let { context.getString(R.string.dayline_until, clock.format(Date(it))) }
                    ?: context.getString(R.string.dayline_free_rest)
                nowUntil.setTextColor(color(R.attr.c_text_dim))
            }
        }
    }

    /** Bandeau : pas de voile, la ligne atténue elle-même ses rendez-vous ; ailleurs, le voile commun. */
    override fun setUnavailable(on: Boolean) {
        line.stale = on
        if (!face.band) super.setUnavailable(on)
    }

    /** Arrivée de l'Accueil : la ligne se dessine, sauf si l'arrivée des widgets est coupée (Réglages > Animations). */
    fun arrive() {
        if (face.band && Look.on(line.context, AnimFamily.HOME)) line.arrive()
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is CalendarSample) return
        this.sample = CalendarSample.events(System.currentTimeMillis(), context::getString)
        render(animate = false)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TITLE_SP = 24f
        const val PILL_TITLE_SP = 20f
        const val BIG_TITLE_SP = 32f
        const val UNTIL_SP = 16f
        const val MINI_DP = 28
        const val ACROSS_DP = 64
        const val GAP_DP = 16
        const val PILL_GAP_DP = 8
    }
}
