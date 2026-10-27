package com.unowhy.companion.ui.home

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTicks
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.NoisePlayer
import com.unowhy.companion.net.CalendarEvent
import com.unowhy.companion.net.HomeFeatures
import com.unowhy.companion.ui.ComputerAudioView
import com.unowhy.companion.ui.FocusDial
import com.unowhy.companion.ui.Look
import com.unowhy.companion.ui.NowPlayingView
import com.unowhy.companion.ui.TasksCard
import com.unowhy.companion.ui.TitleView
import com.unowhy.companion.ui.WaterCard
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.label
import com.unowhy.companion.ui.showForTyping
import com.unowhy.companion.ui.themeColor
import com.unowhy.companion.ui.themeFont
import com.unowhy.companion.ui.themePx
import com.unowhy.companion.ui.themeRes
import kotlinx.coroutines.CoroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Ce que l'Accueil partage avec ses widgets : agenda chargé une fois pour tous, actions de l'écran. */
interface HomeHost {
    val scope: CoroutineScope
    val main: MainActivity?
    val events: List<CalendarEvent>
    val features: HomeFeatures
    /** Erreur ou provenance de l'agenda (« Agenda d'exemple »), vide sinon. */
    val calendarNote: String
    /** Thèmes Cadran : l'ordinateur ne répond plus ; l'Accueil le dit une fois, dans le héros. Null : il répond. */
    val unreachable: Down?
    /** Réunion touchée dans l'agenda, montrée par « Prochain rendez-vous » jusqu'à sa fin. */
    var chosenId: String?
    fun renderCalendar()
    fun reloadCalendar()
    fun editEvent(event: CalendarEvent?)
    fun showCalendars()
    /** Réglages d'un widget changés : la disposition est enregistrée. */
    fun save()
}

/**
 * Un widget de l'Accueil : sa vue remplit ses cases ; [applySize] l'adapte à la taille choisie. [onMinute] : chaque
 * minute (affichage) ; [load] : chaque minute aussi, ce qui passe par le réseau ; [setActive] : page à l'écran ou non.
 */
abstract class HomeWidget(val context: Context, val host: HomeHost, val placed: Placed) {
    abstract val view: View
    open fun applySize() = Unit
    open fun onMinute() = Unit
    open suspend fun load() = Unit
    open fun onCalendar() = Unit
    open fun setActive(active: Boolean) = Unit

    /** L'ordinateur ne répond plus et ce widget en dépend : atténué, sans disparaître (l'Accueil bloque aussi ses touchers). */
    open fun setUnavailable(on: Boolean) {
        view.foreground = if (on) ColorUtils.setAlphaComponent(color(R.attr.c_bg), UNAVAILABLE_VEIL).toDrawable() else null
    }

    open val hasSettings: Boolean get() = false
    open fun showSettings() = Unit
    protected fun color(id: Int) = themeColor(context, id)
    protected fun dp(value: Int) = context.dp(value)

    /** Carte de fond commune (même style que les cartes de l'application). */
    protected fun <T : View> T.asCard(padding: Int? = null): T = apply {
        setBackgroundResource(themeRes(context, R.attr.c_card_bg))
        val px = padding?.let { dp(it) } ?: themePx(context, R.attr.c_card_pad)
        setPadding(px, px, px, px)
    }

    protected fun cardTitle(text: Int, icon: Int) = TitleView(context).apply {
        setText(text)
        setIcon(icon)
    }

    protected fun label(size: Float, colorId: Int = R.attr.c_text, light: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color(colorId))
        if (light) typeface = themeFont(context, R.attr.c_font_light)
    }

    companion object {
        /** Voile de la couleur du fond sur un widget indisponible (0x8C : 55 %). */
        private const val UNAVAILABLE_VEIL = 0x8C

        /** Sans ordinateur, les widgets qui en ont besoin laissent la place à une carte « Connecter mon ordinateur ». */
        fun create(context: Context, host: HomeHost, placed: Placed, noComputer: Boolean): HomeWidget =
            if (noComputer && placed.type.computer) ConnectWidget(context, host, placed)
            else when (placed.type) {
                WidgetType.CLOCK -> ClockWidget(context, host, placed)
                WidgetType.NEXT -> NextWidget(context, host, placed)
                WidgetType.AGENDA -> AgendaWidget(context, host, placed)
                WidgetType.TASKS -> TasksWidget(context, host, placed)
                WidgetType.FOCUS -> FocusWidget(context, host, placed)
                WidgetType.WATER -> WaterWidget(context, host, placed)
                WidgetType.AUDIO -> AudioWidget(context, host, placed)
                WidgetType.MEDIA -> MediaWidget(context, host, placed)
                WidgetType.KEYS -> KeysWidget(context, host, placed)
                WidgetType.WEATHER -> WeatherWidget(context, host, placed)
                WidgetType.NOTE -> NoteWidget(context, host, placed)
                WidgetType.HERO -> HeroWidget(context, host, placed)
                WidgetType.DAYLINE -> DayLineWidget(context, host, placed)
            }
    }
}

/** Heure, salutation et date ; grand chiffre lisible de loin. */
class ClockWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val greeting = label(20f, R.attr.c_text_dim)
    private val clock = TextClock(context).apply {
        format12Hour = "HH:mm"
        format24Hour = "HH:mm"
        includeFontPadding = false
        setTextColor(color(R.attr.c_text))
        typeface = themeFont(context, R.attr.c_font_light)
    }
    private val date = label(22f, R.attr.c_text_dim)
    private val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    override val view = LinearLayout(context).asCard(24)

    override fun applySize() {
        view.removeAllViews()
        side.removeAllViews()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        when (placed.size) {
            // Une ligne : l'heure, et à côté la salutation et la date.
            WidgetSize.M, WidgetSize.W -> {
                val wide = placed.size == WidgetSize.W
                view.orientation = LinearLayout.HORIZONTAL
                view.gravity = Gravity.CENTER_VERTICAL
                clock.textSize = if (wide) 116f else 80f
                greeting.textSize = if (wide) 22f else 17f
                date.textSize = if (wide) 26f else 18f
                view.addView(clock)
                side.addView(greeting)
                side.addView(date)
                view.addView(side, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(if (wide) 32 else 20) })
            }
            else -> {
                val big = placed.size == WidgetSize.XL
                view.orientation = LinearLayout.VERTICAL
                view.gravity = if (big) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
                clock.textSize = if (big) 190f else 120f
                greeting.textSize = if (big) 24f else 20f
                date.textSize = if (big) 30f else 22f
                // Largeur ajustée au texte : sinon chaque ligne prend toute la carte et le centrage ne se voit pas.
                for (line in listOf(greeting, clock, date)) view.addView(line, LinearLayout.LayoutParams(wrap, wrap))
            }
        }
        onMinute()
    }

    override fun onMinute() {
        greeting.setText(when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.greeting_morning
            in 12..17 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        })
        date.text = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date()).replaceFirstChar { it.uppercase() }
    }
}

/**
 * Minuteur Focus (DA Cadran) : cadran de 25 graduations, une par minute, allumées pendant le chrono et éteintes en
 * cascade à la pause. Toucher = mode Focus, bouton = démarrer / pause. Rien ne bouge en dehors des changements.
 */
class FocusWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    /** Conteneur stable : la vue interne est refaite quand la taille change (vertical = autre mise en page). */
    override val view = FrameLayout(context)
    private lateinit var dial: FocusDial
    private lateinit var time: TextView
    private lateinit var phase: TextView
    private lateinit var detail: TextView
    private lateinit var toggle: View
    private lateinit var toggleIcon: ImageView
    private val focusListener: (FocusTimer.State) -> Unit = { render() }
    private val noiseListener: () -> Unit = { render() }
    private var shown = false
    private var tall = false

    init {
        bind()
    }

    override fun applySize() {
        if ((placed.size == WidgetSize.V) == tall) return
        bind()
        render()
    }

    private fun bind() {
        tall = placed.size == WidgetSize.V
        view.removeAllViews()
        val inner = LayoutInflater.from(context).inflate(if (tall) R.layout.widget_focus_tall else R.layout.widget_focus, view, false)
        view.addView(inner)
        dial = inner.findViewById(R.id.widget_focus_dial)
        time = inner.findViewById(R.id.widget_focus_time)
        phase = inner.findViewById(R.id.widget_focus_phase)
        detail = inner.findViewById(R.id.widget_focus_detail)
        toggleIcon = inner.findViewById(R.id.widget_focus_toggle_icon)
        toggle = inner.findViewById(R.id.widget_focus_toggle)
        inner.setOnClickListener { host.main?.showMode("focus") }
        toggle.setOnClickListener { FocusTimer.toggle() }
        Look.pressSpring(inner)
        Look.pressSpring(toggle)
        shown = false
    }

    override fun setActive(active: Boolean) {
        if (active) {
            FocusTimer.observe(focusListener)
            NoisePlayer.observe(noiseListener)
        } else {
            FocusTimer.unobserve(focusListener)
            NoisePlayer.unobserve(noiseListener)
        }
        shown = false
        render()
    }

    override fun onMinute() = render()

    private fun render() {
        val state = FocusTimer.state
        val focus = state.phase == FocusTimer.Phase.FOCUS
        val started = state.running || state.remainingMs < state.durationMs
        time.text = FocusTimer.format(state.remainingMs)
        phase.setText(when {
            !started -> R.string.focus_ready
            !state.running -> R.string.focus_paused
            focus -> R.string.focus_phase_work
            else -> R.string.focus_phase_break
        })
        dial.color = color(if (focus) R.attr.c_accent else R.attr.c_ok)
        // Première image sans cascade : l'écran arrive déjà dans le bon état.
        dial.show(FocusTicks.lit(state.remainingMs, state.durationMs, state.running), animate = shown)
        shown = true
        detail.text = NoisePlayer.kind?.takeIf { NoisePlayer.playing }?.let { context.getString(R.string.noise_playing, context.getString(it.label)) } ?: ""
        // Vertical : l'ambiance est à côté du bouton, qui reste centré tant qu'il n'y en a pas.
        if (tall) detail.visibility = if (detail.text.isEmpty()) View.GONE else View.VISIBLE
        Look.swapIcon(toggleIcon, if (state.running) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = context.getString(if (state.running) R.string.focus_pause else R.string.focus_start)
    }
}

/** Jauge d'eau (sur la tablette, sans l'ordinateur) ; petite : sans le bouton −. */
class WaterWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val card = WaterCard(context)
    override val view = LinearLayout(context).asCard(16).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun applySize() {
        card.compact = placed.size == WidgetSize.S
    }

    override fun onMinute() = card.refresh()
}

/** Son et micro de l'ordinateur. */
class AudioWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val audio = ComputerAudioView(context).apply { attach(host.scope) }
    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(cardTitle(R.string.computer_volume, R.drawable.ic_volume))
        addView(audio, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    override suspend fun load() = audio.refresh()
}

/** Ce qui joue sur l'ordinateur (contrôles multimédias de Windows ; un Mac ne les donne pas). */
class MediaWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val windows = Computers.active(context)?.os == "windows"
    private val playing = NowPlayingView(context).apply { attach(host.scope) }
    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        if (windows) {
            playing.bind(true)
            addView(playing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            addView(cardTitle(R.string.widget_media, R.drawable.ic_play))
            addView(label(16f, R.attr.c_text_dim).apply { setText(R.string.widget_media_windows_only); setPadding(0, dp(8), 0, 0) })
        }
    }

    override fun setActive(active: Boolean) {
        if (windows) playing.setActive(active)
    }
}

/** Tâches du jour, stockées sur l'ordinateur ; le nombre restant va sur la pastille de la barre latérale. */
class TasksWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val tasks = TasksCard(context).apply { attach(host.scope) { pending -> host.main?.setPendingTasks(pending) } }
    override val view: View = tasks.asCard()

    override suspend fun load() = tasks.load()

    override fun setUnavailable(on: Boolean) {
        tasks.quiet = on
        super.setUnavailable(on)
    }
}

/** Note libre, écrite depuis la tablette (« Penser au pain », un code de salle…). */
class NoteWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val text = label(22f).apply { gravity = Gravity.CENTER_VERTICAL }
    override val view = LinearLayout(context).asCard().apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(themeRes(context, R.attr.c_tile_bg))
        setPadding(dp(20), dp(16), dp(20), dp(16))
        addView(cardTitle(R.string.widget_note, R.drawable.ic_edit))
        addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        setOnClickListener { showSettings() }
    }

    override val hasSettings get() = true

    override fun applySize() {
        text.textSize = when (placed.size) { WidgetSize.S -> 17f; WidgetSize.M -> 21f; else -> 26f }
        render()
    }

    private fun render() {
        val note = placed.config.optString("text")
        text.text = note.ifBlank { context.getString(R.string.widget_note_empty) }
        text.setTextColor(color(if (note.isBlank()) R.attr.c_text_faint else R.attr.c_text))
    }

    override fun showSettings() {
        val input = LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.setText(placed.config.optString("text"))
        input.setSelection(input.text.length)
        input.setHint(R.string.widget_note_hint)
        val box = LinearLayout(context).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_note)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                placed.config.put("text", input.text.toString().trim().take(200))
                host.save()
                render()
            }
            .create()
            .showForTyping(input)
    }
}

/** À la place d'un widget qui a besoin de l'ordinateur, tant qu'aucun n'est appairé. */
class ConnectWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard().apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(context).apply {
            setImageResource(placed.type.icon)
            setColorFilter(color(R.attr.c_text_faint))
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(label(16f, R.attr.c_text_dim).apply {
            text = context.getString(R.string.widget_needs_computer, context.getString(placed.type.title))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        if (placed.size.h > 1 || placed.size.w > 1) addView(Button(context, null, 0, R.style.PrimaryButton).apply {
            setText(R.string.connect_button)
            setOnClickListener { host.main?.showWelcome() }
        })
    }
}
