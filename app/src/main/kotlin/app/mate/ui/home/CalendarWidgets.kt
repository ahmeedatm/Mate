package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.CalendarEvent
import app.mate.ui.CallSession
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.errorText
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import app.mate.ui.themePx
import app.mate.ui.themeRes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val timeFormat get() = SimpleDateFormat("HH:mm", Locale.FRANCE)
private val dayHeader get() = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
private val dayName get() = SimpleDateFormat("EEEE", Locale.FRANCE)
private val shortDay get() = SimpleDateFormat("EEE", Locale.FRANCE)

/** Jours entre aujourd'hui et `ms` (0 = aujourd'hui), au calendrier (pas en tranches de 24 h). */
internal fun daysFrom(now: Long, ms: Long): Int {
    val a = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12) }
    val b = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12) }
    return Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()
}

/** « dans 23 min », « à 17:00 », « En cours, jusqu'à 15:30 », « demain à 9:30 », « mardi à 9:30 ». */
internal fun NextLine.whenText(context: Context): String = when (this) {
    is NextLine.InMinutes -> context.getString(R.string.in_minutes, minutes)
    is NextLine.At -> context.getString(R.string.at_time, timeFormat.format(Date(startMs)))
    is NextLine.Now -> context.getString(R.string.now_until, timeFormat.format(Date(endMs)))
    is NextLine.Tomorrow -> context.getString(R.string.tomorrow_at, timeFormat.format(Date(startMs)))
    is NextLine.Day -> context.getString(
        R.string.day_at, if (days < 7) dayName.format(Date(startMs)) else dayHeader.format(Date(startMs)), timeFormat.format(Date(startMs)),
    )
}

/** « Rejoindre » : l'ordinateur ouvre la réunion, puis le profil Réunion s'affiche jusqu'à la fin de l'événement. */
internal fun joinMeeting(host: HomeHost, event: CalendarEvent, button: View) {
    val context = button.context
    button.isEnabled = false
    host.scope.launch {
        val main = host.main
        try {
            val kind = BackendClient(context).calendar.joinMeeting(event.id)
            main?.showBanner(context.getString(R.string.join_opened, event.title), 6)
            // Profil Réunion : micro, caméra, son de l'appel, jusqu'à la fin de l'événement (ou de l'appel vu par l'ordinateur).
            main?.startCall(CallSession.appOfKind(kind), event.endMs)
        } catch (e: Exception) {
            main?.showBanner(context.errorText(e), 6)
        }
        button.isEnabled = true
    }
}

/**
 * Agenda : aujourd'hui et les 7 jours suivants, agendas reliés (Outlook, Google, ICS) et agenda personnel (+ pour
 * noter un rendez-vous, toucher un des siens pour le modifier). Toucher une réunion d'un agenda relié la met dans
 * « Prochain rendez-vous » (pour rejoindre une autre réunion), la toucher à nouveau l'enlève. Moyen : aujourd'hui
 * seulement, sans la source ni « Mes agendas » ; Vertical (une colonne) : les 7 jours, sans la source ni « Mes agendas ».
 */
class AgendaWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view: View = LayoutInflater.from(context).inflate(R.layout.widget_agenda, null)
    private val list = view.findViewById<LinearLayout>(R.id.widget_events)
    private val source = view.findViewById<TextView>(R.id.widget_source)
    private val link = view.findViewById<TextView>(R.id.widget_agenda_link)

    init {
        // Agenda relié : « Mes agendas » (voir, délier, en relier un autre) ; sinon droit à la page de l'ordinateur.
        link.setOnClickListener { host.showCalendars() }
        view.findViewById<View>(R.id.widget_agenda_add).setOnClickListener { host.editEvent(null) }
    }

    private val today get() = placed.size == WidgetSize.M

    /** Moyen ou une seule colonne : pas de place pour la source et « Mes agendas ». */
    private val compact get() = today || placed.size == WidgetSize.V

    override fun applySize() {
        source.visibility = if (compact) View.GONE else View.VISIBLE
        link.visibility = if (compact) View.GONE else View.VISIBLE
        onCalendar()
    }

    override fun onMinute() = onCalendar()

    override fun onCalendar() {
        source.text = host.calendarNote
        link.setText(if (host.features.linked) R.string.calendars_title else R.string.agenda_link)
        val now = System.currentTimeMillis()
        val events = host.events
        list.removeAllViews()
        if (events.isEmpty()) return list.addView(note(R.string.agenda_empty))
        Look.revealOnce(list)
        // Un événement commencé avant aujourd'hui (journée entière sur plusieurs jours) est rangé aujourd'hui.
        val byDay = events.groupBy { daysFrom(now, it.startMs).coerceAtLeast(0) }.toSortedMap()
            .let { if (today) it.headMap(1) else it }
        if (0 !in byDay) list.addView(note(R.string.agenda_nothing_today))
        for ((day, dayEvents) in byDay) {
            if (day > 0) list.addView(TextView(context).apply {
                text = dayHeader.format(Date(dayEvents.first().startMs.coerceAtLeast(now))).replaceFirstChar { it.uppercase() }
                textSize = 13f
                isAllCaps = true
                letterSpacing = 0.08f
                setTextColor(color(R.attr.c_text_dim))
                setPadding(dp(4), dp(14), 0, dp(4))
            })
            for (event in dayEvents) list.addView(row(event, now), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) })
        }
    }

    private fun note(text: Int) = TextView(context).apply {
        setText(text)
        textSize = 16f
        setTextColor(color(R.attr.c_text_dim))
        setPadding(dp(4), dp(12), 0, dp(4))
    }

    private fun row(event: CalendarEvent, now: Long): View {
        val past = !event.allDay && event.endMs <= now
        val ongoing = !event.allDay && event.startMs <= now && now < event.endMs
        val main = color(when {
            ongoing -> R.attr.c_accent
            past -> R.attr.c_text_faint
            else -> R.attr.c_text
        })
        val chosen = event.id == host.chosenId
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (ongoing || chosen) background = GradientDrawable().apply {
                setColor(color(R.attr.c_accent_soft))
                if (chosen) setStroke(dp(2), color(R.attr.c_accent))
                cornerRadius = dp(14).toFloat()
            }
            when {
                // Agenda personnel : toucher = modifier.
                event.localId != null -> setOnClickListener { host.editEvent(event) }
                !past -> setOnClickListener {
                    host.chosenId = if (chosen) null else event.id
                    host.renderCalendar()
                }
            }
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
        row.addView(hours, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))

        row.addView(View(context).apply {
            setBackgroundResource(R.drawable.bar)
            if (ongoing) background.mutate().setTint(main)
        }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(14) })

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
        row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }
}
