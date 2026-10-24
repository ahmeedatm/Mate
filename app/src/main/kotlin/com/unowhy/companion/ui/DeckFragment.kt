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
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.VolumeKeyHandler
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.VoiceState
import com.unowhy.companion.net.DeckPage
import com.unowhy.companion.net.DeckTile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Deck façon Stream Deck : tuiles d'applis et de raccourcis du Mac, son et micro du Mac. */
class DeckFragment : Fragment(), VolumeKeyHandler {

    private lateinit var tabs: LinearLayout
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private lateinit var access: View
    private lateinit var audio: MacAudioView
    private lateinit var appAudio: AppAudioView
    private lateinit var nowPlaying: NowPlayingView
    private lateinit var picker: WindowPicker
    private lateinit var openWindows: OpenWindowsView
    private lateinit var editor: KeyEditor
    private lateinit var adder: AppAdder
    private lateinit var linkAdder: LinkAdder

    private var pages: List<DeckPage> = emptyList()
    private var pageIndex = 0
    /** Onglet « Ouvertes » affiché (toutes les fenêtres de l'ordinateur) à la place de la page `pageIndex`. */
    private var showingOpen = false
    /** Onglets dans l'ordre affiché (pour le balayage) et celui qui est sélectionné. */
    private var tabActions: List<() -> Unit> = emptyList()
    private var selectedTab = -1
    /** Un onglet est tenu (appui long) et déplacé dans la barre. */
    private var movingTab = false
    /** Page Réunion demandée avant que le deck soit chargé. */
    private var meetingPending = false
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
        picker = view.findViewById(R.id.deck_windows)
        picker.attach(viewLifecycleOwner.lifecycleScope) { loadDeck() }
        openWindows = view.findViewById(R.id.deck_open)
        openWindows.attach(viewLifecycleOwner.lifecycleScope)
        // Dans « Ouvertes », glisser une fenêtre de côté la déplace : le balayage ne part que des espaces entre les écrans.
        swipeTabs(openWindows)
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
                    client().openAccessibility()
                    Toast.makeText(requireContext(), R.string.deck_access_opened, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), requireContext().errorText(e), Toast.LENGTH_LONG).show()
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
        openWindows.setActive(active && showingOpen)
        livePoll?.cancel()
        if (active) livePoll = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refreshLiveApps()
                refreshVoice()
                delay(LIVE_POLL_MS)
            }
        }
    }

    /** L'onglet reste un moment après la fermeture ou la fin du micro : pas de clignotement si Discord le coupe un instant. */
    private suspend fun refreshLiveApps() {
        val seen = runCatching { client().liveApps() }.getOrNull() ?: return
        val now = SystemClock.uptimeMillis()
        val before = openApps.keys to liveApps.keys
        openApps = (openApps + seen.open.associateWith { now }).filterValues { now - it < LIVE_HOLD_MS }
        liveApps = (liveApps + seen.live.associateWith { now }).filterValues { now - it < LIVE_HOLD_MS }
        openKnown = true
        if ((openApps.keys to liveApps.keys) != before && pages.isNotEmpty()) renderTabs()
    }

    /** Page d'une appli d'appel : relit l'appel, et refait les touches si on entre ou sort d'un appel. */
    private suspend fun refreshVoice() {
        val app = pages.getOrNull(pageIndex)?.app?.takeUnless { showingOpen }
        val state = app?.let { runCatching { client().voice(it) }.getOrNull() }
        if (app != pages.getOrNull(pageIndex)?.app || showingOpen) return
        val changed = state?.connected != voice?.connected
        voice = state
        renderVoice()
        if (changed) showPage(pageIndex)
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
        voicePeople.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        voicePeople.setTextColor(themeColor(requireContext(), if (v.muted == true || v.deafened == true) R.attr.c_ko else R.attr.c_text_dim))
    }

    /** Touche à montrer selon l'appel : `call` seulement en appel, `idle` seulement hors appel ; les autres toujours. */
    private fun shownNow(tile: DeckTile) = when (tile.whenState) {
        "call" -> voice?.connected == true
        "idle" -> voice?.connected != true
        else -> true
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
                pages = client().deck()
                if (meetingPending) {
                    meetingPending = false
                    pages.indexOfFirst { it.meeting }.takeIf { it >= 0 }?.let { pageIndex = it }
                }
                pageIndex = pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                status.visibility = View.GONE
                if (showingOpen) showOpen() else showPage(pageIndex)
            } catch (e: Exception) {
                status.text = requireContext().errorText(e)
                status.visibility = View.VISIBLE
            }
        }
        audio.refresh()
        // Relu à chaque retour sur le deck : le bandeau disparaît dès que l'autorisation est donnée sur le Mac.
        viewLifecycleOwner.lifecycleScope.launch {
            val missing = runCatching { client().identity().accessibility == false }.getOrDefault(false)
            access.visibility = if (missing) View.VISIBLE else View.GONE
        }
    }

    /** Après « Rejoindre » sur l'Accueil : page Réunion (micro, caméra, raccrocher). */
    fun openMeetingPage() {
        val index = pages.indexOfFirst { it.meeting }
        if (index >= 0) showPage(index) else meetingPending = true
    }

    /**
     * Onglets : les pages sans appli, puis celles des applis ouvertes sur l'ordinateur (Discord) ou en appel (Réunion :
     * seulement pendant l'appel, Teams restant ouvert toute la journée). Sinon la page d'une appli n'a pas d'onglet :
     * on l'ouvre en touchant l'appli, et une pastille à son nom s'ajoute au bout tant qu'elle est affichée.
     */
    private fun renderTabs() {
        // Un onglet est en train d'être déplacé : la barre sera refaite au lâcher.
        if (movingTab) return
        tabs.removeAllViews()
        val actions = mutableListOf<() -> Unit>()
        selectedTab = -1
        fun seen(map: Map<String, Long>, app: String) = map.keys.any { it.equals(app, ignoreCase = true) }
        val live = pages.indices.filter { i ->
            val app = pages[i].app ?: return@filter false
            seen(liveApps, app) || (!pages[i].meeting && seen(openApps, app))
        }
        val shown = pages.indices.filter { pages[it].app == null } + live +
            listOf(pageIndex).filter { pages.getOrNull(it)?.app != null && it !in live }
        class Tab(val key: String, val title: String, val selected: Boolean, val onClick: () -> Unit)
        val entries = mutableListOf<Tab>()
        fun openTab() = Tab(OPEN_TAB, getString(R.string.deck_open_tab), showingOpen, ::showOpen)
        // Par défaut, « Ouvertes » juste après Apps (en premier sans page Apps) : toutes les applis ouvertes, pas seulement les choisies.
        val openAfter = shown.indexOfFirst { pages[it].apps }
        if (openAfter < 0) entries += openTab()
        shown.forEachIndexed { i, index ->
            entries += Tab(PAGE_TAB + pages[index].title, pages[index].title, !showingOpen && index == pageIndex) { showPage(index) }
            if (i == openAfter) entries += openTab()
        }
        // Ordre choisi en déplaçant les onglets ; un onglet jamais placé (page d'appli qui apparaît) garde sa place relative, à la fin.
        val order = Prefs.deckTabOrder(requireContext())
        val sorted = entries.withIndex().sortedBy { (i, t) -> order.indexOf(t.key).takeIf { it >= 0 } ?: (order.size + i) }.map { it.value }
        sorted.forEach { t ->
            val tab = Button(requireContext(), null, 0, R.style.Chip).apply {
                text = t.title
                tag = t.key
                isSelected = t.selected
                setOnClickListener { t.onClick() }
            }
            moveOnLongPress(tab)
            tabs.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) })
            if (t.selected) selectedTab = actions.size
            actions += t.onClick
        }
        tabActions = actions
        // Onglet choisi par balayage : on fait défiler la barre jusqu'à lui.
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
        showingOpen = true
        grid.visibility = View.GONE
        // Les instructions sont dans la bande du bas d'« Ouvertes », qui devient la corbeille pendant un glisser.
        status.visibility = View.GONE
        renderTabs()
        audio.visibility = View.GONE
        voiceCard.visibility = View.GONE
        appAudio.bind(null)
        nowPlaying.bind(false)
        openWindows.setActive(isResumed && !isHidden)
    }

    /** Page de l'appli d'une tuile (Discord, Teams), -1 si elle n'en a pas. */
    private fun appPageOf(tile: DeckTile) =
        if (tile.type != "app") -1 else pages.indexOfFirst { it.app != null && it.app.equals(tile.app, ignoreCase = true) }

    private fun showPage(index: Int) {
        pageIndex = index
        if (showingOpen) {
            showingOpen = false
            openWindows.setActive(false)
            grid.visibility = View.VISIBLE
        }
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
        // Son de l'ordinateur : utile pour un média ou une appli (Discord), pas pour des raccourcis.
        audio.visibility = if (page?.media == true || page?.app != null) View.VISIBLE else View.GONE
        appAudio.bind(page?.app)
        nowPlaying.bind(page?.media == true)
    }

    private fun runTile(tileView: View, tile: DeckTile) {
        tileView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = client().runTile(tile.id)
                result.windows?.let { picker.show(tile, it, removable = isAppsPage()) }
                result.openedApp?.let { Toast.makeText(requireContext(), getString(R.string.media_opened_app, it), Toast.LENGTH_SHORT).show() }
                // Un raccourci comme "Micro Teams" peut avoir changé l'état du son.
                if (tile.type != "app") audio.refresh()
                // « Quitter l'appel », micro… : l'état de l'appel change, on le relit sans attendre la boucle.
                if (pages.getOrNull(pageIndex)?.app != null) { delay(VOICE_AFTER_MS); refreshVoice() }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), requireContext().errorText(e), Toast.LENGTH_LONG).show()
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
            if (custom != null) view.background.mutate().setTint(custom) else view.background.setTintList(null)

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

    private fun confirmRemoveLink(tile: DeckTile) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.links_remove_confirm, tile.label))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apps_remove) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        client().removeDeckLink(tile.id)
                        IconCache.forget(tile.id)
                        loadDeck()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), requireContext().errorText(e), Toast.LENGTH_LONG).show()
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Tuile « Ajouter » des pages Apps et Média (créée sur la tablette, inconnue de l'ordinateur). */
        const val ADD = "add"
        const val LIVE_POLL_MS = 4_000L
        const val LIVE_HOLD_MS = 20_000L
        /** Clés des onglets (ordre gardé dans Prefs) : « Ouvertes », et une page par son titre. */
        const val OPEN_TAB = "open"
        const val PAGE_TAB = "page:"
        /** Distance de côté qui change d'onglet. */
        const val SWIPE_DP = 80
        /** Laisser l'appli réagir à une touche avant de relire l'appel. */
        const val VOICE_AFTER_MS = 700L
    }
}
