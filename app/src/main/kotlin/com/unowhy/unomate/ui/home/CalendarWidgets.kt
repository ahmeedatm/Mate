package com.unowhy.unomate.ui.home

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
import com.unowhy.unomate.R
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.CalendarEvent
import com.unowhy.unomate.ui.CallSession
import com.unowhy.unomate.ui.Look
import com.unowhy.unomate.ui.TitleView
import com.unowhy.unomate.ui.errorText
import com.unowhy.unomate.ui.themeBool
import com.unowhy.unomate.ui.themeColor
import com.unowhy.unomate.ui.themeFont
import com.unowhy.unomate.ui.themePx
import com.unowhy.unomate.ui.themeRes
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
 * Prochain rendez-vous (ou la réunion touchée dans l'agenda), avec « Rejoindre » pour une réunion en ligne (Teams,
 * Meet, Zoom, Webex) à partir de 15 min avant. Moyen : le bouton à droite du titre ; petit et grand : en dessous ;
 * vertical : l'heure en grand, puis les deux rendez-vous suivants.
 */
class NextWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val cadran = themeBool(context, R.attr.c_cadran)
    private val labelView = TitleView(context).apply {
        setIcon(R.drawable.ic_event)
        setTextColor(color(R.attr.c_hue_orange))
    }
    private val title = label(22f).apply {
        ellipsize = TextUtils.TruncateAt.END
        typeface = themeFont(context, R.attr.c_font_medium)
    }
    private val whenText = label(17f, if (cadran) R.attr.c_accent else R.attr.c_hue_orange).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
    private val join = Button(context, null, 0, R.style.NowButton).apply {
        setText(R.string.join_meeting)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_videocam, 0, 0, 0)
        visibility = View.GONE
        setOnClickListener { joinNext() }
    }
    /** Thèmes Cadran, rien de prévu : l'action visible pour noter un rendez-vous (prend la place de « Rejoindre »). */
    private val add = Button(context, null, 0, R.style.ActionButton).apply {
        setText(R.string.event_add)
        visibility = View.GONE
        // Sans agenda relié, le plus utile au bureau : relier Outlook ou Google (page ouverte sur l'ordinateur).
        setOnClickListener { if (host.features.linked) host.editEvent(null) else host.showCalendars() }
    }
    private val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /** Vertical : nombre de rendez-vous du jour (en tête), heure en grand, les deux suivants (en bas). */
    private val todayCount = label(15f, R.attr.c_text_faint)
    private val bigTime = label(44f, light = true).apply { isSingleLine = true; fontFeatureSettings = "tnum" }
    private val after = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val spacer = View(context)
    private val divider = View(context).apply { setBackgroundColor(color(R.attr.c_stroke)) }
    override val view = LinearLayout(context).apply {
        if (cadran) {
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitFollowing() }
            setBackgroundResource(themeRes(context, R.attr.c_home_card_bg))
            themePx(context, R.attr.c_card_pad).let { setPadding(it, it, it, it) }
        } else {
            // Fond teinté de la couleur de l'agenda (orange), comme la pastille du titre.
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(ColorUtils.compositeColors(ColorUtils.setAlphaComponent(color(R.attr.c_hue_orange), 0x26), color(R.attr.c_surface)))
                setStroke(dp(1), ColorUtils.setAlphaComponent(color(R.attr.c_hue_orange), 0x55))
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
    }
    private var joinable: CalendarEvent? = null

    override fun applySize() {
        view.removeAllViews()
        texts.removeAllViews()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        labelView.maxLines = if (placed.size == WidgetSize.V) 1 else Int.MAX_VALUE
        labelView.ellipsize = TextUtils.TruncateAt.END
        if (placed.size == WidgetSize.V) {
            view.orientation = LinearLayout.VERTICAL
            view.gravity = Gravity.TOP
            title.maxLines = 2
            title.textSize = 18f
            whenText.textSize = 14f
            whenText.setTextColor(color(R.attr.c_text_dim))
            view.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(labelView, LinearLayout.LayoutParams(0, wrap, 1f))
                addView(todayCount)
            })
            view.addView(bigTime, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })
            view.addView(title, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
            view.addView(whenText, LinearLayout.LayoutParams(match, wrap))
            view.addView(join, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(18) })
            view.addView(add, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(18) })
            view.addView(spacer, LinearLayout.LayoutParams(match, 0, 1f))
            view.addView(divider, LinearLayout.LayoutParams(match, dp(1)))
            view.addView(after, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) })
        } else {
            labelView.visibility = View.VISIBLE
            whenText.textSize = 17f
            whenText.setTextColor(color(if (cadran) R.attr.c_accent else R.attr.c_hue_orange))
            texts.addView(labelView)
            texts.addView(title, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
            texts.addView(whenText, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(2) })
            if (placed.size == WidgetSize.M) {
                view.orientation = LinearLayout.HORIZONTAL
                view.gravity = Gravity.CENTER_VERTICAL
                title.maxLines = 1
                title.textSize = 22f
                view.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
                view.addView(join, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(12) })
                view.addView(add, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(12) })
            } else {
                val small = placed.size == WidgetSize.S
                view.orientation = LinearLayout.VERTICAL
                view.gravity = Gravity.CENTER_VERTICAL
                title.maxLines = if (small) 2 else 3
                title.textSize = if (small) 18f else 28f
                if (small) whenText.textSize = 14f
                val gap = dp(if (small) 8 else 14)
                view.addView(texts, LinearLayout.LayoutParams(match, wrap))
                view.addView(join, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = gap })
                view.addView(add, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = gap })
            }
        }
        onCalendar()
    }

    override fun onMinute() = onCalendar()

    override fun onCalendar() {
        val now = System.currentTimeMillis()
        val events = host.events
        val upcoming = nextEvent(events, now, host.chosenId)
        val chosen = upcoming?.takeIf { it.id == host.chosenId }
        // Vertical : titre « Agenda » sur une ligne, le compteur du jour à droite.
        labelView.setText(if (chosen != null) R.string.chosen_label else if (placed.size == WidgetSize.V) R.string.today else R.string.next_label)
        joinable = upcoming?.takeIf { DayBrief.canJoin(it, now, chosen != null, host.features.meetings) }
        join.visibility = if (joinable == null) View.GONE else View.VISIBLE
        if (placed.size == WidgetSize.V) renderVertical(events, upcoming, now)
        // Cadran, rien de prévu : l'action pour noter un rendez-vous. Ordinateur injoignable et rien de lu : on ne dit pas « rien de prévu ».
        val empty = cadran && upcoming == null && host.unreachable == null
        add.visibility = if (empty) View.VISIBLE else View.GONE
        whenText.visibility = if (cadran && upcoming == null) View.GONE else View.VISIBLE
        if (upcoming == null) {
            if (cadran) {
                add.setText(if (host.features.linked) R.string.event_add else R.string.agenda_link_now)
                title.text = if (!empty) "" else context.getString(if (host.features.linked) R.string.next_none_today else R.string.next_not_linked)
            } else {
                title.setText(R.string.next_none)
                whenText.setText(R.string.next_none_hint)
            }
            return
        }
        title.text = upcoming.title
        val text = nextLine(upcoming, now).whenText(context)
        whenText.text = if (upcoming.location.isBlank()) text else "$text · ${upcoming.location}"
    }

    /** Vertical : compteur du jour, heure du prochain en grand, deux rendez-vous suivants. */
    private fun renderVertical(events: List<CalendarEvent>, upcoming: CalendarEvent?, now: Long) {
        val today = events.count { !it.allDay && daysFrom(now, it.startMs) == 0 }
        todayCount.text = if (today > 0) context.getString(R.string.next_today, today) else ""
        bigTime.text = upcoming?.let { timeFormat.format(Date(it.startMs)) } ?: ""
        after.removeAllViews()
        if (upcoming != null) Look.revealOnce(after)
        val following = events.filter { !it.allDay && it.endMs > now && it !== upcoming }.take(if (cadran) FOLLOWING_CADRAN else 2)
        // Cadran : sans suivant, ni filet ni bloc ; avec, autant de lignes que la place en laisse (fitFollowing).
        val show = if (cadran && following.isEmpty()) View.GONE else View.VISIBLE
        divider.visibility = show
        after.visibility = show
        for (event in following) after.addView(LinearLayout(context).apply {
            val sameDay = daysFrom(now, event.startMs) == 0
            addView(label(15f, R.attr.c_text_dim).apply {
                text = if (sameDay) timeFormat.format(Date(event.startMs)) else shortDay.format(Date(event.startMs)).replaceFirstChar { it.uppercase() }
                minWidth = dp(56)
            })
            addView(label(15f).apply {
                text = if (sameDay) event.title else "${event.title} ${timeFormat.format(Date(event.startMs))}"
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (after.isNotEmpty()) topMargin = dp(10) })
    }

    /**
     * Cadran, vertical : cache les rendez-vous suivants qui ne tiennent pas entre le contenu du haut et le bas de la
     * tuile (le filet disparaît s'il n'en tient aucun). La place ne dépend pas des lignes cachées : la décision est stable.
     */
    private fun fitFollowing() {
        if (placed.size != WidgetSize.V || after.isEmpty() || view.height == 0 || after.width == 0) return
        var free = view.height - view.paddingBottom - spacer.top - dp(1) - dp(14)
        var fitting = 0
        for (i in 0 until after.childCount) {
            val row = after.getChildAt(i)
            row.measure(View.MeasureSpec.makeMeasureSpec(after.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            val need = row.measuredHeight + if (i > 0) dp(10) else 0
            if (need > free) break
            free -= need
            fitting++
        }
        for (i in 0 until after.childCount) after.getChildAt(i).visibility = if (i < fitting) View.VISIBLE else View.GONE
        val show = if (fitting > 0) View.VISIBLE else View.GONE
        divider.visibility = show
        after.visibility = show
    }

    private fun joinNext() {
        joinable?.let { joinMeeting(host, it, join) }
    }

    private companion object {
        const val FOLLOWING_CADRAN = 4
    }
}

/**
 * Agenda : aujourd'hui et les 7 jours suivants, agendas reliés (Outlook, Google, ICS) et agenda personnel (+ pour
 * noter un rendez-vous, toucher un des siens pour le modifier). Toucher une réunion d'un agenda relié la met dans
 * « Prochain rendez-vous » (pour rejoindre une autre réunion), la toucher à nouveau l'enlève. Moyen : aujourd'hui
 * seulement, sans la source ni « Mes agendas ».
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

    override fun applySize() {
        source.visibility = if (today) View.GONE else View.VISIBLE
        link.visibility = if (today) View.GONE else View.VISIBLE
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
