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
 * Accueil, tableau de bord du bureau : heure, prochain rendez-vous, Focus, son du Mac, agenda du jour
 * et tâches. Agenda rafraîchi toutes les 5 min, le reste chaque minute (et dès que l'écran réapparaît).
 * Prochain rendez-vous et agenda n'apparaissent qu'avec une appli d'agenda dans le deck, « Rejoindre »
 * qu'avec Teams ; sans réponse du Mac, tout reste affiché. Toucher une ligne de l'agenda la met à la
 * place du prochain rendez-vous (pour rejoindre une autre réunion), la toucher à nouveau l'enlève.
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

    private var features = HomeFeatures()
    private var events: List<CalendarEvent> = emptyList()
    private var joinable: CalendarEvent? = null
    /** Réunion touchée dans l'agenda, affichée à la place du prochain rendez-vous jusqu'à sa fin. */
    private var chosenId: String? = null
    private var loop: Job? = null
    private var narrow: Boolean? = null

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

        val scope = viewLifecycleOwner.lifecycleScope
        audio.attach(scope)
        tasks.attach(scope) { pending -> (activity as? MainActivity)?.setPendingTasks(pending) }
        view.findViewById<View>(R.id.home_focus).setOnClickListener { (activity as? MainActivity)?.showMode("focus") }
        focusToggle.setOnClickListener { FocusTimer.toggle() }
        join.setOnClickListener { joinNext() }

        // Le panneau document peut réduire la largeur de moitié : on empile alors les colonnes.
        view.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            if (r - l > 0) v.post { applyWidth(r - l) }
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
                if (tick % 5 == 0) fetch()
                tasks.load()
                audio.refresh()
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
        if (!features.agenda) return
        try {
            val (src, items) = BackendClient(requireContext()).calendar(1)
            events = items
            source.text = if (src == "mock") getString(R.string.calendar_mock) else ""
        } catch (e: Exception) {
            source.text = getString(R.string.error_prefix, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun applyWidth(widthPx: Int) {
        val isNarrow = widthPx < dp(880)
        if (isNarrow == narrow) return
        narrow = isNarrow
        root.orientation = if (isNarrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        left.layoutParams = if (isNarrow) LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(16) }
        else LinearLayout.LayoutParams(0, match, 5f).apply { marginEnd = dp(20) }
        right.layoutParams = if (isNarrow) LinearLayout.LayoutParams(match, wrap) else LinearLayout.LayoutParams(0, match, 6f)
        for (card in listOf(agenda, tasks)) {
            val params = card.layoutParams as LinearLayout.LayoutParams
            params.height = if (isNarrow) dp(400) else 0
            params.weight = if (isNarrow) 0f else 1f
            card.layoutParams = params
        }
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
            nextTitle.setText(if (events.isEmpty()) R.string.no_events else R.string.no_more_events)
            nextWhen.setText(R.string.free_time)
            return
        }
        nextTitle.text = upcoming.title
        val whenText = when {
            upcoming.startMs <= now -> getString(R.string.now_until, timeFormat.format(Date(upcoming.endMs)))
            upcoming.startMs - now < 60 * 60_000 -> getString(R.string.in_minutes, ((upcoming.startMs - now) / 60_000).toInt())
            else -> getString(R.string.at_time, timeFormat.format(Date(upcoming.startMs)))
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
                main?.showBanner(getString(R.string.error_prefix, e.message ?: e.javaClass.simpleName), 6)
            }
            join.isEnabled = true
        }
    }

    private fun renderAgenda(now: Long) {
        list.removeAllViews()
        val ctx = requireContext()
        if (events.isEmpty()) {
            list.addView(TextView(ctx).apply {
                setText(R.string.no_events)
                textSize = 16f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
                setPadding(dp(4), dp(12), 0, 0)
            })
            return
        }
        for (event in events) {
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
                if (!past) setOnClickListener {
                    chosenId = if (chosen) null else event.id
                    render()
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
            if (event.location.isNotBlank()) details.addView(TextView(ctx).apply {
                text = event.location
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            })
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
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
    }
}
