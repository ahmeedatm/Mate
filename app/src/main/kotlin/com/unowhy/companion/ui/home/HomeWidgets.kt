package com.unowhy.companion.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
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
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.LifecycleOwner
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.focus.FocusTicks
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.NoisePlayer
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.CalendarEvent
import com.unowhy.companion.net.DeckPage
import com.unowhy.companion.net.DeckTile
import com.unowhy.companion.net.HomeFeatures
import com.unowhy.companion.net.runCatchingCancellable
import com.unowhy.companion.ui.ComputerAudioView
import com.unowhy.companion.ui.FocusDial
import com.unowhy.companion.ui.IconCache
import com.unowhy.companion.ui.Look
import com.unowhy.companion.ui.NowPlayingView
import com.unowhy.companion.ui.TasksCard
import com.unowhy.companion.ui.TitleView
import com.unowhy.companion.ui.WaterCard
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.errorText
import com.unowhy.companion.ui.label
import com.unowhy.companion.ui.showForTyping
import com.unowhy.companion.ui.showImmersive
import com.unowhy.companion.ui.themeBool
import com.unowhy.companion.ui.themeColor
import com.unowhy.companion.ui.themeFont
import com.unowhy.companion.ui.themePx
import com.unowhy.companion.ui.themeRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    /** Pages du deck, lues une fois pour tous les widgets ; [force] les relit. */
    suspend fun deckPages(force: Boolean = false): List<DeckPage>
    /** Réglages d'un widget changés : la disposition est enregistrée. */
    fun save()
    /** Panneaux plein écran du Deck (touche, applis, sites, fenêtres), créés à la demande. */
    val overlays: DeckOverlays
    /** Le cycle de vie de l'écran (viewLifecycleOwner) : [com.unowhy.companion.ui.AgentsView.attach] en a besoin. */
    val owner: LifecycleOwner
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

    /** Carte de fond commune (même style que les cartes de l'application, translucide en Rétro). */
    protected fun <T : View> T.asCard(padding: Int? = null): T = apply {
        setBackgroundResource(themeRes(context, R.attr.c_home_card_bg))
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
                WidgetType.PERF -> PerfWidget(context, host, placed)
                WidgetType.KEYS -> KeysWidget(context, host, placed)
                WidgetType.WEATHER -> WeatherWidget(context, host, placed)
                WidgetType.NOTE -> NoteWidget(context, host, placed)
                WidgetType.HERO -> HeroWidget(context, host, placed)
                WidgetType.DAYLINE -> DayLineWidget(context, host, placed)
                WidgetType.AGENTS -> AgentsWidget(context, host, placed)
                WidgetType.MIXER -> MixerWidget(context, host, placed)
                WidgetType.OPEN -> OpenWindowsWidget(context, host, placed)
                WidgetType.APPS -> AppsWidget(context, host, placed)
                WidgetType.DISCORD -> DiscordWidget(context, host, placed)
                WidgetType.MEETING -> MeetingWidget(context, host, placed)
                WidgetType.PAD -> PadWidget(context, host, placed)
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
        isSingleLine = true
        setTextColor(color(R.attr.c_text))
        typeface = themeFont(context, R.attr.c_font_light)
    }
    private val date = label(22f, R.attr.c_text_dim).apply { isSingleLine = true }
    private val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    override val view = LinearLayout(context).asCard(24)

    override fun applySize() {
        view.removeAllViews()
        side.removeAllViews()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val small = placed.size == WidgetSize.S
        view.setPadding(dp(if (small) 12 else 24), dp(if (small) 12 else 24), dp(if (small) 12 else 24), dp(if (small) 12 else 24))
        when (placed.size) {
            // Une case : l'heure et la date dessous, centrées.
            WidgetSize.S -> {
                view.orientation = LinearLayout.VERTICAL
                view.gravity = Gravity.CENTER
                clock.textSize = 48f
                date.textSize = 15f
                for (line in listOf(clock, date)) view.addView(line, LinearLayout.LayoutParams(wrap, wrap))
            }
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
        // Une case : date courte (« Mer. 28 oct. »), qui tient sur une ligne.
        val pattern = if (placed.size == WidgetSize.S) "EEE d MMM" else "EEEE d MMMM"
        date.text = SimpleDateFormat(pattern, Locale.FRANCE).format(Date()).replaceFirstChar { it.uppercase() }
    }
}

/**
 * Minuteur Focus (DA Cadran) : cadran de 25 graduations, une par minute, allumées pendant le chrono et éteintes en
 * cascade à la pause. Toucher = mode Focus, bouton = démarrer / pause. Grand : cadran plus grand et ambiances à
 * choisir dessous. Rien ne bouge en dehors des changements.
 */
class FocusWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    /** Conteneur stable : la vue interne est refaite quand la taille change (vertical, grand = autre mise en page). */
    override val view = FrameLayout(context)
    private lateinit var dial: FocusDial
    private lateinit var time: TextView
    private lateinit var phase: TextView
    private lateinit var detail: TextView
    private lateinit var toggle: View
    private lateinit var toggleIcon: ImageView
    private val noiseChips = LinkedHashMap<NoisePlayer.Kind, Button>()
    private val focusListener: (FocusTimer.State) -> Unit = { render() }
    private val noiseListener: () -> Unit = { render() }
    private var shown = false
    private var tall = false
    private var big = false
    private var small = false
    private val cadran = themeBool(context, R.attr.c_cadran)

    init {
        bind()
    }

    override fun applySize() {
        if ((placed.size == WidgetSize.V) == tall && (placed.size == WidgetSize.L) == big && (placed.size == WidgetSize.S) == small) return
        bind()
        render()
    }

    private fun bind() {
        tall = placed.size == WidgetSize.V
        big = placed.size == WidgetSize.L
        small = placed.size == WidgetSize.S
        view.removeAllViews()
        noiseChips.clear()
        val inner = LayoutInflater.from(context).inflate(if (tall) R.layout.widget_focus_tall else R.layout.widget_focus, view, false)
        if (big) view.addView(withNoises(inner)) else view.addView(inner)
        dial = inner.findViewById(R.id.widget_focus_dial)
        time = inner.findViewById(R.id.widget_focus_time)
        phase = inner.findViewById(R.id.widget_focus_phase)
        detail = inner.findViewById(R.id.widget_focus_detail)
        toggleIcon = inner.findViewById(R.id.widget_focus_toggle_icon)
        toggle = inner.findViewById(R.id.widget_focus_toggle)
        if (small) shrink(inner)
        if (cadran) {
            capToDial(time, if (tall || big) 40f else if (small) 26f else 30f)
            capToDial(phase, if (tall || big) 13f else if (small) 11f else 12f)
        }
        inner.setOnClickListener { host.main?.showMode("focus") }
        toggle.setOnClickListener { FocusTimer.toggle() }
        Look.pressSpring(inner)
        Look.pressSpring(toggle)
        shown = false
    }

    /** Une case : cadran réduit et bouton seulement (le titre « Focus » et l'ambiance ne tiennent pas à côté). */
    private fun shrink(inner: View) {
        inner.setPadding(dp(14), dp(14), dp(14), dp(14))
        inner.findViewById<View>(R.id.widget_focus_ring_box).layoutParams.apply { width = dp(104); height = dp(104) }
        (detail.parent as View).visibility = View.GONE
        ((toggle.parent as View).layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
    }

    /** Grand : la tuile passe autour du cadran agrandi et d'une rangée d'ambiances (toucher celle qui joue l'arrête). */
    private fun withNoises(inner: View): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = inner.background
            setPadding(inner.paddingLeft, inner.paddingTop, inner.paddingRight, inner.paddingBottom)
        }
        inner.background = null
        inner.setPadding(0, 0, 0, 0)
        inner.findViewById<View>(R.id.widget_focus_ring_box).layoutParams.apply { width = dp(176); height = dp(176) }
        tile.addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER }
        for (kind in listOf(NoisePlayer.Kind.BROWN, NoisePlayer.Kind.PINK, NoisePlayer.Kind.WHITE)) {
            val chip = Button(context, null, 0, R.style.Chip).apply {
                setText(kind.label)
                setOnClickListener { if (NoisePlayer.kind == kind) NoisePlayer.stop() else NoisePlayer.play(kind) }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            noiseChips[kind] = chip
        }
        tile.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        return tile
    }

    /** Police agrandie : le texte ne dépasse pas sa taille à 1,0 ([maxSp]), celle qui tient dans le cadran. Réduite, il suit. */
    private fun capToDial(text: TextView, maxSp: Float) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(text.textSize, maxSp * context.resources.displayMetrics.density))
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
            !state.running && cadran -> R.string.focus_paused
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
        for ((kind, chip) in noiseChips) chip.isSelected = NoisePlayer.kind == kind
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

/**
 * Son et micro de l'ordinateur ; petit : muet et volume −/+ ; grand : une ligne par source (sortie, micro et, sur
 * Windows, chaque appli qui a du son).
 */
class AudioWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    /** Conteneur stable : le contenu est refait quand la taille change. */
    override val view = FrameLayout(context)
    private var audio: ComputerAudioView? = null
    private var mixer: SoundList? = null
    private var shownSize: WidgetSize? = null
    private var active = false

    override fun applySize() {
        if (placed.size == shownSize) return
        shownSize = placed.size
        mixer?.setActive(false)
        view.removeAllViews()
        audio = null
        mixer = null
        val fill = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (placed.size == WidgetSize.L) {
            val list = SoundList(context, host.scope)
            mixer = list
            view.addView(
                LinearLayout(context).asCard(16).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(cardTitle(R.string.computer_volume, R.drawable.ic_volume))
                    addView(
                        ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) },
                    )
                },
                fill,
            )
        } else {
            val bar = ComputerAudioView(context).apply {
                attach(host.scope)
                if (placed.size == WidgetSize.S) compact()
            }
            audio = bar
            view.addView(
                LinearLayout(context).asCard(16).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(cardTitle(R.string.computer_volume, R.drawable.ic_volume))
                    addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                },
                fill,
            )
            bar.refresh()
        }
        setActive(active)
    }

    override suspend fun load() {
        audio?.refresh()
    }

    override fun setActive(active: Boolean) {
        this.active = active
        mixer?.setActive(active)
    }
}

/**
 * Ce qui joue sur l'ordinateur (contrôles multimédias de Windows, MediaRemote sur Mac), comme la page Média ;
 * petit : titre et boutons ; moyen : la lecture sur une ligne ; grand : la même, puis les sites de la page Média
 * (toucher ouvre, appui long retire, « + » du titre ajoute). Pas de barre de son : en 2 × 2 elle prendrait la place
 * des sites, et le widget Son la fait. Le widget garde l'appui long : sinon la grille entrerait en mode Modifier.
 */
class MediaWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val playing = NowPlayingView(context).apply { attach(host.scope) }

    private val sites: TileSection = TileSection(
        context, host, R.string.widget_media_sites, R.drawable.ic_link, R.string.links_add_title,
        tilesOf = { pages -> pages.firstOrNull { it.media }?.tiles },
        onAdd = { host.overlays.addLinks { sites.reload() } },
    )

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        playing.bind(true)
        addView(playing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sites.addTo(this, headerTopMarginDp = 8)
    }

    private val big get() = placed.size == WidgetSize.L

    init {
        // L'appui long d'un site n'entre pas en mode Modifier ; celui des autres tailles, si.
        sites.grid.onTap = { cell, _ -> sites.tileOf(cell)?.let(::open) }
        sites.grid.onHold = { cell, _ -> sites.tileOf(cell)?.let(::confirmRemove) }
    }

    override fun applySize() {
        playing.layout(if (placed.size == WidgetSize.S) NowPlayingView.Layout.SMALL else NowPlayingView.Layout.ROW)
        sites.shown = big
        if (big) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (big) sites.load()
    }

    private fun open(site: DeckTile) =
        sites.run(site) { result -> result.openedApp?.let { host.main?.showBanner(context.getString(R.string.media_opened_app, it), BANNER_S) } }

    private fun confirmRemove(site: DeckTile) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.widget_media_remove_confirm, site.label))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apps_remove) { _, _ ->
                host.scope.launch {
                    runCatchingCancellable { BackendClient(context).deck.removeLink(site.id) }
                        .onSuccess {
                            IconCache.forget(site.id)
                            sites.reload()
                        }
                        .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
                }
            }
            .showImmersive()
    }

    override fun setActive(active: Boolean) = playing.setActive(active)

    private companion object {
        const val BANNER_S = 4
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
