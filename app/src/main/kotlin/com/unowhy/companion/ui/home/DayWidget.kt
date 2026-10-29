package com.unowhy.companion.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.CalendarEvent
import com.unowhy.companion.net.runCatchingCancellable
import com.unowhy.companion.ui.errorText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Ma journée (Accueil de bureau, à la place de la grande heure : l'heure est déjà en haut à droite) : salutation et date,
 * la prochaine réunion du jour en grand, ou « Aucune / Plus de réunion aujourd'hui » et la suivante ; puis les actions
 * du jour : Focus (le chrono, [DayBrief.focusPill]), Ne pas déranger de l'ordinateur (lu à chaque [load], caché s'il est
 * illisible), Rejoindre ([DayBrief.canJoin]). Météo et eau à droite ([HeroComplications]).
 */
class DayWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view: View = LayoutInflater.from(context).inflate(R.layout.widget_day, FrameLayout(context), false)
    private val greeting = view.findViewById<TextView>(R.id.day_greeting)
    private val date = view.findViewById<TextView>(R.id.day_date)
    private val meeting = view.findViewById<View>(R.id.day_meeting)
    private val title = view.findViewById<TextView>(R.id.day_title)
    private val whenText = view.findViewById<TextView>(R.id.day_when)
    private val join = view.findViewById<Button>(R.id.day_join)
    private val focusButton = view.findViewById<Button>(R.id.day_focus)
    private val dndButton = view.findViewById<Button>(R.id.day_dnd)
    private val note = view.findViewById<TextView>(R.id.day_note)
    private val compl = HeroComplications(context, host, placed, view)
    private val dateFormat = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private var joinable: CalendarEvent? = null
    private var pill: DayBrief.FocusPill? = null
    private var dnd: Boolean? = null

    // Le chrono prévient chaque seconde : la pastille ne change qu'avec les minutes ou l'état.
    private val focusListener: (FocusTimer.State) -> Unit = { state -> DayBrief.focusPill(state).takeIf { it != pill }?.let(::renderFocusPill) }

    override val hasSettings get() = true

    init {
        join.setOnClickListener { joinable?.let { joinMeeting(host, it, join) } }
        focusButton.setOnClickListener { FocusTimer.toggle() }
        dndButton.setOnClickListener { toggleDnd() }
        renderFocusPill(DayBrief.focusPill(FocusTimer.state))
        render(animate = false)
    }

    override fun setActive(active: Boolean) {
        compl.setActive(active)
        if (active) FocusTimer.observe(focusListener) else FocusTimer.unobserve(focusListener)
        if (active) render(animate = false)
    }

    override fun onMinute() = render(animate = true)

    override fun onCalendar() = renderFocus()

    private fun render(animate: Boolean) {
        val now = System.currentTimeMillis()
        greeting.setText(
            when (DayBrief.greeting(Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY))) {
                DayBrief.Greeting.MORNING -> R.string.day_morning
                DayBrief.Greeting.AFTERNOON -> R.string.day_afternoon
                DayBrief.Greeting.EVENING -> R.string.day_evening
            },
        )
        date.text = dateFormat.format(Date(now)).replaceFirstChar { it.uppercase() }
        renderFocus()
        compl.render(animate)
    }

    private fun renderFocus() {
        val now = System.currentTimeMillis()
        // Ordinateur injoignable : seul message, les rendez-vous peuvent dater.
        renderDnd()
        host.unreachable?.let { down ->
            val since = down.since()
            showNote(if (since != null) context.getString(R.string.hero_down, down.name, since) else context.getString(R.string.hero_down_unknown, down.name), R.attr.c_warn)
            return
        }
        when (val focus = DayBrief.focus(host.events, now, host.chosenId)) {
            is DayBrief.Focus.Meeting -> {
                meeting.visibility = View.VISIBLE
                note.visibility = View.GONE
                title.text = focus.event.title
                whenText.text = focus.line.whenText(context)
                whenText.setTextColor(color(if (focus.line is NextLine.At) R.attr.c_text_dim else R.attr.c_accent))
                joinable = focus.event.takeIf { DayBrief.canJoin(it, now, it.id == host.chosenId, host.features.meetings) }
                join.visibility = if (joinable != null) View.VISIBLE else View.GONE
            }
            is DayBrief.Focus.Free -> {
                // Pas d'agenda relié ni de rendez-vous noté : la carte Agenda le dit déjà, rien à affirmer ici.
                if (host.events.isEmpty() && !host.features.linked) return showNote(null, R.attr.c_text_dim)
                val free = context.getString(if (focus.hadMeetings) R.string.day_free_after else R.string.day_free)
                val later = focus.later?.let { context.getString(R.string.day_later, it.whenText(context), it.title) }
                showNote(if (later != null) "$free · $later" else free, R.attr.c_text_dim)
            }
        }
    }

    /** Ligne du dessous en texte seul ; null : rien. */
    private fun showNote(text: String?, colorAttr: Int) {
        meeting.visibility = View.GONE
        joinable = null
        note.visibility = if (text != null) View.VISIBLE else View.GONE
        note.text = text
        note.setTextColor(color(colorAttr))
    }

    private fun renderFocusPill(p: DayBrief.FocusPill) {
        pill = p
        focusButton.text = context.getString(
            when (p.kind) {
                DayBrief.FocusPill.Kind.IDLE -> R.string.day_focus
                DayBrief.FocusPill.Kind.RUNNING -> R.string.day_focus_running
                DayBrief.FocusPill.Kind.PAUSED -> R.string.day_focus_paused
                DayBrief.FocusPill.Kind.BREAK -> R.string.day_focus_break
            },
            p.minutes,
        )
        val running = FocusTimer.state.running
        focusButton.setCompoundDrawablesRelativeWithIntrinsicBounds(if (running) R.drawable.ic_pause else R.drawable.ic_play, 0, 0, 0)
        focusButton.isSelected = p.kind == DayBrief.FocusPill.Kind.RUNNING
    }

    /** Ne pas déranger : seulement lisible et ordinateur joignable ; rouge quand il est actif. */
    private fun renderDnd() {
        val on = dnd
        dndButton.visibility = if (on != null && host.unreachable == null) View.VISIBLE else View.GONE
        dndButton.isSelected = on == true
    }

    private fun toggleDnd() {
        val wanted = dnd != true
        dnd = wanted
        renderDnd()
        host.scope.launch {
            try {
                BackendClient(context).system.setDnd(wanted)
            } catch (e: Exception) {
                dnd = !wanted
                renderDnd()
                host.main?.showBanner(context.errorText(e), 6)
            }
        }
    }

    override suspend fun load() {
        dnd = runCatchingCancellable { BackendClient(context).system.dnd() }.getOrNull()
        renderDnd()
        compl.load()
    }

    override fun showSettings() = compl.showSettings()
}
