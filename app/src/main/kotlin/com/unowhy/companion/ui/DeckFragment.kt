package com.unowhy.companion.ui

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.VolumeKeyHandler
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DeckPage
import com.unowhy.companion.net.DeckTile
import com.unowhy.companion.net.VoiceState
import com.unowhy.companion.ui.mixer.MixerView
import com.unowhy.companion.ui.pointer.PadView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Deck façon Stream Deck : tuiles d'applis et de raccourcis du Mac, son et micro du Mac. */
class DeckFragment : Fragment(), VolumeKeyHandler {

    private lateinit var tabs: TabStrip
    private lateinit var content: View
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private lateinit var access: View
    private lateinit var audio: ComputerAudioView
    private lateinit var appAudio: AppAudioView
    private lateinit var nowPlaying: NowPlayingView
    private lateinit var mail: MailView
    private lateinit var picker: WindowPicker
    private lateinit var openWindows: OpenWindowsView
    private lateinit var mixer: MixerView
    private lateinit var agents: AgentsView
    private lateinit var gitlab: GitLabView
    private lateinit var jira: JiraView
    private lateinit var pad: PadView
    private lateinit var editor: KeyEditor
    private lateinit var adder: AppAdder
    private lateinit var linkAdder: LinkAdder

    private var pages: List<DeckPage> = emptyList()
    private var pageIndex = 0
    /** Onglet « Ouvertes » affiché (toutes les fenêtres de l'ordinateur) à la place de la page `pageIndex`. */
    private var showingOpen = false
    /** Onglet « Son » affiché (console de mixage). */
    private var showingMixer = false
    /** Onglet « Pavé » affiché (la tablette pilote le pointeur de l'ordinateur), proposé seulement pour un Mac ou un Windows. */
    private var showingPad = false
    /** Onglet « Agents » affiché (sessions Claude Code), et proposé seulement si l'ordinateur l'annonce (`/deck/features`). */
    private var showingAgents = false
    private var agentsOn = false
    /** Onglet « GitLab » affiché (MR à suivre et à fusionner), proposé seulement si l'ordinateur a un jeton GitLab (`/deck/features`). */
    private var showingGitLab = false
    private var gitlabOn = false
    /** Onglet « Jira » affiché (tickets de l'utilisateur), et proposé seulement si Jira est configuré sur l'ordinateur. */
    private var showingJira = false
    private var jiraOn = false
    /** Onglet Agents demandé (lumière touchée) avant que l'ordinateur ait dit s'il l'offre. */
    private var pendingAgents = false
    /** Plugins coupés sur cet ordinateur (écran des plugins) : « Ouvertes », « Pavé », « Son » sans onglet. */
    private var off: Set<String> = emptySet()
    /** Onglets dans l'ordre affiché (pour le balayage) et celui qui est sélectionné. */
    private var tabActions: List<() -> Unit> = emptyList()
    private var selectedTab = -1
    /** Clé de l'onglet affiché : en changer fait entrer le contenu du côté du nouvel onglet. */
    private var shownTab: String? = null
    /** Côté d'où entrera le contenu au prochain dessin (0 : rien en attente). */
    private var pendingSlide = 0
    private var slide: List<SpringAnimation> = emptyList()
    /** Un onglet est tenu (appui long) et déplacé dans la barre. */
    private var movingTab = false
    /** Page demandée avant que le deck soit chargé (Réunion, page d'une appli après un appel). */
    private var pendingPage: ((DeckPage) -> Boolean)? = null
    /** Applis en appel (micro utilisé) → dernier moment où on l'a vu : leur page a un onglet. */
    /** Applis à page vues ouvertes (onglet) ou en appel, avec l'heure où on les a vues. */
    private var openApps: Map<String, Long> = emptyMap()
    private var liveApps: Map<String, Long> = emptyMap()
    /** Ouverture de l'ordinateur pas encore lue : on ne sait pas si l'appli tourne. */
    private var openKnown = false
    private var livePoll: Job? = null
    /** Appel en cours dans l'appli de la page affichée (Discord) ; null : pas d'appli, ou état illisible. */
    private var voice: VoiceState? = null
    private var voiceApp: String? = null
    private lateinit var voiceCard: View
    private lateinit var voiceChannel: TextView
    private lateinit var voicePeople: TextView
    private val adapter = TileAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_deck, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        tabs = view.findViewById(R.id.deck_tabs)
        content = view.findViewById(R.id.deck_content)
        grid = view.findViewById(R.id.deck_grid)
        status = view.findViewById(R.id.deck_status)
        audio = view.findViewById(R.id.deck_audio)
        audio.attach(viewLifecycleOwner.lifecycleScope)
        audio.showLabel()
        appAudio = view.findViewById(R.id.deck_app_audio)
        appAudio.attach(viewLifecycleOwner.lifecycleScope)
        voiceCard = view.findViewById(R.id.deck_voice)
        voiceChannel = view.findViewById(R.id.deck_voice_channel)
        voicePeople = view.findViewById(R.id.deck_voice_people)
        nowPlaying = view.findViewById(R.id.deck_now_playing)
        nowPlaying.attach(viewLifecycleOwner.lifecycleScope)
        mail = view.findViewById(R.id.deck_mail)
        mail.attach(viewLifecycleOwner.lifecycleScope)
        picker = view.findViewById(R.id.deck_windows)
        picker.attach(viewLifecycleOwner.lifecycleScope) { loadDeck() }
        openWindows = view.findViewById(R.id.deck_open)
        openWindows.attach(viewLifecycleOwner.lifecycleScope)
        // Dans « Ouvertes », glisser une fenêtre de côté la déplace : le balayage ne part que des espaces entre les écrans.
        swipeTabs(openWindows)
        // Console : glisser de côté la fait défiler, les curseurs se glissent verticalement ; pas de balayage d'onglet.
        mixer = view.findViewById(R.id.deck_mixer)
        mixer.attach(viewLifecycleOwner.lifecycleScope)
        agents = view.findViewById(R.id.deck_agents)
        agents.attach(viewLifecycleOwner)
        swipeTabs(agents)
        gitlab = view.findViewById(R.id.deck_gitlab)
        gitlab.attach(viewLifecycleOwner.lifecycleScope)
        swipeTabs(gitlab)
        jira = view.findViewById(R.id.deck_jira)
        jira.attach(viewLifecycleOwner)
        swipeTabs(jira)
        // Pavé : tout geste est pour le pointeur, jamais un balayage d'onglet (pas de swipeTabs, la vue garde ses touches).
        pad = view.findViewById(R.id.deck_pad)
        editor = view.findViewById(R.id.deck_editor)
        editor.attach(viewLifecycleOwner.lifecycleScope) { loadDeck() }
        adder = view.findViewById(R.id.deck_adder)
        adder.attach(viewLifecycleOwner.lifecycleScope) { loadDeck() }
        linkAdder = view.findViewById(R.id.deck_links)
        linkAdder.attach(viewLifecycleOwner.lifecycleScope) { loadDeck() }

        grid.adapter = adapter
        swipeTabs(grid)
        grid.setOnItemClickListener { _, tileView, position, _ ->
            val tile = adapter.getItem(position)
            when (tile.type) {
                "empty" -> editor.show(slotOf(tile))
                ADD -> if (isMediaPage()) linkAdder.show() else adder.show(currentApps())
                else -> {
                    // Appli qui a sa page (Discord) : on y va, en la lançant si elle est fermée ; sur sa page, la tuile la met devant.
                    val appPage = appPageOf(tile)
                    if (appPage >= 0 && appPage != pageIndex) {
                        if (!isOpen(tile.app)) runTile(tileView, tile)
                        showPage(appPage)
                    } else runTile(tileView, tile)
                }
            }
        }
        // Appui long : page Perso, modifier la touche ; ailleurs, fenêtres d'une appli (en choisir une, la changer d'écran).
        grid.setOnItemLongClickListener { _, tileView, position, _ ->
            val tile = adapter.getItem(position)
            when {
                pages.getOrNull(pageIndex)?.editable == true -> editor.show(slotOf(tile))
                tile.type == "app" -> picker.show(tile, removable = isAppsPage())
                isMediaPage() && tile.type != ADD -> confirmRemoveLink(tile)
                else -> return@setOnItemLongClickListener false
            }
            tileView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
        view.findViewById<ImageButton>(R.id.deck_refresh).setOnClickListener { IconCache.clear(); loadDeck() }
        access = view.findViewById(R.id.deck_access)
        view.findViewById<View>(R.id.deck_access_open).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    client().system.openAccessibility()
                    Toast.makeText(requireContext(), R.string.deck_access_opened, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    toastError(e)
                }
            }
        }

        loadDeck()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        // Le son et les touches perso (outil Claude) ont pu changer depuis le Mac : on relit en revenant sur le deck.
        if (!hidden) loadDeck() else { picker.hide(); editor.hide(); adder.hide(); linkAdder.hide() }
        setLiveViewsActive(!hidden && isResumed)
    }

    override fun onResume() {
        super.onResume()
        setLiveViewsActive(!isHidden)
    }

    override fun onPause() {
        super.onPause()
        setLiveViewsActive(false)
    }

    /** Volume d'une appli et lecture en cours : relus en boucle seulement quand le deck est à l'écran. */
    private fun setLiveViewsActive(active: Boolean) {
        appAudio.setActive(active)
        nowPlaying.setActive(active)
        mail.setActive(active)
        openWindows.setActive(active && showingOpen)
        mixer.setActive(active && showingMixer)
        agents.setActive(active && showingAgents)
        gitlab.setActive(active && showingGitLab)
        jira.setActive(active && showingJira)
        pad.setActive(active && showingPad)
        livePoll?.cancel()
        if (active) livePoll = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refreshLiveApps()
                refreshVoice()
                delay(LIVE_POLL_MS)
            }
        }
    }

    /**
     * L'onglet reste un moment après la fermeture ou la fin du micro : pas de clignotement si Discord le coupe un instant.
     * Page Réunion : peu de temps, l'ordinateur lisse déjà l'appel (Teams sur Mac : 8 s).
     */
    private suspend fun refreshLiveApps() {
        val seen = runCatching { client().deck.liveApps() }.getOrNull() ?: return
        val now = SystemClock.uptimeMillis()
        val before = openApps.keys to liveApps.keys
        openApps = (openApps + seen.open.associateWith { now }).filterValues { now - it < LIVE_HOLD_MS }
        val meetingApps = pages.filter { it.meeting }.mapNotNull { it.app?.lowercase() }.toSet()
        liveApps = (liveApps + seen.live.associateWith { now })
            .filter { (app, at) -> now - at < if (app.lowercase() in meetingApps) MEETING_HOLD_MS else LIVE_HOLD_MS }
        openKnown = true
        // Fin de l'appel sur la page Réunion : elle n'a plus rien à montrer, retour à la première page.
        val page = pages.getOrNull(pageIndex)
        fun live(keys: Set<String>) = keys.any { it.equals(page?.app, ignoreCase = true) }
        if (page?.meeting == true && !showingOpen && !showingPad && live(before.second) && !live(liveApps.keys)) {
            showPage(0)
            return
        }
        if ((openApps.keys to liveApps.keys) != before && pages.isNotEmpty()) renderTabs()
    }

    /** Page d'une appli d'appel : relit l'appel, et refait les touches si on entre ou sort d'un appel. */
    private suspend fun refreshVoice() {
        val app = pages.getOrNull(pageIndex)?.app?.takeUnless { showingOpen || showingPad }
        val state = app?.let { runCatching { client().deck.voice(it) }.getOrNull() }
        if (app != pages.getOrNull(pageIndex)?.app || showingOpen || showingPad) return
        val changed = state?.connected != voice?.connected || state?.streaming != voice?.streaming
        val relit = state?.muted != voice?.muted || state?.deafened != voice?.deafened || state?.cameraOff != voice?.cameraOff
        voice = state
        renderVoice()
        if (changed) showPage(pageIndex) else if (relit) adapter.notifyDataSetChanged()
    }

    private fun renderVoice() {
        val v = voice?.takeIf { it.connected }
        voiceCard.visibility = if (v == null) View.GONE else View.VISIBLE
        if (v == null) return
        voiceChannel.text = getString(R.string.deck_voice_channel, v.channel ?: pages.getOrNull(pageIndex)?.app.orEmpty())
        val parts = listOfNotNull(
            v.people.takeIf { it.isNotEmpty() }?.let { getString(R.string.deck_voice_with, it.joinToString(", ")) },
            getString(R.string.deck_voice_mic_off).takeIf { v.muted == true },
            getString(R.string.deck_voice_deafened).takeIf { v.deafened == true },
        )
        voicePeople.text = parts.joinToString(" · ")
        // Place gardée même vide : sinon les touches sautent quand « Ton micro est coupé » apparaît.
        voicePeople.visibility = if (parts.isEmpty()) View.INVISIBLE else View.VISIBLE
        voicePeople.setTextColor(themeColor(requireContext(), if (v.muted == true || v.deafened == true) R.attr.c_ko else R.attr.c_text_dim))
    }

    /**
     * Touche à montrer selon l'appel : `call` seulement en appel, `idle` hors appel, `streaming` pendant un partage
     * d'écran, `share` en appel sans partage (« Partager », remplacé par « Arrêter le partage »), `lobby` sur l'écran
     * d'avant-réunion de Teams (« Rejoindre maintenant »).
     */
    private fun shownNow(tile: DeckTile) = when (tile.whenState) {
        "call" -> voice?.connected == true
        "streaming" -> voice?.streaming == true
        "share" -> voice?.connected == true && voice?.streaming != true
        "lobby" -> voice?.lobby == true
        "idle" -> voice?.connected != true
        else -> true
    }

    private fun isLit(tile: DeckTile) = voice?.connected == true && when (tile.lit) {
        // En sourdine, Discord coupe aussi le micro (il revient avec le casque, comme il était avant).
        "muted" -> voice?.muted == true || voice?.deafened == true
        "deafened" -> voice?.deafened == true
        "camera" -> voice?.cameraOff == true
        else -> false
    }

    /** Fermée = on la lance en ouvrant sa page ; dans le doute (pas encore lu), on la lance aussi. */
    private fun isOpen(app: String?) = openKnown && app != null && openApps.keys.any { it.equals(app, ignoreCase = true) }

    override fun onVolumeKey(up: Boolean): Boolean {
        audio.nudge(up)
        return true
    }

    private fun client() = BackendClient(requireContext())

    private fun loadDeck() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                pages = client().deck.pages()
                pendingPage?.let { wanted ->
                    pendingPage = null
                    pages.indexOfFirst(wanted).takeIf { it >= 0 }?.let { pageIndex = it }
                }
                pageIndex = pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                status.visibility = View.GONE
                val features = runCatching { client().deck.homeFeatures() }.getOrNull()
                agentsOn = features?.agents ?: false
                gitlabOn = features?.gitlab ?: false
                off = features?.off.orEmpty()
                jiraOn = features?.jira == true
                when {
                    showingOpen && OPEN_TAB !in off -> showOpen()
                    showingPad && padOn() -> showPad()
                    (showingAgents || pendingAgents) && agentsOn -> showAgents()
                    showingGitLab && gitlabOn -> showGitLab()
                    showingJira && jiraOn -> showJira()
                    else -> showPage(pageIndex)
                }
            } catch (e: Exception) {
                status.text = errorText(e)
                status.visibility = View.VISIBLE
            }
        }
        audio.refresh()
        // Relu à chaque retour sur le deck : le bandeau disparaît dès que l'autorisation est donnée sur le Mac.
        viewLifecycleOwner.lifecycleScope.launch {
            val missing = runCatching { client().pairing.identity().accessibility == false }.getOrDefault(false)
            access.visibility = if (missing) View.VISIBLE else View.GONE
        }
    }

    /** Après « Rejoindre » sur l'Accueil : page Réunion (micro, caméra, raccrocher). */
    fun openMeetingPage() = openPage { it.meeting }

    /** Petit lecteur touché : page Média. */
    fun openMediaPage() = openPage { it.media }

    /** Après avoir répondu à un appel : page de l'appli (micro, sourdine, Quitter l'appel), Réunion pour Teams. */
    fun openAppPage(app: String) {
        val ofApp = { page: DeckPage -> page.app.equals(app, ignoreCase = true) }
        openPage { ofApp(it) && (!it.meeting || pages.none { other -> ofApp(other) && !other.meeting }) }
    }

    private fun openPage(wanted: (DeckPage) -> Boolean) {
        val index = pages.indexOfFirst(wanted)
        if (index >= 0) showPage(index) else pendingPage = wanted
    }

    /**
     * Onglets : les pages sans appli, puis celles des applis ouvertes sur l'ordinateur (Discord) ou en appel (Réunion :
     * seulement pendant l'appel, Teams restant ouvert toute la journée). Sinon la page d'une appli n'a pas d'onglet :
     * on l'ouvre en touchant l'appli, et une pastille à son nom s'ajoute au bout tant qu'elle est affichée.
     */
    private class Tab(val key: String, val title: String, val selected: Boolean, val onClick: () -> Unit)

    /** Les onglets à afficher, dans l'ordre choisi. Aucune vue créée ici. */
    private fun computeTabs(): List<Tab> {
        fun seen(map: Map<String, Long>, app: String) = map.keys.any { it.equals(app, ignoreCase = true) }
        val live = pages.indices.filter { i ->
            val app = pages[i].app ?: return@filter false
            seen(liveApps, app) || (!pages[i].meeting && seen(openApps, app))
        }
        val shown = pages.indices.filter { pages[it].app == null } + live +
            listOf(pageIndex).filter { pages.getOrNull(it)?.app != null && it !in live }
        val entries = mutableListOf<Tab>()
        fun openTab() = Tab(OPEN_TAB, getString(R.string.deck_open_tab), showingOpen, ::showOpen)
        fun mixerTab() = Tab(MIXER_TAB, getString(R.string.deck_mixer_tab), showingMixer, ::showMixer)
        fun padTab() = Tab(PAD_TAB, getString(R.string.deck_pad_tab), showingPad, ::showPad)
        fun agentsTab() = Tab(AGENTS_TAB, getString(R.string.deck_agents_tab), showingAgents, ::showAgents)
        fun gitlabTab() = Tab(GITLAB_TAB, getString(R.string.deck_gitlab_tab), showingGitLab, ::showGitLab)
        fun jiraTab() = Tab(JIRA_TAB, getString(R.string.deck_jira_tab), showingJira, ::showJira)
        // « Ouvertes », « Pavé » (Mac ou Windows), « Son », puis « Agents », « GitLab » et « Jira » (si l'ordinateur les offre) : ensemble posé après Apps.
        fun extras() = listOfNotNull(
            openTab().takeIf { OPEN_TAB !in off },
            padTab().takeIf { padOn() },
            mixerTab().takeIf { MIXER_TAB !in off },
            agentsTab().takeIf { agentsOn },
            gitlabTab().takeIf { gitlabOn },
            jiraTab().takeIf { jiraOn },
        )
        // Par défaut, « Ouvertes » juste après Apps (en premier sans page Apps) : toutes les applis ouvertes, pas seulement les choisies.
        val openAfter = shown.indexOfFirst { pages[it].apps }
        // « Son » juste après.
        if (openAfter < 0) entries += extras()
        shown.forEachIndexed { i, index ->
            entries += Tab(PAGE_TAB + pages[index].title, pages[index].title, !showingOpen && !showingPad && !showingMixer && !showingAgents && !showingGitLab && !showingJira && index == pageIndex) { showPage(index) }
            if (i == openAfter) entries += extras()
        }
        // Ordre choisi en déplaçant les onglets ; un onglet jamais placé (page d'appli qui apparaît) garde sa place relative, à la fin.
        val order = Prefs.deckTabOrder(requireContext())
        return entries.withIndex().sortedBy { (i, t) -> order.indexOf(t.key).takeIf { it >= 0 } ?: (order.size + i) }.map { it.value }
    }

    private fun renderTabs() {
        // Un onglet est en train d'être déplacé : la barre sera refaite au lâcher.
        if (movingTab) return
        val sorted = computeTabs()
        // Même ensemble d'onglets (simple changement de sélection) : on met à jour l'état sélectionné sans
        // removeAllViews ni ré-inflation, qui provoquaient une mise en page complète à chaque toucher d'onglet.
        val sameTabs = tabs.childCount == sorted.size &&
            sorted.withIndex().all { (i, t) -> tabs.getChildAt(i).tag == t.key }
        if (sameTabs) {
            applyTabSelection(sorted)
            return
        }
        tabs.removeAllViews()
        val actions = mutableListOf<() -> Unit>()
        selectedTab = -1
        sorted.forEach { t ->
            val tab = Button(requireContext(), null, 0, R.style.Chip_Tab).apply {
                text = t.title
                tag = t.key
                isSelected = t.selected
                setOnClickListener { t.onClick() }
            }
            moveOnLongPress(tab)
            tabs.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) })
            if (t.selected) selectedTab = actions.size
            actions += t.onClick
        }
        tabActions = actions
        scrollToSelectedTab()
        followSelection(sorted)
    }

    /** Ensemble d'onglets inchangé : met à jour l'état sélectionné et les actions sur les boutons déjà en place. */
    private fun applyTabSelection(sorted: List<Tab>) {
        val actions = mutableListOf<() -> Unit>()
        selectedTab = -1
        sorted.forEachIndexed { i, t ->
            val tab = tabs.getChildAt(i)
            if (tab.isSelected != t.selected) tab.isSelected = t.selected
            tab.setOnClickListener { t.onClick() }
            if (t.selected) selectedTab = i
            actions += t.onClick
        }
        tabActions = actions
        scrollToSelectedTab()
        followSelection(sorted)
    }

    /**
     * Nouvel onglet : la pastille glisse jusqu'à lui et le contenu entre de son côté (axe partagé). Les vues de l'onglet
     * ne sont rendues visibles qu'après renderTabs : le glissement part juste avant le dessin suivant.
     */
    private fun followSelection(sorted: List<Tab>) {
        tabs.select(selectedTab)
        val key = sorted.getOrNull(selectedTab)?.key
        if (key == shownTab) return
        val direction = TabMotion.direction(sorted.indexOfFirst { it.key == shownTab }, selectedTab)
        shownTab = key
        Look.settle(slide, pageViews())
        slide = emptyList()
        if (direction == 0 || !isResumed || isHidden) return
        if (pendingSlide == 0) {
            content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    slide = Look.slideIn(pageViews(), pendingSlide)
                    pendingSlide = 0
                    return true
                }
            })
        }
        pendingSlide = direction
    }

    /** Ce qui change d'un onglet à l'autre, sous la rangée d'onglets. */
    private fun pageViews() = listOf(audio, appAudio, voiceCard, nowPlaying, mail, grid, openWindows, mixer, agents, gitlab, jira, pad, status)

    /** Onglet choisi par balayage : on fait défiler la barre jusqu'à lui. */
    private fun scrollToSelectedTab() {
        tabs.getChildAt(selectedTab)?.let { tab ->
            tabs.post { (tabs.parent as HorizontalScrollView).smoothScrollTo((tab.left - dp(48)).coerceAtLeast(0), 0) }
        }
    }

    /**
     * Appui long sur un onglet : il se soulève et suit le doigt dans la barre ; les autres se poussent à son passage.
     * Lâché, l'ordre est gardé pour cet ordinateur. La barre ne défile pas pendant ce temps.
     */
    private fun moveOnLongPress(tab: View) {
        var lastRawX = 0f
        var grabRawX = 0f
        var from = 0
        var to = 0
        var step = 0
        tab.setOnLongClickListener { v ->
            movingTab = true
            grabRawX = lastRawX
            from = tabs.indexOfChild(v)
            to = from
            step = v.width + dp(8)
            v.isPressed = false
            v.parent.requestDisallowInterceptTouchEvent(true)
            v.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.9f).setDuration(120).start()
            v.elevation = dp(8).toFloat()
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
        // On ne retire pas l'onglet de la barre pendant le geste (Android l'annulerait) : il suit le doigt et les autres
        // s'écartent ; le nouvel ordre n'est appliqué qu'au lâcher.
        tab.setOnTouchListener { v, e ->
            lastRawX = e.rawX
            if (!movingTab) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    // Bloqué aux bords de la partie visible de la barre : à gauche le début, à droite juste avant le bouton ⟳
                    // (sinon il passe dessous ou sort de l'écran).
                    val bar = tabs.parent as HorizontalScrollView
                    val minLeft = maxOf(0, bar.scrollX)
                    val maxRight = bar.scrollX + bar.width
                    v.translationX = (e.rawX - grabRawX).coerceIn((minLeft - v.left).toFloat(), (maxRight - v.right).toFloat().coerceAtLeast((minLeft - v.left).toFloat()))
                    val center = v.left + v.translationX + v.width / 2f
                    to = (0 until tabs.childCount).count { i ->
                        val other = tabs.getChildAt(i)
                        other != v && other.left + other.width / 2f < center
                    }
                    for (i in 0 until tabs.childCount) {
                        val other = tabs.getChildAt(i)
                        if (other == v) continue
                        val shift = when {
                            from < to && i in from + 1..to -> -step
                            from > to && i in to until from -> step
                            else -> 0
                        }.toFloat()
                        if (other.getTag(R.id.deck_tabs) != shift) {
                            other.setTag(R.id.deck_tabs, shift)
                            other.animate().translationX(shift).setDuration(120).start()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val keys = (0 until tabs.childCount).map { tabs.getChildAt(it).tag as String }.toMutableList()
                    keys.add(to, keys.removeAt(from))
                    Prefs.setDeckTabOrder(requireContext(), keys)
                    movingTab = false
                    v.isPressed = false
                    v.elevation = 0f
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
                    renderTabs()
                }
            }
            true
        }
    }

    /**
     * Balayer la grille de côté passe à l'onglet voisin. Le geste est retiré à la grille dès qu'il part de côté,
     * pour qu'elle n'ouvre pas la tuile ni son appui long ; vers le haut ou le bas, elle défile comme avant.
     */
    private fun swipeTabs(view: View) {
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop * 2
        var downX = 0f
        var downY = 0f
        var swiping = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swiping = false }
                MotionEvent.ACTION_MOVE -> if (!swiping) {
                    val dx = e.rawX - downX
                    if (abs(dx) > slop && abs(dx) > abs(e.rawY - downY) * 1.5f) {
                        swiping = true
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        v.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                MotionEvent.ACTION_UP -> if (swiping) {
                    val dx = e.rawX - downX
                    if (abs(dx) > dp(SWIPE_DP)) tabActions.getOrNull(selectedTab + if (dx < 0) 1 else -1)?.invoke()
                }
            }
            swiping
        }
    }

    /** Onglet « Ouvertes » : les touches et les barres de son laissent la place au schéma des écrans. */
    private fun showOpen() {
        hideMixer()
        hideAgents()
        hideGitLab()
        hideJira()
        hidePad()
        showingOpen = true
        grid.visibility = View.GONE
        // Les instructions sont dans la bande du bas d'« Ouvertes », qui devient la corbeille pendant un glisser.
        status.visibility = View.GONE
        renderTabs()
        audio.visibility = View.GONE
        voiceCard.visibility = View.GONE
        appAudio.bind(null)
        nowPlaying.bind(false)
        mail.bind(false)
        openWindows.setActive(isResumed && !isHidden)
    }

    /** Le pavé ne se propose que pour un ordinateur qu'on peut piloter (Mac, Windows), s'il n'a pas été coupé. */
    private fun padOn() = Computers.active(requireContext())?.os in setOf("mac", "windows") && PAD_TAB !in off

    /** Onglet « Pavé » : la surface tactile prend la place des touches et des barres de son. */
    private fun showPad() {
        if (showingOpen) {
            showingOpen = false
            openWindows.setActive(false)
        }
        hideMixer()
        hideAgents()
        hideGitLab()
        hideJira()
        showingPad = true
        grid.visibility = View.GONE
        status.visibility = View.GONE
        renderTabs()
        audio.visibility = View.GONE
        voiceCard.visibility = View.GONE
        appAudio.bind(null)
        nowPlaying.bind(false)
        mail.bind(false)
        pad.setActive(isResumed && !isHidden)
    }

    private fun hidePad() {
        if (!showingPad) return
        showingPad = false
        pad.setActive(false)
    }

    /** Onglet « Son » : la console de mixage prend la place des touches et des barres de son. */
    private fun showMixer() {
        if (showingOpen) {
            showingOpen = false
            openWindows.setActive(false)
        }
        hideAgents()
        hideGitLab()
        hideJira()
        hidePad()
        showingMixer = true
        grid.visibility = View.GONE
        status.visibility = View.GONE
        renderTabs()
        audio.visibility = View.GONE
        voiceCard.visibility = View.GONE
        appAudio.bind(null)
        nowPlaying.bind(false)
        mail.bind(false)
        mixer.visibility = View.VISIBLE
        mixer.setActive(isResumed && !isHidden)
    }

    private fun hideMixer() {
        if (!showingMixer) return
        showingMixer = false
        mixer.setActive(false)
        mixer.visibility = View.GONE
    }

    /** Onglet « Agents » : les sessions Claude Code à la place des touches et des barres de son. */
    private fun showAgents() {
        if (showingOpen) {
            showingOpen = false
            openWindows.setActive(false)
        }
        hideMixer()
        hideJira()
        hidePad()
        hideGitLab()
        showingAgents = true
        pendingAgents = false
        grid.visibility = View.GONE
        status.visibility = View.GONE
        renderTabs()
        audio.visibility = View.GONE
        voiceCard.visibility = View.GONE
        appAudio.bind(null)
        nowPlaying.bind(false)
        mail.bind(false)
        agents.setActive(isResumed && !isHidden)
    }

    private fun hideAgents() {
        if (!showingAgents) return
        showingAgents = false
        agents.setActive(false)
    }

    /** Onglet « GitLab » : les MR à la place des touches et des barres de son. */
    private fun showGitLab() {
        if (showingOpen) {
            showingOpen = false
            openWindows.setActive(false)
        }
        hideMixer()
        hideAgents()
        hideJira()
        hidePad()
        showingGitLab = true
        grid.visibility = View.GONE
        status.visibility = View.GONE
        renderTabs()
        audio.visibility = View.GONE
        voiceCard.visibility = View.GONE
        appAudio.bind(null)
        nowPlaying.bind(false)
        gitlab.setActive(isResumed && !isHidden)
    }

    private fun hideGitLab() {
        if (!showingGitLab) return
        showingGitLab = false
        gitlab.setActive(false)
    }

    /** Onglet « Jira » : les tickets de l'utilisateur à la place des touches et des barres de son. */
    private fun showJira() {
        if (showingOpen) {
            showingOpen = false
            openWindows.setActive(false)
        }
        hideMixer()
        hideAgents()
        hideGitLab()
        hidePad()
        showingJira = true
        grid.visibility = View.GONE
        status.visibility = View.GONE
        renderTabs()
        audio.visibility = View.GONE
        voiceCard.visibility = View.GONE
        appAudio.bind(null)
        nowPlaying.bind(false)
        jira.setActive(isResumed && !isHidden)
    }

    private fun hideJira() {
        if (!showingJira) return
        showingJira = false
        jira.setActive(false)
    }

    /** Lumière touchée (ou widget) : l'onglet Agents, dès que l'ordinateur a dit qu'il l'offre. */
    fun openAgentsTab() {
        if (agentsOn) showAgents() else pendingAgents = true
    }

    /** Page de l'appli d'une tuile (Discord, Teams), -1 si elle n'en a pas. */
    private fun appPageOf(tile: DeckTile) =
        if (tile.type != "app") -1 else pages.indexOfFirst { it.app != null && it.app.equals(tile.app, ignoreCase = true) }

    private fun showPage(index: Int) {
        pageIndex = index
        if (showingOpen) {
            showingOpen = false
            openWindows.setActive(false)
        }
        hideMixer()
        hideAgents()
        hideGitLab()
        hideJira()
        hidePad()
        grid.visibility = View.VISIBLE
        val page = pages.getOrNull(index)
        // Pages Apps et Média : tuile « Ajouter » en dernier (applis de l'ordinateur, sites).
        // Autre appli (ou page sans appli) : l'appel lu ne vaut plus ; celui de la nouvelle appli est lu tout de suite.
        if (page?.app != voiceApp) {
            voice = null
            voiceApp = page?.app
            if (page?.app != null) viewLifecycleOwner.lifecycleScope.launch { refreshVoice() }
        }
        renderVoice()
        adapter.tiles = page?.tiles.orEmpty().filter(::shownNow) +
            listOfNotNull(DeckTile(ADD, getString(R.string.apps_add_tile), ADD, null, null).takeIf { page?.apps == true || page?.media == true })
        if (page?.editable == true || page?.apps == true || (page?.media == true && page.tiles.isNotEmpty())) {
            status.setText(when {
                page.apps -> R.string.deck_apps_hint
                page.media -> R.string.deck_media_hint
                else -> R.string.deck_edit_hint
            })
            status.visibility = View.VISIBLE
        } else if (pages.isNotEmpty()) {
            status.visibility = View.GONE
        }
        renderTabs()
        // Son de l'ordinateur : utile pour un média ou une appli (Discord), pas pour des raccourcis ; `sound: false` (Word,
        // Excel, PowerPoint, Outlook) le retire, et le volume propre à l'appli avec.
        val sound = page?.sound != false
        audio.visibility = if (sound && (page?.media == true || page?.app != null)) View.VISIBLE else View.GONE
        appAudio.bind(page?.app.takeIf { sound })
        nowPlaying.bind(page?.media == true)
        mail.bind(page?.mail == true)
        (activity as? MainActivity)?.refreshMiniPlayer()
    }

    private fun runTile(tileView: View, tile: DeckTile) {
        tileView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = client().deck.runTile(tile.id)
                result.windows?.let { picker.show(tile, it, removable = isAppsPage()) }
                result.openedApp?.let { Toast.makeText(requireContext(), getString(R.string.media_opened_app, it), Toast.LENGTH_SHORT).show() }
                // Un raccourci comme "Micro Teams" peut avoir changé l'état du son.
                if (tile.type != "app") audio.refresh()
                // « Quitter l'appel », micro… : l'état de l'appel change, on le relit sans attendre la boucle.
                if (pages.getOrNull(pageIndex)?.app != null) { delay(VOICE_AFTER_MS); refreshVoice() }
            } catch (e: Exception) {
                toastError(e)
            }
        }
    }

    private inner class TileAdapter : BaseAdapter() {
        var tiles: List<DeckTile> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        override fun getCount() = tiles.size
        override fun getItem(position: Int) = tiles[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_tile, parent, false)
            val tile = tiles[position]
            val icon = view.findViewById<ImageView>(R.id.tile_icon)
            val initial = view.findViewById<TextView>(R.id.tile_initial)
            view.findViewById<TextView>(R.id.tile_label).text = tile.label
            view.tag = tile.id

            // Fond remis à chaque fois : la vue a pu servir à un emplacement vide (pointillés).
            view.setBackgroundResource(if (tile.type == "empty" || tile.type == ADD) R.drawable.tile_empty_bg else R.drawable.tile_bg)
            val custom = tile.color?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            // setBackgroundResource ne fait rien si c'est déjà le même fond : la couleur d'une tuile d'une autre page
            // (Micro rouge, Décrocher vert) resterait sur la vue recyclée, il faut l'effacer.
            // Micro coupé, casque en sourdine : allumée du rouge de Muet (onglet Son), pour le voir d'un coup d'œil.
            val lit = isLit(tile)
            val tint = if (lit) themeColor(requireContext(), R.attr.c_ko) else custom
            if (tint != null) view.background.mutate().setTint(tint) else view.background.setTintList(null)
            view.findViewById<TextView>(R.id.tile_label).setTextColor(
                when {
                    lit -> themeColor(requireContext(), R.attr.c_on_accent)
                    custom != null -> Look.readableOn(requireContext(), custom)
                    else -> themeColor(requireContext(), R.attr.c_text)
                },
            )

            if (tile.type == "empty" || tile.type == ADD) {
                if (tile.type == "empty") view.findViewById<TextView>(R.id.tile_label).setText(R.string.key_empty_label)
                initial.text = "+"
                Look.plainInitial(initial, R.attr.c_accent)
                initial.visibility = View.VISIBLE
                icon.setImageBitmap(null)
                return view
            }

            Look.tintInitial(initial, tile.label)
            if (tile.glyph != null) {
                initial.text = tile.glyph
                initial.visibility = View.VISIBLE
                icon.setImageBitmap(null)
                return view
            }
            initial.text = tile.label.take(1).uppercase()
            val cached = IconCache.peek(tile.id)
            icon.setImageBitmap(cached)
            initial.visibility = if (cached != null) View.GONE else View.VISIBLE
            if (cached == null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = IconCache.load(client(), tile.id)
                    // La vue a pu être recyclée pour une autre tuile pendant le chargement.
                    if (bitmap != null && view.tag == tile.id) {
                        icon.setImageBitmap(bitmap)
                        initial.visibility = View.GONE
                    }
                }
            }
            return view
        }
    }

    private fun isAppsPage() = pages.getOrNull(pageIndex)?.apps == true

    private fun isMediaPage() = pages.getOrNull(pageIndex)?.media == true

    /** Page Média affichée (pas un onglet à part comme Ouvertes ou Son) : le petit lecteur se cache. */
    fun isMediaPageShown() = isMediaPage() && nowPlaying.visibility == View.VISIBLE

    private fun confirmRemoveLink(tile: DeckTile) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.links_remove_confirm, tile.label))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apps_remove) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        client().deck.removeLink(tile.id)
                        IconCache.forget(tile.id)
                        loadDeck()
                    } catch (e: Exception) {
                        toastError(e)
                    }
                }
            }
            .showImmersive()
    }

    /** Applis de la page Apps : nom → identifiant de tuile. */
    private fun currentApps() = pages.firstOrNull { it.apps }?.tiles.orEmpty()
        .mapNotNull { t -> t.app?.let { it to t.id } }.toMap()

    /** Numéro d'emplacement d'une touche perso (tuile "k-<slot>"). */
    private fun slotOf(tile: DeckTile) = tile.id.removePrefix("k-").toInt()

    private companion object {
        /** Tuile « Ajouter » des pages Apps et Média (créée sur la tablette, inconnue de l'ordinateur). */
        const val ADD = "add"
        const val LIVE_POLL_MS = 4_000L
        const val LIVE_HOLD_MS = 20_000L
        const val MEETING_HOLD_MS = 5_000L
        /** Clés des onglets (ordre gardé dans Prefs) : « Ouvertes », et une page par son titre ; aussi l'id du plugin (`/plugins`). */
        const val OPEN_TAB = "open"
        const val MIXER_TAB = "mixer"
        const val AGENTS_TAB = "agents"
        const val GITLAB_TAB = "gitlab"
        const val JIRA_TAB = "jira"
        const val PAD_TAB = "pad"
        const val PAGE_TAB = "page:"
        /** Distance de côté qui change d'onglet. */
        const val SWIPE_DP = 80
        /** Laisser l'appli réagir à une touche avant de relire l'appel. */
        const val VOICE_AFTER_MS = 700L
    }
}
