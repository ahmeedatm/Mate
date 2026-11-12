package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ArrayRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.LifecycleOwner
import app.mate.MainActivity
import app.mate.R
import app.mate.github.Forge
import app.mate.net.BackendClient
import app.mate.net.CalendarEvent
import app.mate.net.DeckPage
import app.mate.net.DeckTile
import app.mate.net.HomeFeatures
import app.mate.net.ShoppingItem
import app.mate.net.TaskItem
import app.mate.net.runCatchingCancellable
import app.mate.ui.ComputerAudioView
import app.mate.ui.IconCache
import app.mate.ui.ListShape
import app.mate.ui.NowPlayingView
import app.mate.ui.ShoppingCard
import app.mate.ui.TasksCard
import app.mate.ui.TitleView
import app.mate.ui.dp
import app.mate.ui.errorText
import app.mate.ui.label
import app.mate.ui.mixer.AudioOutputChooser
import app.mate.ui.showImmersive
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeColorList
import app.mate.ui.themeFont
import app.mate.ui.themePx
import app.mate.ui.themeRes
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
    /** Console : l'ordinateur ne répond plus ; l'Accueil le dit une fois, dans le héros. Null : il répond. */
    val unreachable: Down?
    /** Mode Modifier en cours : l'éditeur d'une touche ne s'ouvre que là. */
    val editing: Boolean
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
    /** Le cycle de vie de l'écran (viewLifecycleOwner) : [app.mate.ui.AgentsView.attach] en a besoin. */
    val owner: LifecycleOwner
}

/** Contexte repeint en fenêtre LCD : repérable, pour retrouver le contexte d'origine (`HomeWidget.outerContext`). */
private class LcdContext(base: Context, overlay: Int) : ContextThemeWrapper(base, overlay)

/**
 * Un widget de l'Accueil : sa vue remplit ses cases. [onMinute] : chaque minute (affichage) ; [load] : chaque minute
 * aussi, ce qui passe par le réseau ; [setActive] : page à l'écran ou non.
 */
abstract class HomeWidget(val context: Context, val host: HomeHost, val placed: Placed) {
    abstract val view: View

    /**
     * Adapte la vue à la forme posée : une mise en page par [family], jamais un simple étirement. Plus grand, le widget
     * montre plus de choses ; plus petit, il garde l'essentiel. Les tailles de texte et d'image suivent la taille réelle
     * de la vue (elle change avec le profil de rangées, [RowProfile]), pas une taille supposée de la forme.
     */
    open fun applySize() = Unit

    /** Famille de la forme posée, celle que [applySize] dessine. */
    protected val family get() = placed.size.family

    /**
     * Aperçu du catalogue : remplit le widget avec son exemple ([WidgetSamples]), après [applySize] ; l'exemple tient si
     * [applySize] repasse. Le catalogue n'appelle ni [load] ni [setActive] et garde les touchers pour lui : rien ne doit
     * partir sur le réseau, tourner en boucle ni écrire dans `placed.config`, constructeur compris.
     */
    open fun showPreview(sample: WidgetSample) = Unit
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
    /** Ce widget est une fenêtre LCD (Instrument) : chiffres en Departure Mono, orange ([LcdStyle]). */
    /** Contexte d'origine, pour dialogues, toasts et `as Activity` : le contexte LCD ne sert qu'à créer les vues. */
    protected val outerContext: Context get() = (context as? LcdContext)?.baseContext ?: context

    protected val lcd get() = LcdStyle.applies(placed.type, themeRes(context, R.attr.c_lcd_overlay))

    /** Le thème pose les widgets de touches dans un panneau à filet, même sans grille (Instrument, `c_home_card_outline`). */
    protected val panel get() = themeBool(context, R.attr.c_home_card_outline)

    /** Petit titre d'une fenêtre LCD (Eau, Météo) : capitales en Departure Mono, comme la maquette. */
    protected fun lcdCaption() = label(LCD_CAPTION_SP, R.attr.c_text_dim).apply {
        typeface = themeFont(context, R.attr.c_font_display)
        isAllCaps = true
        letterSpacing = LCD_CAPTION_SPACING
        maxLines = 1
    }

    /** Petit titre en capitales d'un panneau de Colonne (Applis, Touches) : la grille a le sien, la liste en manque. Compact : il prend [TileShapes.PANEL_CAPTION_DP]. */
    protected fun panelCaption(text: Int) = TitleView(context).apply {
        setText(text)
        textSize = PANEL_CAPTION_SP
        includeFontPadding = false
        minHeight = dp(TileShapes.PANEL_CAPTION_DP)
        maxHeight = dp(TileShapes.PANEL_CAPTION_DP)
        gravity = Gravity.CENTER_VERTICAL
    }
    protected fun color(id: Int) = themeColor(context, id)
    protected fun dp(value: Int) = context.dp(value)

    /** Carte de fond commune : la tuile du thème au style choisi dans Réglages > Thème (HomeCard). */
    protected fun <T : View> T.asCard(padding: Int? = null): T = apply {
        background = HomeCard.background(context)
        val px = padding?.let { dp(it) } ?: themePx(context, R.attr.c_card_pad)
        setPadding(px, px, px, px)
    }

    /** Widget posé à nu (Héros, Journée, Ligne du jour) : le thème à cadre partout (`c_bare_frame`, Instrument nuit) lui donne quand même la carte au style choisi. */
    protected fun <T : View> T.barelyFramed(): T = apply {
        if (themeBool(context, R.attr.c_bare_frame)) background = HomeCard.background(context)
    }

    protected fun cardTitle(text: Int) = TitleView(context).apply {
        setText(text)
    }

    protected fun label(size: Float, colorId: Int = R.attr.c_text, light: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color(colorId))
        if (light) typeface = themeFont(context, R.attr.c_font_light)
    }

    companion object {
        /** Corps du petit titre d'un panneau de Colonne ([panelCaption]). */
        const val PANEL_CAPTION_SP = 10f

        /** Titre d'une fenêtre LCD : taille, espacement des lettres, et hauteur qu'il prend ([lcdCaption]). */
        const val LCD_CAPTION_SP = 11f
        const val LCD_CAPTION_SPACING = 0.08f
        const val LCD_CAPTION_DP = 18

        /** Voile de la couleur du fond sur un widget indisponible (0x8C : 55 %). */
        private const val UNAVAILABLE_VEIL = 0x8C

        /** Sans ordinateur, les widgets qui en ont besoin laissent la place à une carte « Connecter mon ordinateur ». */
        fun create(context: Context, host: HomeHost, placed: Placed, noComputer: Boolean): HomeWidget =
            if (noComputer && placed.type.computer) ConnectWidget(context, host, placed)
            else build(lcdContext(context, placed.type), host, placed)

        /** Le contexte du thème, repeint en fenêtre LCD (Instrument) pour les widgets de [LcdStyle], posé une seule fois ici. */
        private fun lcdContext(context: Context, type: WidgetType): Context {
            val overlay = themeRes(context, R.attr.c_lcd_overlay)
            return if (LcdStyle.applies(type, overlay)) LcdContext(context, overlay) else context
        }

        private fun build(context: Context, host: HomeHost, placed: Placed): HomeWidget =
            when (placed.type) {
                WidgetType.CLOCK -> ClockWidget(context, host, placed)
                WidgetType.NEXT -> NextWidget(context, host, placed)
                WidgetType.AGENDA -> AgendaWidget(context, host, placed)
                WidgetType.TASKS -> TasksWidget(context, host, placed)
                WidgetType.SHOPPING -> ShoppingWidget(context, host, placed)
                WidgetType.FOCUS -> FocusWidget(context, host, placed)
                WidgetType.WATER -> WaterWidget(context, host, placed)
                WidgetType.AUDIO -> AudioWidget(context, host, placed)
                WidgetType.MEDIA -> MediaWidget(context, host, placed)
                WidgetType.PERF -> PerfWidget(context, host, placed)
                WidgetType.KEYS -> KeysWidget(context, host, placed)
                WidgetType.WEATHER -> WeatherWidget(context, host, placed)
                WidgetType.TRANSPORT -> TransportWidget(context, host, placed)
                WidgetType.NOTE -> NoteWidget(context, host, placed)
                WidgetType.HERO -> HeroWidget(context, host, placed)
                WidgetType.DAY -> DayWidget(context, host, placed)
                WidgetType.DAYLINE -> DayLineWidget(context, host, placed)
                WidgetType.AGENTS -> AgentsWidget(context, host, placed)
                WidgetType.JIRA -> jiraWidget(context, host, placed)
                WidgetType.GITLAB -> gitLabWidget(context, host, placed)
                WidgetType.MIXER -> MixerWidget(context, host, placed)
                WidgetType.OPEN -> OpenWindowsWidget(context, host, placed)
                WidgetType.APPS -> AppsWidget(context, host, placed)
                // Thèmes Instrument (fenêtres LCD) : la console à touches.
                WidgetType.DISCORD -> if (themeRes(context, R.attr.c_lcd_overlay) != 0) DiscordConsoleWidget(context, host, placed) else DiscordWidget(context, host, placed)
                WidgetType.MEETING -> MeetingWidget(context, host, placed)
                WidgetType.CALL -> AnyCallWidget(context, host, placed)
                WidgetType.PAD -> PadWidget(context, host, placed)
                WidgetType.CHECKLIST -> ChecklistWidget(context, host, placed)
                WidgetType.PRESENT -> PresentWidget(context, host, placed)
                WidgetType.AWAY -> AwayWidget(context, host, placed)
                WidgetType.HA -> HaWidget(context, host, placed)
                WidgetType.PIPELINES -> pipelinesWidget(context, host, placed)
                WidgetType.GITHUB -> gitLabWidget(context, host, placed, Forge.GITHUB)
                WidgetType.ACTIONS -> pipelinesWidget(context, host, placed, Forge.GITHUB)
                WidgetType.YTMUSIC -> YtMusicWidget(context, host, placed)
                WidgetType.WEB -> WebWidget(context, host, placed)
                WidgetType.NEWS -> NewsWidget(context, host, placed)
                WidgetType.MAIL -> MailWidget(context, host, placed)
                WidgetType.MEALS -> MealsWidget(context, host, placed)
                WidgetType.METRICS -> MetricsWidget(context, host, placed)
                WidgetType.VALORANT -> ValorantWidget(context, host, placed)
                WidgetType.SCREEN -> ScreenWidget(context, host, placed)
            }
    }
}

/**
 * Son de l'ordinateur, une mise en page par famille de formes : Pastille, le volume en anneau (toucher coupe) ; Ligne,
 * muet, curseur, volume et micro ; Colonne, la carte se remplit à hauteur du volume (glisser règle, toucher coupe) ;
 * Bloc et plus grand, une ligne par source (sortie, micro, applis sous Windows), et dès Grand le choix de la sortie audio.
 */
class AudioWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    /** Conteneur stable : le contenu est refait quand la taille change. */
    override val view = FrameLayout(context)
    private var audio: ComputerAudioView? = null
    private var mixer: SoundList? = null
    private var link: AudioLink? = null
    private var shape: AudioShapes.Shape? = null
    private var shownSize: WidgetSize? = null
    private var active = false
    private var preview = false

    override fun applySize() {
        if (placed.size == shownSize) return
        shownSize = placed.size
        mixer?.setActive(false)
        link?.setActive(false)
        view.removeAllViews()
        audio = null
        mixer = null
        link = null
        shape = null
        when (family) {
            ShapeFamily.PILL -> showShape(padded = true) { it.ring() }
            ShapeFamily.COLUMN -> showShape(padded = false) { it.fill() }
            ShapeFamily.ROW -> showBar()
            ShapeFamily.BLOCK -> showList(outputs = false)
            ShapeFamily.LARGE, ShapeFamily.FULL -> showList(outputs = true)
        }
        if (preview) showPreview(AudioSample)
        setActive(active)
    }

    private val fill get() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Pastille et Colonne : une forme d'[AudioShapes], relue par un [AudioLink]. */
    private fun showShape(padded: Boolean, make: (AudioShapes) -> AudioShapes.Shape) {
        // La forme est tenue par ce lien : une réponse en retard après un changement de taille vise l'ancienne vue.
        var made: AudioShapes.Shape? = null
        val audioLink = AudioLink(context, host.scope, shown = { view.isShown }) { state -> made?.render?.invoke(state) }
        val built = make(AudioShapes(context, audioLink))
        made = built
        link = audioLink
        shape = built
        // Le remplissage se tire à la verticale : le widget garde le balayage, sinon la grille changerait de profil.
        view.addView(
            OwnerFrameLayout(context, ownsLongPress = false, ownsSwipe = !padded).asCard(if (padded) 16 else 0).apply {
                clipToOutline = true
                addView(built.view, fill)
            },
            fill,
        )
        built.render(null)
    }

    /** Ligne : muet, curseur, volume et micro ; dès 3 × 1 le titre à côté, empilés ils ne tiennent pas en hauteur. */
    private fun showBar() {
        val bar = ComputerAudioView(context)
        audio = bar
        val wide = placed.size.w >= 3
        view.addView(
            LinearLayout(context).asCard(16).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(cardTitle(R.string.computer_volume))
                addView(
                    bar,
                    if (wide) {
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) }
                    } else {
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                    },
                )
            },
            fill,
        )
    }

    /** Bloc et plus grand : une ligne par source ; [outputs] : le bouton du titre choisit la sortie audio. */
    private fun showList(outputs: Boolean) {
        val list = SoundList(context, host.scope)
        mixer = list
        val title = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(cardTitle(R.string.computer_volume), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (outputs) addView(outputButton())
        }
        view.addView(
            LinearLayout(context).asCard(16).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(
                    ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) },
                )
            },
            fill,
        )
    }

    private fun outputButton() = ImageButton(context, null, 0, R.style.IconButton).apply {
        setImageResource(R.drawable.ic_headphones)
        // Le tint du style n'est pas appliqué à un bouton créé en code : l'icône blanche disparaîtrait en thème clair.
        ImageViewCompat.setImageTintList(this, themeColorList(context, R.attr.c_text))
        contentDescription = context.getString(R.string.mixer_output_action)
        val chooser = AudioOutputChooser(
            context,
            host.scope,
            onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
            onSwitched = {},
        )
        setOnClickListener { chooser.show() }
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is AudioSample) return
        preview = true
        shape?.render?.invoke(SoundSamples.audio)
        audio?.showSample(SoundSamples.audio)
        mixer?.showSample(SoundSamples.mixer(context))
    }

    override suspend fun load() {
        if (active) audio?.refresh()
    }

    override fun setActive(active: Boolean) {
        this.active = active
        // Rien ne part sur le réseau avant l'écran (le catalogue n'appelle pas setActive).
        if (active) audio?.let {
            it.attach(host.scope)
            it.refresh()
        }
        mixer?.setActive(active)
        link?.setActive(active)
    }
}

/**
 * Ce qui joue sur l'ordinateur (contrôles multimédias de Windows, MediaRemote sur Mac), comme la page Média ; une mise
 * en page par famille de formes. Pastille : la pochette remplit la case, lecture / pause dessus ; Colonne : l'affiche,
 * titre et boutons sur la pochette ([PlayingShapes], choisies par Ahmed, #182) ; Ligne : la lecture sur une ligne ;
 * Bloc et plus grand : la même, puis les sites de la page Média (toucher ouvre, appui long retire, « + » du titre
 * ajoute), d'autant plus nombreux que le widget est grand. Ni pastilles des lectures ni barre de son : le widget Son
 * règle le volume. Seule la grille des sites garde l'appui long (mode Modifier ailleurs).
 */
class MediaWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val playing = NowPlayingView(context).apply {
        attach(host.scope)
        compact = true
    }

    private val sites: TileSection = TileSection(
        context, host, R.string.widget_media_sites, R.string.links_add_title,
        tilesOf = { pages -> pages.firstOrNull { it.media }?.tiles },
        onAdd = { host.overlays.addLinks { sites.reload() } },
    )

    /** Ligne et plus grand : la lecture puis les sites. */
    private val card = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        playing.bind(true)
        addView(playing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sites.addTo(this, headerTopMarginDp = 8)
    }

    /** Conteneur stable : la carte ou une forme de [PlayingShapes], selon la famille. */
    override val view = FrameLayout(context)
    private var link: PlayingLink? = null
    private var shape: PlayingShapes.Shape? = null
    private var shownFamily: ShapeFamily? = null
    private var active = false
    private var preview = false

    private val big get() = family in BIG

    init {
        // L'appui long d'un site n'entre pas en mode Modifier ; celui des autres tailles, si.
        sites.grid.ownsLongPress = true
        sites.grid.onTap = { cell, _ -> sites.tileOf(cell)?.let(::open) }
        sites.grid.onHold = { cell, _ -> sites.tileOf(cell)?.let(::confirmRemove) }
    }

    override fun applySize() {
        // Ligne (une rangée de cases) : titre sur une ligne, l'avancement reste dans la carte.
        playing.titleLines = if (placed.size.h == 1) 1 else 2
        sites.shown = big && !preview
        if (family == shownFamily) return
        shownFamily = family
        // Arrêt direct : `active` garde l'état de l'écran, relancé plus bas dans la nouvelle forme.
        playing.setActive(false)
        link?.setActive(false)
        view.removeAllViews()
        link = null
        shape = null
        val fill = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        when (family) {
            ShapeFamily.PILL -> showShape { it.pill() }
            ShapeFamily.COLUMN -> showShape { it.column() }
            else -> {
                playing.layout(NowPlayingView.Layout.ROW)
                view.addView(card, fill)
            }
        }
        if (preview) showPreview(MediaSample)
        setActive(active)
    }

    /** Pastille et Colonne : une forme de [PlayingShapes], relue par un [PlayingLink]. */
    private fun showShape(make: (PlayingShapes) -> PlayingShapes.Shape) {
        // La forme est tenue par ce lien : une réponse en retard après un changement de taille vise l'ancienne vue.
        var made: PlayingShapes.Shape? = null
        val api = BackendClient(context).system
        val playingLink = PlayingLink(
            host.scope,
            shown = { view.isShown },
            read = { NowTrack.ofMedia(api.media()) },
            cover = { track -> track.app?.let { app -> track.coverKey?.let { api.mediaThumb(app, it.substringAfter('|')) } } },
            send = { track, op -> api.mediaControl(op, track.app) },
            coverPx = dp(COVER_DP),
        ) { state -> made?.render?.invoke(state) }
        val built = make(PlayingShapes(context, R.string.media_empty) { op -> playingLink.action(op) })
        made = built
        link = playingLink
        shape = built
        view.addView(
            FrameLayout(context).asCard(0).apply {
                clipToOutline = true
                addView(built.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        built.render(null)
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is MediaSample) return
        preview = true
        // shortcut: les sites ne sont pas montrés dans l'aperçu (il faudrait des tuiles d'exemple), à revoir avec le catalogue.
        sites.shown = false
        val art = PlayingShapes.sampleCover(context)
        shape?.render?.invoke(PlayingSamples.playing(context, art))
        if (shape == null) playing.showSample(PlayingSamples.media(context), art)
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

    override fun setActive(active: Boolean) {
        this.active = active
        // Rien ne part sur le réseau avant l'écran (le catalogue n'appelle pas setActive).
        val shaped = shape != null
        playing.setActive(active && !shaped && !preview)
        link?.setActive(active && !preview)
        if (active && big && !preview) host.scope.launch { load() }
    }

    private companion object {
        const val BANNER_S = 4

        /** Pochette des petites formes, réduite à la taille d'une colonne. */
        const val COVER_DP = 280
        val BIG = setOf(ShapeFamily.BLOCK, ShapeFamily.LARGE, ShapeFamily.FULL)
    }
}

/** Exemple des Tâches pour le catalogue : les libellés, dont les [done] derniers sont faits. */
class TasksSample(@ArrayRes val labels: Int, val done: Int) : WidgetSample

/** Tâches du jour, stockées sur l'ordinateur ; le nombre restant va sur la pastille de la barre latérale. */
class TasksWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val tasks = TasksCard(context).apply { attach(host.scope) { pending -> host.main?.setPendingTasks(pending) } }
    override val view: View = tasks.asCard()

    override fun applySize() = tasks.setShape(ListShape.of(placed.size))

    override fun showPreview(sample: WidgetSample) {
        val example = sample as? TasksSample ?: return
        val labels = context.resources.getStringArray(example.labels)
        tasks.showPreview(
            labels.mapIndexed { i, label ->
                TaskItem("sample-$i", label, daily = false, done = i >= labels.size - example.done, auto = false, detail = null, hasUrl = false)
            },
        )
    }

    override suspend fun load() = tasks.load()

    override fun setUnavailable(on: Boolean) {
        tasks.quiet = on
        super.setUnavailable(on)
    }
}

/** Exemple des Courses pour le catalogue : les articles, dont les [done] derniers sont pris. */
class ShoppingSample(@ArrayRes val labels: Int, val done: Int) : WidgetSample

/** Liste de courses, stockée sur l'ordinateur ; l'ajout à la voix la remplit (« ajoute du lait à la liste de courses »). */
class ShoppingWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val shopping = ShoppingCard(context).apply { attach(host.scope) }
    override val view: View = shopping.asCard()

    override fun applySize() = shopping.setShape(ListShape.of(placed.size))

    override fun showPreview(sample: WidgetSample) {
        val example = sample as? ShoppingSample ?: return
        val labels = context.resources.getStringArray(example.labels)
        shopping.showPreview(labels.mapIndexed { i, label -> ShoppingItem("sample-$i", label, done = i >= labels.size - example.done) })
    }

    override suspend fun load() = shopping.load()

    override fun setUnavailable(on: Boolean) {
        shopping.quiet = on
        super.setUnavailable(on)
    }
}

/** À la place d'un widget qui a besoin de l'ordinateur, tant qu'aucun n'est appairé. */
class ConnectWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard().apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(context).apply {
            setImageResource(placed.type.icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
