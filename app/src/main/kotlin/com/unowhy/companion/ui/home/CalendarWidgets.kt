package com.unowhy.companion.ui.home

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
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.CalendarEvent
import com.unowhy.companion.ui.TitleView
import com.unowhy.companion.ui.errorText
import com.unowhy.companion.ui.themeColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val timeFormat get() = SimpleDateFormat("HH:mm", Locale.FRANCE)
private val dayHeader get() = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
private val dayName get() = SimpleDateFormat("EEEE", Locale.FRANCE)

/** Jours entre aujourd'hui et `ms` (0 = aujourd'hui), au calendrier (pas en tranches de 24 h). */
internal fun daysFrom(now: Long, ms: Long): Int {
    val a = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12) }
    val b = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12) }
    return Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()
}

/**
 * Prochain rendez-vous (ou la réunion touchée dans l'agenda), avec « Rejoindre » pour une réunion en ligne (Teams,
 * Meet, Zoom, Webex) à partir de
 * 15 min avant. Moyen : le bouton à droite du titre ; grand : en dessous.
 */
class NextWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val labelView = TitleView(context).apply {
        setIcon(R.drawable.ic_event)
        setTextColor(color(R.attr.c_hue_orange))
    }
    private val title = label(22f).apply {
        ellipsize = TextUtils.TruncateAt.END
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val whenText = label(17f, R.attr.c_hue_orange).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
    private val join = Button(context, null, 0, R.style.PrimaryButton).apply {
        setText(R.string.join_meeting)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_videocam, 0, 0, 0)
        visibility = View.GONE
        setOnClickListener { joinNext() }
    }
    private val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(labelView)
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(whenText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
    }
    override val view = LinearLayout(context).apply {
        // Fond teinté de la couleur de l'agenda (orange), comme la pastille du titre.
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(ColorUtils.compositeColors(ColorUtils.setAlphaComponent(color(R.attr.c_hue_orange), 0x26), color(R.attr.c_surface)))
            setStroke(dp(1), ColorUtils.setAlphaComponent(color(R.attr.c_hue_orange), 0x55))
        }
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    private var joinable: CalendarEvent? = null

    override fun applySize() {
        view.removeAllViews()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        if (placed.size == WidgetSize.M) {
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER_VERTICAL
            title.maxLines = 1
            title.textSize = 22f
            view.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
            view.addView(join, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(12) })
        } else {
            view.orientation = LinearLayout.VERTICAL
            view.gravity = Gravity.CENTER_VERTICAL
            title.maxLines = 3
            title.textSize = 28f
            view.addView(texts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
            view.addView(join, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(14) })
        }
        onCalendar()
    }

    override fun onMinute() = onCalendar()

    override fun onCalendar() {
        val now = System.currentTimeMillis()
        val events = host.events
        val chosen = events.firstOrNull { it.id == host.chosenId && it.endMs > now }
        labelView.setText(if (chosen == null) R.string.next_label else R.string.chosen_label)
        val upcoming = chosen ?: events.firstOrNull { !it.allDay && it.endMs > now }
        // « Rejoindre » à partir de 15 min avant : plus tôt, un toucher par erreur ouvrirait Teams pour rien.
        // Une réunion choisie dans l'agenda peut être rejointe tout de suite (le choix est volontaire).
        joinable = upcoming?.takeIf { host.features.meetings && it.joinable && (chosen != null || it.startMs - now <= JOIN_EARLY_MS) }
        join.visibility = if (joinable == null) View.GONE else View.VISIBLE
        if (upcoming == null) {
            title.setText(R.string.next_none)
            whenText.setText(R.string.next_none_hint)
            return
        }
        title.text = upcoming.title
        val time = timeFormat.format(Date(upcoming.startMs))
        val text = when (val days = daysFrom(now, upcoming.startMs)) {
            0 -> when {
                upcoming.startMs <= now -> context.getString(R.string.now_until, timeFormat.format(Date(upcoming.endMs)))
                upcoming.startMs - now < 60 * 60_000 -> context.getString(R.string.in_minutes, ((upcoming.startMs - now) / 60_000).toInt())
                else -> context.getString(R.string.at_time, time)
            }
            1 -> context.getString(R.string.tomorrow_at, time)
            else -> context.getString(R.string.day_at, if (days < 7) dayName.format(Date(upcoming.startMs)) else dayHeader.format(Date(upcoming.startMs)), time)
        }
        whenText.text = if (upcoming.location.isBlank()) text else "$text · ${upcoming.location}"
    }

    private fun joinNext() {
        val event = joinable ?: return
        join.isEnabled = false
        host.scope.launch {
            val main = host.main
            try {
                val kind = BackendClient(context).calendar.joinMeeting(event.id)
                main?.showBanner(context.getString(R.string.join_opened, event.title), 6)
                // Page Réunion du deck : raccourcis de Teams, inutiles pour Meet ou Zoom.
                if (kind == "teams" && host.features.teams) main?.showMeetingControls()
            } catch (e: Exception) {
                main?.showBanner(context.errorText(e), 6)
            }
            join.isEnabled = true
        }
    }

    private companion object {
        const val JOIN_EARLY_MS = 15 * 60_000L
    }
}

/**
 * Agenda : aujourd'hui et les 7 jours suivants, agendas reliés (Outlook, Google, ICS) et agenda personnel (+ pour
 * noter un rendez-vous, toucher un des siens pour le modifier). Toucher une réunion d'un agenda relié la met dans
 * « Prochain rendez-vous » (pour rejoindre une autre réunion), la toucher à nouveau l'enlève.
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

    override fun onMinute() = onCalendar()

    override fun onCalendar() {
        source.text = host.calendarNote
        link.setText(if (host.features.linked) R.string.calendars_title else R.string.agenda_link)
        val now = System.currentTimeMillis()
        val events = host.events
        list.removeAllViews()
        if (events.isEmpty()) return list.addView(note(R.string.agenda_empty))
        // Un événement commencé avant aujourd'hui (journée entière sur plusieurs jours) est rangé aujourd'hui.
        val byDay = events.groupBy { daysFrom(now, it.startMs).coerceAtLeast(0) }.toSortedMap()
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
