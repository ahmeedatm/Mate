package com.unowhy.companion.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.NoisePlayer
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.CalendarEvent
import com.unowhy.companion.net.HomeFeatures
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Accueil, tableau de bord du bureau : heure, prochain rendez-vous, Focus, son de l'ordinateur, agenda et tâches.
 * Agenda : aujourd'hui et les 7 jours suivants, agendas reliés (Outlook, Google, ICS) et agenda personnel (+ pour
 * noter un rendez-vous, toucher un des siens pour le modifier). Rafraîchi toutes les 5 min, le reste chaque minute.
 * « Rejoindre » seulement avec Teams. Toucher une réunion d'un agenda relié la met à la place du prochain
 * rendez-vous (pour rejoindre une autre réunion), la toucher à nouveau l'enlève.
 */
class HomeFragment : Fragment() {

    private val dayFormat = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

    private lateinit var root: LinearLayout
    private lateinit var left: View
    private lateinit var right: View
    private lateinit var agenda: View
    private lateinit var next: View
    private lateinit var nextLabel: TextView
    private lateinit var greeting: TextView
    private lateinit var date: TextView
    private lateinit var nextTitle: TextView
    private lateinit var nextWhen: TextView
    private lateinit var join: Button
    private lateinit var list: LinearLayout
    private lateinit var source: TextView
    private lateinit var focusRing: ProgressRing
    private lateinit var focusTime: TextView
    private lateinit var focusDetail: TextView
    private lateinit var focusToggle: ImageButton
    private lateinit var audio: MacAudioView
    private lateinit var tasks: TasksCard
    private lateinit var water: WaterCard
    private lateinit var connect: View
    private lateinit var agendaLink: View
    /** « Relier Outlook / Google » touché : l'agenda relié sur l'ordinateur apparaît dans la minute. */
    private var waitingLink = false
    private val dayHeader = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private val dayName = SimpleDateFormat("EEEE", Locale.FRANCE)
    /** Aucun ordinateur appairé : heure et Focus seulement, plus la carte « Connecter mon ordinateur ». */
    private var noComputer = false

    private var features = HomeFeatures()
    private var events: List<CalendarEvent> = emptyList()
    private var joinable: CalendarEvent? = null
    /** Réunion touchée dans l'agenda, affichée à la place du prochain rendez-vous jusqu'à sa fin. */
    private var chosenId: String? = null
    private var loop: Job? = null
    private var narrow: Boolean? = null
    private var height = 0

    private val focusListener: (FocusTimer.State) -> Unit = { renderFocus() }
    private val noiseListener: () -> Unit = { renderFocus() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        root = view.findViewById(R.id.home_root)
        left = view.findViewById(R.id.home_left)
        right = view.findViewById(R.id.home_right)
        agenda = view.findViewById(R.id.home_agenda)
        next = view.findViewById(R.id.home_next)
        nextLabel = view.findViewById(R.id.home_next_label)
        greeting = view.findViewById(R.id.home_greeting)
        date = view.findViewById(R.id.home_date)
        nextTitle = view.findViewById(R.id.home_next_title)
        nextWhen = view.findViewById(R.id.home_next_when)
        join = view.findViewById(R.id.home_join)
        list = view.findViewById(R.id.home_events)
        source = view.findViewById(R.id.home_source)
        focusRing = view.findViewById(R.id.home_focus_ring)
        focusTime = view.findViewById(R.id.home_focus_time)
        focusDetail = view.findViewById(R.id.home_focus_detail)
        focusToggle = view.findViewById(R.id.home_focus_toggle)
        audio = view.findViewById(R.id.home_audio)
        tasks = view.findViewById(R.id.home_tasks)
        water = view.findViewById(R.id.home_water)
        connect = view.findViewById(R.id.home_connect)
        agendaLink = view.findViewById(R.id.home_agenda_link)
        agendaLink.setOnClickListener { openAgendaSetup() }
        view.findViewById<View>(R.id.home_agenda_add).setOnClickListener { editEvent(null) }

        // Écran recréé quand l'ordinateur change (MainActivity.onComputerChanged) : lu une fois ici.
        noComputer = Computers.all(requireContext()).isEmpty()
        if (noComputer) {
            for (card in listOf(agenda, next, tasks, view.findViewById(R.id.home_audio_card))) card.visibility = View.GONE
            connect.visibility = View.VISIBLE
            connect.findViewById<TextView>(R.id.connect_text).setText(R.string.connect_home)
            connect.findViewById<View>(R.id.connect_button).setOnClickListener { (activity as? MainActivity)?.showWelcome() }
        }

        val scope = viewLifecycleOwner.lifecycleScope
        audio.attach(scope)
        tasks.attach(scope) { pending -> (activity as? MainActivity)?.setPendingTasks(pending) }
        view.findViewById<View>(R.id.home_focus).setOnClickListener { (activity as? MainActivity)?.showMode("focus") }
        focusToggle.setOnClickListener { FocusTimer.toggle() }
        join.setOnClickListener { joinNext() }

        // Le panneau document peut réduire la largeur de moitié : on empile alors les colonnes.
        view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            if (r - l > 0) v.post { applySize(r - l, b - t) }
        }
    }

    override fun onStart() {
        super.onStart()
        FocusTimer.observe(focusListener)
        NoisePlayer.observe(noiseListener)
        renderFocus()
        if (!isHidden) startLoop()
    }

    override fun onStop() {
        FocusTimer.unobserve(focusListener)
        NoisePlayer.unobserve(noiseListener)
        loop?.cancel()
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (hidden) loop?.cancel() else startLoop()
    }

    /** Une passe par minute (statut "en cours" / "dans X min", tâches, son), l'agenda toutes les 5. */
    private fun startLoop() {
        loop?.cancel()
        loop = viewLifecycleOwner.lifecycleScope.launch {
            var tick = 0
            while (true) {
                if (!noComputer) {
                    if (tick % 5 == 0 || waitingLink) fetch()
                    tasks.load()
                    audio.refresh()
                }
                render()
                tick++
                delay(60_000)
            }
        }
    }

    private suspend fun fetch() {
        // Les applis du deck ont pu changer : relu à chaque retour sur l'Accueil.
        runCatching { features = BackendClient(requireContext()).homeFeatures() }
        next.visibility = if (features.agenda) View.VISIBLE else View.GONE
        agenda.visibility = next.visibility
        if (features.linked) waitingLink = false
        agendaLink.visibility = if (features.linked) View.GONE else View.VISIBLE
        if (!features.agenda) return
        try {
            val days = BackendClient(requireContext()).calendar(AGENDA_DAYS)
            events = days.events
            source.text = days.error ?: if (days.source == "mock") getString(R.string.calendar_mock) else ""
        } catch (e: Exception) {
            source.text = requireContext().errorText(e)
        }
    }

    /** Nouvel événement (`event` null) ou modification d'un événement de l'agenda personnel. */
    private fun editEvent(event: CalendarEvent?) {
        val ctx = requireContext()
        val localId = event?.localId
        val save = { draft: EventDraft ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    BackendClient(ctx).saveEvent(localId, draft.title, draft.startMs, draft.allDay, draft.remindBefore)
                    (activity as? MainActivity)?.showBanner(getString(R.string.event_saved, draft.title), 4)
                    fetch()
                    render()
                } catch (e: Exception) {
                    (activity as? MainActivity)?.showBanner(ctx.errorText(e), 6)
                }
            }
            Unit
        }
        val delete = localId?.let {
            {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        BackendClient(ctx).deleteEvent(it)
                        fetch()
                        render()
                    } catch (e: Exception) {
                        (activity as? MainActivity)?.showBanner(ctx.errorText(e), 6)
                    }
                }
                Unit
            }
        }
        EventDialog(ctx, save, delete).show(event)
    }

    /** Ouvre sur l'ordinateur la page du compagnon, à la section Agenda (lien ICS à coller). */
    private fun openAgendaSetup() {
        val main = activity as? MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendClient(requireContext()).openLocalPage("agenda")
                waitingLink = true
                main?.showBanner(getString(R.string.agenda_setup_opened), 8)
            } catch (e: Exception) {
                main?.showBanner(requireContext().errorText(e), 6)
            }
        }
    }

    /**
     * Large : deux colonnes, cartes de droite à la hauteur exacte de l'écran (un agenda chargé défile dans sa carte ;
     * le ScrollView ignorerait une hauteur fixée sur la page, qui s'étirerait et pousserait la colonne de gauche hors de
     * l'écran). Étroit (panneau document ouvert) : colonnes empilées, la page défile.
     */
    private fun applySize(widthPx: Int, heightPx: Int) {
        val isNarrow = widthPx < dp(880)
        if (isNarrow == narrow && heightPx == height) return
        height = heightPx
        // Hauteur utile : écran moins les marges de la page (24 dp en haut et en bas) ; deux cartes séparées de 16 dp.
        val usable = heightPx - dp(48)
        for ((card, wide) in listOf(agenda to (usable - dp(16)) / 2, tasks to (usable - dp(16)) / 2, connect to usable)) {
            val params = card.layoutParams as LinearLayout.LayoutParams
            params.height = if (isNarrow) dp(400) else wide
            params.weight = 0f
            card.layoutParams = params
        }
        if (isNarrow == narrow) return
        narrow = isNarrow
        root.orientation = if (isNarrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        left.layoutParams = if (isNarrow) LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(16) }
        else LinearLayout.LayoutParams(0, match, 5f).apply { marginEnd = dp(20) }
        right.layoutParams = if (isNarrow) LinearLayout.LayoutParams(match, wrap) else LinearLayout.LayoutParams(0, match, 6f)
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greeting.setText(when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..17 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        })
        date.text = dayFormat.format(Date(now)).replaceFirstChar { it.uppercase() }
        water.refresh()
        renderNext(now)
        renderAgenda(now)
    }

    private fun renderNext(now: Long) {
        val chosen = events.firstOrNull { it.id == chosenId && it.endMs > now }
        if (chosen == null) chosenId = null
        nextLabel.setText(if (chosen == null) R.string.next_label else R.string.chosen_label)
        val upcoming = chosen ?: events.firstOrNull { !it.allDay && it.endMs > now }
        // « Rejoindre » à partir de 15 min avant : plus tôt, un toucher par erreur ouvrirait Teams pour rien.
        // Une réunion choisie dans l'agenda peut être rejointe tout de suite (le choix est volontaire).
        joinable = upcoming?.takeIf {
            features.meetings && it.joinable && (chosen != null || it.startMs - now <= JOIN_EARLY_MS)
        }
        join.visibility = if (joinable == null) View.GONE else View.VISIBLE
        if (upcoming == null) {
            nextTitle.setText(R.string.next_none)
            nextWhen.setText(R.string.next_none_hint)
            return
        }
        nextTitle.text = upcoming.title
        val time = timeFormat.format(Date(upcoming.startMs))
        val whenText = when (val days = daysFrom(now, upcoming.startMs)) {
            0 -> when {
                upcoming.startMs <= now -> getString(R.string.now_until, timeFormat.format(Date(upcoming.endMs)))
                upcoming.startMs - now < 60 * 60_000 -> getString(R.string.in_minutes, ((upcoming.startMs - now) / 60_000).toInt())
                else -> getString(R.string.at_time, time)
            }
            1 -> getString(R.string.tomorrow_at, time)
            else -> getString(R.string.day_at, if (days < 7) dayName.format(Date(upcoming.startMs)) else dayHeader.format(Date(upcoming.startMs)), time)
        }
        nextWhen.text = if (upcoming.location.isBlank()) whenText else "$whenText · ${upcoming.location}"
    }

    private fun joinNext() {
        val event = joinable ?: return
        join.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val main = activity as? MainActivity
            try {
                BackendClient(requireContext()).joinMeeting(event.id)
                main?.showBanner(getString(R.string.join_opened, event.title), 6)
                main?.showMeetingControls()
            } catch (e: Exception) {
                main?.showBanner(requireContext().errorText(e), 6)
            }
            join.isEnabled = true
        }
    }

    /** Jours entre aujourd'hui et `ms` (0 = aujourd'hui), au calendrier (pas en tranches de 24 h). */
    private fun daysFrom(now: Long, ms: Long): Int {
        val a = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12) }
        val b = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12) }
        return Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()
    }

    /** Aujourd'hui, puis chaque jour suivant qui a des événements, sous son titre (« mardi 27 octobre »). */
    private fun renderAgenda(now: Long) {
        list.removeAllViews()
        val ctx = requireContext()
        if (events.isEmpty()) return list.addView(agendaNote(R.string.agenda_empty))
        // Un événement commencé avant aujourd'hui (journée entière sur plusieurs jours) est rangé aujourd'hui.
        val byDay = events.groupBy { daysFrom(now, it.startMs).coerceAtLeast(0) }.toSortedMap()
        if (0 !in byDay) list.addView(agendaNote(R.string.agenda_nothing_today))
        for ((day, dayEvents) in byDay) {
            if (day > 0) list.addView(TextView(ctx).apply {
                text = dayHeader.format(Date(dayEvents.first().startMs.coerceAtLeast(now))).replaceFirstChar { it.uppercase() }
                textSize = 13f
                isAllCaps = true
                letterSpacing = 0.08f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
                setPadding(dp(4), dp(14), 0, dp(4))
            })
            for (event in dayEvents) list.addView(eventRow(event, now), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) })
        }
    }

    private fun agendaNote(text: Int) = TextView(requireContext()).apply {
        setText(text)
        textSize = 16f
        setTextColor(ContextCompat.getColor(context, R.color.text_dim))
        setPadding(dp(4), dp(12), 0, dp(4))
    }

    private fun eventRow(event: CalendarEvent, now: Long): View {
        val ctx = requireContext()
        val past = !event.allDay && event.endMs <= now
        val ongoing = !event.allDay && event.startMs <= now && now < event.endMs
        val main = ContextCompat.getColor(ctx, when {
            ongoing -> R.color.accent
            past -> R.color.text_faint
            else -> R.color.text
        })
        val chosen = event.id == chosenId
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (ongoing || chosen) background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(ctx, R.color.accent_soft))
                if (chosen) setStroke(dp(2), ContextCompat.getColor(ctx, R.color.accent))
                cornerRadius = dp(14).toFloat()
            }
            when {
                // Agenda personnel : toucher = modifier.
                event.localId != null -> setOnClickListener { editEvent(event) }
                !past -> setOnClickListener {
                    chosenId = if (chosen) null else event.id
                    render()
                }
            }
        }

        val hours = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        hours.addView(TextView(ctx).apply {
            text = if (event.allDay) getString(R.string.all_day) else timeFormat.format(Date(event.startMs))
            textSize = 17f
            setTextColor(main)
        })
        if (!event.allDay) hours.addView(TextView(ctx).apply {
            text = timeFormat.format(Date(event.endMs))
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
        })
        row.addView(hours, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))

        row.addView(View(ctx).apply {
            setBackgroundResource(R.drawable.bar)
            if (ongoing) background.mutate().setTint(main)
        }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(14) })

        val details = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        details.addView(TextView(ctx).apply {
            text = event.title
            textSize = 17f
            setTextColor(if (past) main else ContextCompat.getColor(ctx, R.color.text))
        })
        val reminder = event.remindBefore?.let {
            getString(R.string.event_reminder_detail, getString(when (it) {
                0 -> R.string.event_reminder_at
                15 -> R.string.event_reminder_15
                60 -> R.string.event_reminder_60
                else -> R.string.event_reminder_at
            }).lowercase(Locale.FRANCE))
        }
        val sub = listOfNotNull(event.location.takeIf { it.isNotBlank() }, reminder).joinToString(" · ")
        if (sub.isNotEmpty()) details.addView(TextView(ctx).apply {
            text = sub
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
        })
        row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun renderFocus() {
        val state = FocusTimer.state
        val focus = state.phase == FocusTimer.Phase.FOCUS
        val active = state.running || state.remainingMs < state.durationMs
        val ctx = requireContext()
        focusTime.text = FocusTimer.format(state.remainingMs)
        focusRing.progress = (state.durationMs - state.remainingMs).toFloat() / state.durationMs.coerceAtLeast(1)
        focusRing.color = ContextCompat.getColor(ctx, if (focus) R.color.accent else R.color.ok)
        val phase = if (active) getString(if (focus) R.string.focus_phase_work else R.string.focus_phase_break) else getString(R.string.focus_ready)
        val noise = NoisePlayer.kind
        focusDetail.text = if (noise == null) phase else "$phase · " + getString(R.string.noise_playing, getString(noise.label))
        focusToggle.setImageResource(if (state.running) R.drawable.ic_pause else R.drawable.ic_play)
        focusToggle.contentDescription = getString(if (state.running) R.string.focus_pause else R.string.focus_start)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val JOIN_EARLY_MS = 15 * 60_000L
        /** Aujourd'hui et les 7 jours suivants : « mardi soir » se voit dès aujourd'hui. */
        const val AGENDA_DAYS = 8
    }
}
