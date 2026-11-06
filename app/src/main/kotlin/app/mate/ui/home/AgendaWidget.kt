package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.net.CalendarEvent
import app.mate.ui.Look
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Agenda : aujourd'hui et les 7 jours suivants, agendas reliés (Outlook, Google, ICS) et agenda personnel (+ pour noter
 * un rendez-vous, toucher un des siens pour le modifier). Toucher une réunion d'un agenda relié la met dans « Prochain
 * rendez-vous » (pour rejoindre une autre réunion), la toucher à nouveau l'enlève. Direction Cadran (#180), une mise en
 * page par famille ([AgendaFace]) : Pastille et Ligne = combien il en reste et la journée en ligne de temps ; Colonne =
 * la ligne debout avec les titres ; Bloc et Grand étroit = la liste par jour ; Grand large et Plein écran = la semaine.
 */
class AgendaWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    /** Liste et semaine : la carte d'origine (titre, source, « Mes agendas », +). */
    private val listCard: View = LayoutInflater.from(context).inflate(R.layout.widget_agenda, FrameLayout(context), false)
    private val list = listCard.findViewById<LinearLayout>(R.id.widget_events)
    private val source = listCard.findViewById<TextView>(R.id.widget_source)
    private val link = listCard.findViewById<TextView>(R.id.widget_agenda_link)

    /** Ligne de temps : combien il en reste, la journée, + en colonne. */
    private val count = label(40f, light = true).apply { isSingleLine = true; fontFeatureSettings = "tnum" }
    private val countUnit = label(15f, R.attr.c_text_dim).apply { isSingleLine = true }
    private val track = DayTrack(context)
    private val trackAdd = ImageButton(context, null, 0, R.style.IconButton_Flat).apply {
        setImageResource(R.drawable.ic_add)
        contentDescription = context.getString(R.string.event_new)
        setOnClickListener { host.editEvent(null) }
    }
    private val trackCard = LinearLayout(context).asCard()
    override val view = FrameLayout(context)

    private var face = AgendaFace.of(placed.size)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val dayHeader = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private val weekDay = SimpleDateFormat("EEE d", Locale.FRANCE)

    /** Aperçu du catalogue : la journée d'exemple à la place de l'agenda de l'ordinateur. */
    private var sample: List<CalendarEvent>? = null
    private val events get() = sample ?: host.events

    init {
        // Agenda relié : « Mes agendas » (voir, délier, en relier un autre) ; sinon droit à la page de l'ordinateur.
        link.setOnClickListener { host.showCalendars() }
        listCard.findViewById<View>(R.id.widget_agenda_add).setOnClickListener { host.editEvent(null) }
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) view.post(::fit)
        }
    }

    override fun applySize() {
        face = AgendaFace.of(placed.size)
        view.removeAllViews()
        if (face.mode == AgendaFace.Mode.LIST || face.mode == AgendaFace.Mode.WEEK) {
            view.addView(listCard, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            buildTrack()
            view.addView(trackCard, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        onCalendar()
        view.post(::fit)
    }

    /** Pastille : le compte au-dessus de la ligne ; Ligne : à gauche ; Colonne : en tête avec +, la ligne debout dessous. */
    private fun buildTrack() {
        for (part in listOf(count, countUnit, track, trackAdd)) (part.parent as? ViewGroup)?.removeView(part)
        trackCard.removeAllViews()
        val pill = family == ShapeFamily.PILL
        val pad = dp(if (pill) PILL_PAD_DP else PAD_DP)
        trackCard.setPadding(pad, pad, pad, pad)
        track.across = face.mode == AgendaFace.Mode.TRACK
        track.titles = face.titles
        val head = LinearLayout(context).apply {
            gravity = Gravity.BOTTOM
            addView(count)
            addView(countUnit, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(UNIT_GAP_DP); bottomMargin = dp(UNIT_LIFT_DP) })
        }
        when (family) {
            ShapeFamily.ROW -> {
                trackCard.orientation = LinearLayout.HORIZONTAL
                trackCard.gravity = Gravity.CENTER_VERTICAL
                head.orientation = LinearLayout.VERTICAL
                head.gravity = Gravity.START
                (countUnit.layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; bottomMargin = 0 }
                trackCard.addView(head, LinearLayout.LayoutParams(WRAP, WRAP))
                trackCard.addView(track, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(GAP_DP) })
            }
            else -> {
                trackCard.orientation = LinearLayout.VERTICAL
                trackCard.gravity = Gravity.NO_GRAVITY
                if (face.mode == AgendaFace.Mode.TRACK_DOWN) {
                    head.gravity = Gravity.CENTER_VERTICAL
                    head.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                    head.addView(trackAdd, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
                }
                trackCard.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
                trackCard.addView(track, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(if (pill) PILL_GAP_DP else GAP_DP) })
            }
        }
    }

    /** Taille du compte selon la hauteur réelle (rangées de hauteur variable). */
    private fun fit() {
        if (face.mode != AgendaFace.Mode.TRACK && face.mode != AgendaFace.Mode.TRACK_DOWN) return
        val h = trackCard.height - trackCard.paddingTop - trackCard.paddingBottom
        if (h <= 0) return
        val part = if (family == ShapeFamily.ROW) ROW_COUNT_PART else PILL_COUNT_PART
        // Ligne fine (92 dp) : le compte et son unité l'un sous l'autre doivent tenir, d'où un plancher plus bas.
        val floor = sp(if (family == ShapeFamily.ROW) ROW_MIN_COUNT_SP else MIN_COUNT_SP)
        val px = if (face.mode == AgendaFace.Mode.TRACK_DOWN) sp(COLUMN_COUNT_SP) else (h * part).coerceIn(floor, sp(MAX_COUNT_SP))
        // Ligne : le compte et son unité l'un sous l'autre tiennent dans la hauteur, quelle que soit la police du thème.
        val room = if (family == ShapeFamily.ROW) (h - countUnit.lineHeight) / COUNT_LINE else px
        count.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(px, room))
    }

    override fun onMinute() = onCalendar()

    override fun onCalendar() {
        val now = System.currentTimeMillis()
        when (face.mode) {
            AgendaFace.Mode.TRACK, AgendaFace.Mode.TRACK_DOWN -> renderTrack(now)
            AgendaFace.Mode.LIST -> renderList(now)
            AgendaFace.Mode.WEEK -> renderWeek(now)
        }
    }

    private fun renderTrack(now: Long) {
        val events = events
        track.show(events, now)
        // Rien de lu parce que l'ordinateur ne répond pas ou que l'agenda n'est pas relié : ne pas dire « 0 restant ».
        val linked = host.features.linked
        if (sample == null && events.isEmpty() && (host.unreachable != null || !linked)) {
            count.put(context.getString(R.string.agenda_unknown))
            countUnit.put(if (linked) "" else context.getString(R.string.agenda_link_now))
            trackCard.setOnClickListener(if (linked) null else View.OnClickListener { host.showCalendars() })
            return
        }
        trackCard.setOnClickListener(null)
        val left = AgendaPlan.remaining(events, now)
        count.put(left.toString())
        countUnit.put(context.getString(if (left <= 1) R.string.agenda_left_one else R.string.agenda_left_many))
    }

    /** Même texte : pas de nouvelle mise en page (une fois par minute sur la tablette). */
    private fun TextView.put(value: String) {
        if (text.toString() != value) text = value
    }

    private fun renderHeader() {
        source.text = if (sample != null) "" else host.calendarNote
        link.setText(if (host.features.linked) R.string.calendars_title else R.string.agenda_link)
    }

    private fun renderList(now: Long) {
        renderHeader()
        val events = events
        list.removeAllViews()
        if (events.isEmpty()) return list.addView(note(R.string.agenda_empty))
        Look.revealOnce(list)
        // Un événement commencé avant aujourd'hui (journée entière sur plusieurs jours) est rangé aujourd'hui.
        val byDay = events.groupBy { daysFrom(now, it.startMs).coerceAtLeast(0) }.toSortedMap()
        if (0 !in byDay) list.addView(note(R.string.agenda_nothing_today))
        for ((day, dayEvents) in byDay) {
            if (day > 0) list.addView(dayTitle(dayHeader.format(Date(dayEvents.first().startMs.coerceAtLeast(now)))))
            for (event in dayEvents) list.addView(row(event, now), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
        }
    }

    /** La semaine : une colonne par jour, aujourd'hui d'abord, ses rendez-vous dessous (on fait défiler s'il y en a trop). */
    private fun renderWeek(now: Long) {
        renderHeader()
        list.removeAllViews()
        if (events.isEmpty()) return list.addView(note(R.string.agenda_empty))
        Look.revealOnce(list)
        val week = AgendaPlan.week(events, now)
        val row = LinearLayout(context)
        week.forEachIndexed { day, dayEvents ->
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val date = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, day) }.timeInMillis
            column.addView(dayTitle(if (day == 0) context.getString(R.string.event_today) else weekDay.format(Date(date))).apply { setPadding(dp(4), 0, 0, dp(4)) })
            for (event in dayEvents) column.addView(cell(event, now), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
            row.addView(column, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (day > 0) marginStart = dp(WEEK_GAP_DP) })
        }
        list.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun dayTitle(text: String) = TextView(context).apply {
        this.text = text.replaceFirstChar { it.uppercase() }
        textSize = 13f
        isAllCaps = true
        letterSpacing = 0.08f
        isSingleLine = true
        setTextColor(color(R.attr.c_text_dim))
        setPadding(dp(4), dp(14), 0, dp(4))
    }

    private fun note(text: Int) = TextView(context).apply {
        setText(text)
        textSize = 16f
        setTextColor(color(R.attr.c_text_dim))
        setPadding(dp(4), dp(12), 0, dp(4))
    }

    /** Toucher : modifier un rendez-vous de l'agenda personnel, ou choisir (puis rendre) une réunion pour Prochain rendez-vous. */
    private fun View.touchable(event: CalendarEvent, past: Boolean, chosen: Boolean) {
        if (sample != null) return
        when {
            event.localId != null -> setOnClickListener { host.editEvent(event) }
            !past -> setOnClickListener {
                host.chosenId = if (chosen) null else event.id
                host.renderCalendar()
            }
        }
    }

    private fun highlight(ongoing: Boolean, chosen: Boolean) = if (!ongoing && !chosen) null else GradientDrawable().apply {
        setColor(color(R.attr.c_accent_soft))
        if (chosen) setStroke(dp(2), color(R.attr.c_accent))
        cornerRadius = dp(14).toFloat()
    }

    /** Semaine : l'heure (accent si en cours) et le titre sur deux lignes au plus. */
    private fun cell(event: CalendarEvent, now: Long): View {
        val past = !event.allDay && event.endMs <= now
        val ongoing = !event.allDay && event.startMs <= now && now < event.endMs
        val chosen = event.id == host.chosenId
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = highlight(ongoing, chosen)
            addView(label(13f, if (ongoing) R.attr.c_accent else R.attr.c_text_faint).apply {
                text = if (event.allDay) context.getString(R.string.all_day) else timeFormat.format(Date(event.startMs))
            })
            addView(label(15f, if (past) R.attr.c_text_faint else R.attr.c_text).apply {
                text = event.title
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            touchable(event, past, chosen)
        }
    }

    /** Liste : l'heure de début et de fin, un trait, le titre et le lieu (ou le rappel). */
    private fun row(event: CalendarEvent, now: Long): View {
        val past = !event.allDay && event.endMs <= now
        val ongoing = !event.allDay && event.startMs <= now && now < event.endMs
        val main = color(
            when {
                ongoing -> R.attr.c_accent
                past -> R.attr.c_text_faint
                else -> R.attr.c_text
            },
        )
        val chosen = event.id == host.chosenId
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = highlight(ongoing, chosen)
            touchable(event, past, chosen)
        }
        val hours = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        hours.addView(TextView(context).apply {
            text = if (event.allDay) context.getString(R.string.all_day) else timeFormat.format(Date(event.startMs))
            textSize = 17f
            setTextColor(main)
        })
        if (!event.allDay) hours.addView(TextView(context).apply {
            text = timeFormat.format(Date(event.endMs))
            textSize = 13f
            setTextColor(color(R.attr.c_text_faint))
        })
        row.addView(hours, LinearLayout.LayoutParams(dp(76), WRAP))
        row.addView(View(context).apply {
            setBackgroundResource(R.drawable.bar)
            if (ongoing) background.mutate().setTint(main)
        }, LinearLayout.LayoutParams(dp(3), MATCH).apply { marginEnd = dp(14) })
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        details.addView(TextView(context).apply {
            text = event.title
            textSize = 17f
            setTextColor(if (past) main else color(R.attr.c_text))
        })
        val reminder = event.remindBefore?.let {
            context.getString(R.string.event_reminder_detail, context.getString(when (it) {
                15 -> R.string.event_reminder_15
                60 -> R.string.event_reminder_60
                else -> R.string.event_reminder_at
            }).lowercase(Locale.FRANCE))
        }
        val sub = listOfNotNull(event.location.takeIf { it.isNotBlank() }, reminder).joinToString(" · ")
        if (sub.isNotEmpty()) details.addView(TextView(context).apply {
            text = sub
            textSize = 13f
            setTextColor(color(R.attr.c_text_dim))
        })
        row.addView(details, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is CalendarSample) return
        this.sample = CalendarSample.events(System.currentTimeMillis(), context::getString)
        onCalendar()
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val PAD_DP = 20
        const val PILL_PAD_DP = 14
        const val GAP_DP = 16
        const val PILL_GAP_DP = 8
        const val UNIT_GAP_DP = 8
        const val UNIT_LIFT_DP = 6
        const val WEEK_GAP_DP = 8
        const val TOUCH_DP = 48

        /** Compte en part de la hauteur utile, borné ; en colonne, une taille fixe. */
        const val PILL_COUNT_PART = 0.4f
        const val ROW_COUNT_PART = 0.5f
        const val MIN_COUNT_SP = 28f
        const val ROW_MIN_COUNT_SP = 22f
        const val MAX_COUNT_SP = 64f
        const val COLUMN_COUNT_SP = 40f

        /** Hauteur d'une ligne du compte, en tailles de texte (marges de police comprises, polices hautes des thèmes). */
        const val COUNT_LINE = 1.4f
    }
}
