package com.unowhy.unomate.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.unowhy.unomate.AnimFamily
import com.unowhy.unomate.Computers
import com.unowhy.unomate.MainActivity
import com.unowhy.unomate.R
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.CalendarEvent
import com.unowhy.unomate.net.HomeFeatures
import com.unowhy.unomate.ui.home.COLS
import com.unowhy.unomate.ui.home.DayLineWidget
import com.unowhy.unomate.ui.home.DeckOverlays
import com.unowhy.unomate.ui.home.DeckPagesCache
import com.unowhy.unomate.ui.home.Down
import com.unowhy.unomate.ui.home.Fit
import com.unowhy.unomate.ui.home.HomeHost
import com.unowhy.unomate.ui.home.HomeLayout
import com.unowhy.unomate.ui.home.HomeWidget
import com.unowhy.unomate.ui.home.Placed
import com.unowhy.unomate.ui.home.ProfilesDialog
import com.unowhy.unomate.ui.home.ROWS
import com.unowhy.unomate.ui.home.RowProfile
import com.unowhy.unomate.ui.home.WidgetGrid
import com.unowhy.unomate.ui.home.WidgetSize
import com.unowhy.unomate.ui.home.WidgetType
import com.unowhy.unomate.ui.home.WidgetZoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Accueil à widgets, personnalisable comme un écran de téléphone : appui long = mode Modifier (ajouter, retirer,
 * changer la taille, glisser). Chaque page de la grille est un profil nommé, parcouru par la barre de profil en haut.
 * La disposition est propre à la tablette ([HomeLayout]) ; l'agenda est chargé ici une fois pour tous les widgets
 * qui l'affichent, toutes les 5 min, le reste chaque minute.
 */
class HomeFragment : Fragment(), HomeHost, WidgetGrid.Listener {

    private lateinit var grid: WidgetGrid
    private lateinit var scroll: ScrollView
    private lateinit var editBar: View

    private lateinit var layout: HomeLayout
    /** Disposition au moment d'entrer dans le mode Modifier : « Annuler » y revient. */
    private var backup: HomeLayout? = null
    private val widgets = LinkedHashMap<Placed, HomeWidget>()
    private var page = 0

    /** Profil affiché (la barre et la fin d'appel s'en servent). */
    val shownPage: Int get() = page

    /** La disposition affichée (l'objet vivant : la barre et l'appel en cours s'en servent), null avant le chargement. */
    val shownLayout: HomeLayout? get() = if (::layout.isInitialized) layout else null

    /** Premier affichage de cet écran : il s'ouvre sur le profil Accueil, pas sur la première page. */
    private var pageChosen = false

    /** Mode Modifier en cours : un appel qui commence ne fait pas changer d'écran. */
    val editing: Boolean get() = ::grid.isInitialized && grid.editing
    /** Famille de thème Cadran : disposition à part et lignes inégales. */
    private val cadran get() = themeBool(requireContext(), R.attr.c_cadran)
    /** Aucun ordinateur appairé : les widgets qui en ont besoin proposent de le connecter. */
    private var noComputer = false
    private var loop: Job? = null
    /** Chargement du profil qu'on vient d'ouvrir : annulé dès qu'on en ouvre un autre. */
    private var profileLoad: Job? = null
    /** Entre onStart et onStop (l'état du cycle de vie n'y est pas encore STARTED). */
    private var started = false
    /** « Relier Outlook / Google » touché : l'agenda relié sur l'ordinateur apparaît dans la minute. */
    private var waitingLink = false

    /** Pages du deck partagées entre les widgets ; l'écran est recréé quand l'ordinateur change. */
    private val pagesCache by lazy { DeckPagesCache { BackendClient(requireContext()).deck.pages() } }

    override var events: List<CalendarEvent> = emptyList()
    override var features = HomeFeatures()
    override var calendarNote = ""
    override var chosenId: String? = null
    override var unreachable: Down? = null
    override val scope: CoroutineScope get() = viewLifecycleOwner.lifecycleScope
    override val main get() = activity as? MainActivity
    override val owner: LifecycleOwner get() = viewLifecycleOwner

    private var deckOverlays: DeckOverlays? = null

    /** Widget agrandi, par-dessus la grille et les panneaux du Deck ; créé au premier appui long. */
    private var zoomView: WidgetZoom? = null
    private val zoom: WidgetZoom
        get() = zoomView ?: WidgetZoom(requireContext()).also {
            zoomView = it
            requireView().findViewById<ViewGroup>(R.id.home_root).addView(it, ViewGroup.LayoutParams(-1, -1))
        }
    override val overlays: DeckOverlays
        get() = deckOverlays ?: DeckOverlays(requireView().findViewById(R.id.home_root), scope).also { deckOverlays = it }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        deckOverlays = null
        grid = view.findViewById(R.id.home_grid)
        scroll = view.findViewById(R.id.home_scroll)
        editBar = view.findViewById(R.id.home_edit_bar)
        grid.listener = this
        grid.blocked = { unreachable != null && dependsOnComputer(it) }

        view.findViewById<View>(R.id.home_edit_add).setOnClickListener { onAddAt(-1, -1) }
        view.findViewById<View>(R.id.home_edit_cancel).setOnClickListener { endEdit(keep = false) }
        view.findViewById<View>(R.id.home_edit_done).setOnClickListener { endEdit(keep = true) }

        // Écran recréé quand l'ordinateur change (MainActivity.onComputerChanged) : lu une fois ici.
        noComputer = Computers.all(requireContext()).isEmpty()
        if (cadran) {
            unreachable = main?.unreachable()
            grid.setPadding(dp(44), dp(28), dp(40), dp(32))
            grid.gap = dp(16)
            grid.rowWeights = RowProfile.CADRAN_ROWS
            // L'heure déborde de quelques dp sous sa ligne, comme dans la maquette.
            grid.clipChildren = false
        }
        layout = HomeLayout.load(requireContext(), cadran)
        // `or` : tout s'applique. Réunion posé une fois ; le Deck d'office du premier Deck V2 retiré s'il n'a pas été touché.
        if (nameProfiles() or (cadran && (layout.ensureRoles(getString(R.string.profile_call)) or layout.dropDefaultDeck()))) {
            HomeLayout.save(requireContext(), layout, cadran)
        }
        if (!pageChosen) {
            page = layout.homeProfile
            pageChosen = true
        }
        build()

        // Rétro : les tubes passent derrière les widgets, voilés derrière ceux sans fond (bareWidgets) ; les textes foncés
        // sont détourés d'un halo pour rester lisibles dessus.
        if (themeBool(requireContext(), R.attr.c_flow)) grid.afterLayout = { Look.haloTexts(grid) }
        // Le panneau document peut réduire la largeur de moitié : 2 colonnes, la page défile.
        scroll.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val narrow = r - l < dp(880)
            if (narrow && grid.editing) endEdit(keep = true)
            grid.post {
                grid.narrow = narrow
                grid.viewportHeight = b - t
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        activate()
        if (!isHidden) {
            startLoop()
            animateIn()
        }
    }

    override fun onStop() {
        started = false
        // Les autres panneaux restent : l'écran qui s'éteint ne doit pas perdre une macro à moitié saisie.
        deckOverlays?.hideAgentsBoard()
        closeZoom()
        widgets.values.forEach { it.setActive(false) }
        loop?.cancel()
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (hidden) {
            closeZoom()
            deckOverlays?.hideAll()
            if (grid.editing) endEdit(keep = true)
            loop?.cancel()
        } else {
            startLoop()
            animateIn()
        }
        activate()
    }

    /**
     * Les widgets de la page arrivent l'un après l'autre, de haut en bas puis de gauche à droite. Cadran : le héros
     * descend d'un cran, la ligne du jour se dessine, les tuiles montent de 50 ms en 50 ms (ressorts).
     */
    private fun animateIn() {
        val shown = widgets.keys.filter { it.page == page }.sortedWith(compareBy({ it.row }, { it.col }))
        if (!cadran) return shown.forEachIndexed { i, placed -> widgets[placed]?.let { Look.enter(it.view, i) } }
        var tiles = 0
        for (placed in shown) {
            val widget = widgets[placed] ?: continue
            when (placed.type) {
                WidgetType.HERO, WidgetType.DAY -> Look.enterSpring(widget.view, 0, -8)
                WidgetType.DAYLINE -> (widget as? DayLineWidget)?.arrive()
                else -> Look.enterSpring(widget.view, 160L + 50L * tiles++, 24)
            }
        }
    }

    // --- Widgets

    /** (Re)crée tous les widgets d'après la disposition. */
    private fun build() {
        closeZoom()
        widgets.values.forEach { it.setActive(false) }
        widgets.clear()
        grid.removeAllViews()
        layout.widgets.forEach { addWidget(it) }
        page = page.coerceIn(0, layout.pages - 1)
        grid.page = page
        renderProfileBar()
        activate()
    }

    private fun addWidget(placed: Placed): HomeWidget {
        val widget = HomeWidget.create(requireContext(), this, placed, noComputer)
        widget.view.tag = placed
        widget.setUnavailable(unreachable != null && dependsOnComputer(placed))
        widget.applySize()
        widget.onCalendar()
        widget.onMinute()
        grid.addView(widget.view)
        widgets[placed] = widget
        return widget
    }

    /** Seuls les widgets du profil à l'écran se mettent à jour en continu (Focus, lecture en cours). */
    private fun activate() {
        val visible = started && !isHidden
        widgets.forEach { (placed, widget) -> widget.setActive(visible && placed.page == page) }
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            var tick = 0
            while (true) {
                // Le battement d'abord : les chiffres basculent à hh:mm:00, avant les échanges réseau.
                widgets.values.forEach { it.onMinute() }
                if (!noComputer && (tick % 5 == 0 || waitingLink) && showsCalendar()) {
                    fetchCalendar()
                    renderCalendar()
                }
                for (widget in shownWidgets()) widget.load()
                zoomView?.current()?.let {
                    it.onMinute()
                    it.load()
                }
                // Sans widget Tâches, la pastille de la barre latérale n'a plus rien à compter.
                if (widgets.keys.none { it.type == WidgetType.TASKS }) main?.setPendingTasks(0)
                tick++
                delay(MinuteClock.msToNextMinute(System.currentTimeMillis()) + MinuteClock.SLACK_MS)
            }
        }
    }

    /** Widgets recréés (Annuler, modèle) : leurs données tout de suite, sans attendre la minute suivante. */
    private fun refreshAll() {
        scope.launch {
            if (!noComputer && showsCalendar()) {
                fetchCalendar()
                renderCalendar()
            }
            for (widget in shownWidgets()) widget.load()
        }
    }

    /** Les widgets du profil affiché : les autres ne relisent pas l'ordinateur (tablette lente). */
    private fun shownWidgets() = widgets.filterKeys { it.page == page }.values.toList()

    private fun showsCalendar() = widgets.keys.any {
        it.type == WidgetType.AGENDA || it.type == WidgetType.NEXT || it.type == WidgetType.HERO || it.type == WidgetType.DAY || it.type == WidgetType.DAYLINE
    }

    private suspend fun fetchCalendar() {
        val client = BackendClient(requireContext())
        runCatching { features = client.deck.homeFeatures() }
        if (features.linked) waitingLink = false
        try {
            val days = client.calendar.days(AGENDA_DAYS)
            events = days.events
            calendarNote = days.error ?: if (days.source == "mock") getString(R.string.calendar_mock) else ""
        } catch (e: Exception) {
            calendarNote = errorText(e)
        }
    }

    // --- HomeHost

    override fun renderCalendar() = widgets.values.forEach { it.onCalendar() }

    /** Ce qui s'éteint avec l'ordinateur : ses widgets, et la ligne du jour (ses rendez-vous viennent de lui). */
    private fun dependsOnComputer(placed: Placed) = placed.type.computer || placed.type == WidgetType.DAYLINE

    /**
     * Thèmes Cadran : la liaison a changé. Un seul message dans le héros, les widgets qui dépendent de l'ordinateur
     * s'atténuent et ne répondent plus au toucher ; dès qu'il répond, tout est relu.
     */
    fun onLinkChanged() {
        if (view == null || !cadran) return
        val down = main?.unreachable()
        if (down == unreachable) return
        val recovered = down == null
        unreachable = down
        widgets.forEach { (placed, widget) -> if (dependsOnComputer(placed)) widget.setUnavailable(down != null) }
        renderCalendar()
        if (recovered) refreshAll()
    }

    override fun reloadCalendar() {
        scope.launch {
            fetchCalendar()
            renderCalendar()
        }
    }

    /** Nouvel événement (`event` null) ou modification d'un événement de l'agenda personnel. */
    override fun editEvent(event: CalendarEvent?) {
        val ctx = requireContext()
        val localId = event?.localId
        val save = { draft: EventDraft ->
            scope.launch {
                try {
                    BackendClient(ctx).calendar.saveEvent(localId, draft.title, draft.startMs, draft.allDay, draft.remindBefore)
                    main?.showBanner(getString(R.string.event_saved, draft.title), 4)
                    fetchCalendar()
                    renderCalendar()
                } catch (e: Exception) {
                    main?.showBanner(ctx.errorText(e), 6)
                }
            }
            Unit
        }
        val delete = localId?.let {
            {
                scope.launch {
                    try {
                        BackendClient(ctx).calendar.deleteEvent(it)
                        fetchCalendar()
                        renderCalendar()
                    } catch (e: Exception) {
                        main?.showBanner(ctx.errorText(e), 6)
                    }
                }
                Unit
            }
        }
        EventDialog(ctx, save, delete).show(event)
    }

    override suspend fun deckPages(force: Boolean) = pagesCache.get(force)

    /** Agenda relié : « Mes agendas » (voir, délier, en relier un autre) ; sinon droit à la page de l'ordinateur. */
    override fun showCalendars() {
        if (features.linked) CalendarsDialog(requireContext(), scope, onChanged = { reloadCalendar() }, onAdd = { openAgendaSetup() }).show()
        else openAgendaSetup()
    }

    /** Ouvre sur l'ordinateur la page du compagnon, à la section Agenda (lien ICS à coller). */
    private fun openAgendaSetup() {
        scope.launch {
            try {
                BackendClient(requireContext()).system.openLocalPage("agenda")
                waitingLink = true
                main?.showBanner(getString(R.string.agenda_setup_opened), 8)
            } catch (e: Exception) {
                main?.showBanner(errorText(e), 6)
            }
        }
    }

    /** Tableau des agents Claude en grand (alerte d'un agent, voix). */
    fun showAgentsBoard() = overlays.agentsBoard(viewLifecycleOwner)

    /** Réglage d'un widget changé hors mode Modifier ; en mode Modifier, « Terminé » enregistre. */
    override fun save() {
        if (!grid.editing) HomeLayout.save(requireContext(), layout, cadran)
    }

    // --- Profils

    /** Nom du profil : celui donné par l'utilisateur, sinon « Bureau » pour le premier, « Profil N » pour les autres. */
    fun profileName(index: Int): String = layout.nameOf(index, getString(R.string.profile_first), getString(R.string.profile_default))

    /** Donne son nom par défaut à chaque profil sans nom : il le garde quand on le déplace. Vrai si un nom a été posé. */
    private fun nameProfiles() = layout.nameMissing(getString(R.string.profile_first), getString(R.string.profile_default))

    /**
     * Affiche le profil [index] et charge ses widgets (le chargement du profil quitté est annulé).
     * `animate` : les widgets arrivent un à un (animateIn), ou la grille dans le sens du déplacement dans la barre si
     * l'arrivée des widgets est coupée ; le chargement attend
     * la fin du glissement : des widgets qui se redessinent pendant ce temps feraient recalculer le calque à chaque image.
     */
    fun showProfile(index: Int, animate: Boolean = true) {
        closeZoom()
        deckOverlays?.hideAll()
        profileLoad?.cancel()
        val order = layout.profilesInOrder(inCall = main?.inCall == true)
        val move = RailModel.direction(order.indexOf(page), order.indexOf(index))
        val changed = index != page
        // Changer de profil termine le mode Modifier (gardé), comme quitter l'Accueil.
        if (changed && grid.editing) endEdit(keep = true)
        displayProfile(index)
        val slide = animate && changed
        // Arrivée des widgets active (Réglages > Animations) : ils arrivent un à un, comme en venant d'un autre écran,
        // sans glisser la grille entière par-dessus (son calque serait redessiné à chaque image). Sinon : la grille glisse.
        if (slide && Look.on(requireContext(), AnimFamily.HOME)) animateIn() else if (slide) Look.sharedAxis(grid, fromBelow = move >= 0)
        if (slide) main?.flowTravel(move)
        profileLoad = scope.launch {
            if (slide) delay(Look.NAV_SETTLE_MS)
            shownWidgets().forEach { it.load() }
        }
    }

    /**
     * Disposition changée depuis la page de l'ordinateur (déjà enregistrée) : relue, widgets recréés. Le mode Modifier en
     * cours est abandonné, ce qu'y a fait l'utilisateur ne doit pas écraser l'envoi.
     */
    fun reloadLayout() {
        if (view == null) return
        if (grid.editing) endEdit(keep = false)
        layout = HomeLayout.load(requireContext(), cadran)
        if (nameProfiles() or (cadran && layout.ensureRoles(getString(R.string.profile_call)))) {
            HomeLayout.save(requireContext(), layout, cadran)
        }
        build()
        if (!isHidden) refreshAll()
    }

    /** Widgets sans fond du profil affiché (héros, ligne du jour) : les tubes du Rétro passent voilés derrière (FlowBandView). */
    fun bareWidgets(): List<View> =
        widgets.mapNotNull { (placed, widget) -> widget.view.takeIf { placed.page == page && placed.type.bare } }

    /** Titre, pastilles et widgets actifs, sans rien relire de l'ordinateur. */
    private fun displayProfile(index: Int) {
        page = index.coerceIn(0, layout.pages - 1)
        grid.page = page
        renderProfileBar()
        activate()
    }

    /**
     * Liste des profils. Un changement termine d'abord le mode Modifier en gardant les widgets : `save()` n'écrit rien
     * tant qu'il est actif. Profils ajoutés, déplacés ou retirés : widgets recréés, puis le profil affiché charge ses
     * données. Renommage : la barre seulement, ni widgets ni réseau.
     */
    fun openProfiles() {
        ProfilesDialog(requireContext(), layout, page, ::profileName, !grid.narrow, ::editProfile) { show, structure ->
            if (grid.editing) endEdit(keep = true)
            nameProfiles()
            save()
            when {
                structure -> {
                    build()
                    showProfile(show)
                }
                show != page -> showProfile(show)
                else -> renderProfileBar()
            }
        }.show()
    }

    /** « Modifier ce profil » : le profil choisi s'affiche, puis le mode Modifier commence, comme à l'appui long. */
    private fun editProfile(index: Int) {
        if (grid.editing) endEdit(keep = true)
        if (index != page) showProfile(index)
        // Réunion hors appel : on le règle en aperçu.
        if (index == layout.callProfile) main?.showBanner(getString(R.string.call_profile_preview), 6)
        onEnterEdit()
    }

    /** La barre fixe montre les profils et marque celui affiché. */
    private fun renderProfileBar() {
        main?.onProfileShown(layout, page)
    }

    /**
     * Balayage vertical : entrée voisine de la barre, profil puis outils (Focus, Ordi, Caméra), comme en la touchant. Sans
     * barre (ou profil caché derrière « Plus ») : profil voisin dans l'ordre des profils.
     */
    override fun onPageSwipe(delta: Int) {
        val root = view ?: return
        if (!canPage(delta)) return SwipeFollow.release(root)
        // Le nouvel écran arrive par Look.sharedAxis : celui-ci reprend sa place d'un coup.
        SwipeFollow.reset(root)
        if (main?.railStep(delta) == true) return
        neighborProfile(delta)?.let(::showProfile)
    }

    override fun onPageDrag(dy: Float) {
        view?.let { SwipeFollow.drag(it, dy, canPage(if (dy < 0) 1 else -1)) }
    }

    override fun onPageDragEnd() {
        view?.let(SwipeFollow::release)
    }

    /** Un écran attend dans ce sens : entrée voisine de la barre, sinon profil voisin (caché derrière « Plus »). */
    private fun canPage(delta: Int): Boolean = main?.railCanStep(delta) == true || neighborProfile(delta) != null

    private fun neighborProfile(delta: Int): Int? {
        val order = layout.profilesInOrder(inCall = main?.inCall == true)
        return order.getOrNull(order.indexOf(page) + delta)
    }

    // --- Widget agrandi

    /** Widget maintenu : un second exemplaire dans sa plus grande taille, par-dessus l'écran, à jour tant qu'il est ouvert. */
    override fun onExpand(placed: Placed) {
        if (grid.editing) return
        // Même réglages (objet partagé) : ce qui est changé dans le grand vaut pour le widget de la grille.
        val big = Placed(placed.type, placed.type.zoomSize, placed.page, placed.col, placed.row, placed.config)
        val widget = HomeWidget.create(requireContext(), this, big, noComputer)
        widget.applySize()
        widget.onCalendar()
        widget.onMinute()
        zoom.show(widget) {
            widget.setActive(false)
            // Ce qui a changé dans le grand (tâche cochée, volume) se voit tout de suite dans la grille.
            widgets[placed]?.let { small -> scope.launch { small.load() } }
        }
        widget.setActive(true)
        scope.launch { widget.load() }
    }

    /** Ferme le widget agrandi ; faux s'il n'y en avait pas (Retour). */
    fun closeZoom(): Boolean {
        val shown = zoomView?.takeIf { it.isShowing } ?: return false
        shown.hide()
        return true
    }

    // --- Mode Modifier

    /** Crayon de la barre : commence le mode Modifier, ou le termine (en gardant) s'il est en cours. */
    fun toggleEdit() {
        if (grid.editing) {
            endEdit(keep = true)
            return
        }
        // Panneau document ouvert (2 colonnes) : pas de mode Modifier, comme avant à l'appui long.
        if (grid.narrow) {
            main?.showBanner(getString(R.string.home_edit_narrow), 4)
            return
        }
        closeZoom()
        onEnterEdit()
    }

    private fun onEnterEdit() {
        backup = layout.copy()
        grid.editing = true
        editBar.visibility = View.VISIBLE
        main?.onEditingChanged(true)
        scaleWidgets(EDIT_SCALE)
        (activity as? MainActivity)?.refreshMiniPlayer()
    }

    /** Mode Modifier : les widgets du profil se resserrent un peu (pas de tremblement, c'est une boucle) ; 1 en sortant. */
    private fun scaleWidgets(scale: Float) {
        shownWidgets().forEach {
            Look.navSpring(it.view, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, Look.SPATIAL_DEFAULT, scale)
            Look.navSpring(it.view, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, Look.SPATIAL_DEFAULT, scale)
        }
    }

    fun isEditing() = view != null && grid.editing

    private fun endEdit(keep: Boolean) {
        if (!grid.editing) return
        scaleWidgets(1f)
        deckOverlays?.hideAll()
        grid.editing = false
        editBar.visibility = View.GONE
        main?.onEditingChanged(false)
        (activity as? MainActivity)?.refreshMiniPlayer()
        val previous = backup
        backup = null
        if (!keep && previous != null) {
            layout = previous
            build()
            refreshAll()
            return
        }
        HomeLayout.save(requireContext(), layout, cadran)
        displayProfile(page)
    }

    override fun onAddAt(col: Int, row: Int) {
        val ctx = requireContext()
        // Seulement ce que l'ordinateur actif offre (Jira, GitLab) et ce que ses plugins laissent (Ouvertes, Son, Pavé).
        val types = WidgetType.entries.filter { it.offered(features) }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.home_gallery_title)
            .setView(ScrollView(ctx).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null)
            .create()
        for (type in types) list.addView(galleryRow(type) {
            dialog.dismiss()
            place(type, col, row)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        dialog.showImmersive()
    }

    private fun galleryRow(type: WidgetType, onClick: () -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(ImageView(ctx).apply {
                setImageResource(type.icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setColorFilter(themeColor(ctx, R.attr.c_accent))
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    setText(type.title)
                    textSize = 18f
                    setTextColor(themeColor(ctx, R.attr.c_text))
                })
                addView(TextView(ctx).apply {
                    val sizes = type.sizes.joinToString(", ") { getString(it.label).lowercase() }
                    text = getString(R.string.home_gallery_detail, getString(type.description), sizes)
                    textSize = 14f
                    setTextColor(themeColor(ctx, R.attr.c_text_dim))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            setOnClickListener { onClick() }
        }
    }

    /** Pose le widget à la case touchée si possible, sinon à la première place libre de la page, dans la plus grande taille qui tient. */
    private fun place(type: WidgetType, col: Int, row: Int) {
        val spot = type.sizes.firstNotNullOfOrNull { size ->
            if (col >= 0) {
                val c = col.coerceAtMost(COLS - size.w)
                val r = row.coerceAtMost(ROWS - size.h)
                if (layout.fits(page, c, r, size)) return@firstNotNullOfOrNull Triple(size, c, r)
            }
            layout.freeSpot(page, size)?.let { (c, r) -> Triple(size, c, r) }
        }
        if (spot == null) {
            Toast.makeText(requireContext(), R.string.home_no_room, Toast.LENGTH_LONG).show()
            return
        }
        val placed = Placed(type, spot.first, page, spot.second, spot.third)
        layout.widgets += placed
        val widget = addWidget(placed)
        grid.showPage()
        activate()
        scope.launch { widget.load() }
        // Touches et météo n'affichent rien tant qu'on ne les a pas réglées.
        if (type == WidgetType.KEYS || type == WidgetType.WEATHER) widget.showSettings()
    }

    override fun onRemove(placed: Placed) {
        layout.widgets.remove(placed)
        widgets.remove(placed)?.let {
            it.setActive(false)
            grid.removeView(it.view)
        }
        grid.invalidate()
    }

    override fun pickSize(placed: Placed, left: Float, top: Float, right: Float, bottom: Float) = layout.pickSize(placed, left, top, right, bottom)

    /** Poignée lâchée : le widget prend la taille et la case choisies (sa case d'origine reste couverte). */
    override fun onResize(placed: Placed, fit: Fit) {
        placed.size = fit.size
        placed.col = fit.col
        placed.row = fit.row
        widgets[placed]?.applySize()
        grid.requestLayout()
        grid.invalidate()
    }

    /**
     * Action d'accessibilité de la poignée : taille suivante qui tient, d'abord depuis la même case, sinon étendue vers
     * la gauche ou le haut, en gardant toujours la case d'origine (le widget ne saute pas ailleurs).
     */
    override fun onCycleSize(placed: Placed) {
        val sizes = placed.type.sizes
        val start = sizes.indexOf(placed.size)
        for (step in 1 until sizes.size) {
            val size = sizes[(start + step) % sizes.size]
            val anchors = (placed.row downTo placed.row - size.h + 1).flatMap { r -> (placed.col downTo placed.col - size.w + 1).map { c -> c to r } }
            val spot = anchors.firstOrNull { (c, r) -> layout.fits(placed.page, c, r, size, ignore = placed) }
            if (spot != null) {
                placed.size = size
                placed.col = spot.first
                placed.row = spot.second
                widgets[placed]?.applySize()
                grid.requestLayout()
                grid.invalidate()
                return
            }
        }
        Toast.makeText(requireContext(), R.string.home_no_room_size, Toast.LENGTH_LONG).show()
    }

    override fun onSettings(placed: Placed) {
        widgets[placed]?.showSettings()
    }

    override fun hasSettings(placed: Placed) = widgets[placed]?.hasSettings == true

    override fun canMove(placed: Placed, col: Int, row: Int) = layout.fits(placed.page, col, row, placed.size, ignore = placed)

    override fun onMove(placed: Placed, col: Int, row: Int) {
        placed.col = col
        placed.row = row
        grid.requestLayout()
        grid.invalidate()
    }

    private companion object {
        /** Échelle des widgets en mode Modifier. */
        const val EDIT_SCALE = 0.94f

        /** Aujourd'hui et les 7 jours suivants : « mardi soir » se voit dès aujourd'hui. */
        const val AGENDA_DAYS = 8
    }
}
