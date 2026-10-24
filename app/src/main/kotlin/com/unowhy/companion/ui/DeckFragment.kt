package com.unowhy.companion.ui

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.R
import com.unowhy.companion.VolumeKeyHandler
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DeckPage
import com.unowhy.companion.net.DeckTile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    /** Page Réunion demandée avant que le deck soit chargé. */
    private var meetingPending = false
    /** Applis en appel (micro utilisé) → dernier moment où on l'a vu : leur page a un onglet. */
    /** Applis à page vues ouvertes (onglet) ou en appel, avec l'heure où on les a vues. */
    private var openApps: Map<String, Long> = emptyMap()
    private var liveApps: Map<String, Long> = emptyMap()
    /** Ouverture de l'ordinateur pas encore lue : on ne sait pas si l'appli tourne. */
    private var openKnown = false
    private var livePoll: Job? = null
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
        nowPlaying = view.findViewById(R.id.deck_now_playing)
        nowPlaying.attach(viewLifecycleOwner.lifecycleScope)
        picker = view.findViewById(R.id.deck_windows)
        picker.attach(viewLifecycleOwner.lifecycleScope) { loadDeck() }
        openWindows = view.findViewById(R.id.deck_open)
        openWindows.attach(viewLifecycleOwner.lifecycleScope)
        editor = view.findViewById(R.id.deck_editor)
        editor.attach(viewLifecycleOwner.lifecycleScope) { loadDeck() }
        adder = view.findViewById(R.id.deck_adder)
        adder.attach(viewLifecycleOwner.lifecycleScope) { loadDeck() }
        linkAdder = view.findViewById(R.id.deck_links)
        linkAdder.attach(viewLifecycleOwner.lifecycleScope) { loadDeck() }

        grid.adapter = adapter
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
        tabs.removeAllViews()
        fun seen(map: Map<String, Long>, app: String) = map.keys.any { it.equals(app, ignoreCase = true) }
        val live = pages.indices.filter { i ->
            val app = pages[i].app ?: return@filter false
            seen(liveApps, app) || (!pages[i].meeting && seen(openApps, app))
        }
        val shown = pages.indices.filter { pages[it].app == null } + live +
            listOf(pageIndex).filter { pages.getOrNull(it)?.app != null && it !in live }
        fun addTab(title: String, selected: Boolean, onClick: () -> Unit) {
            val tab = Button(requireContext(), null, 0, R.style.Chip).apply {
                text = title
                isSelected = selected
                setOnClickListener { onClick() }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) })
        }
        // « Ouvertes » juste après Apps (en premier sans page Apps) : toutes les applis ouvertes, pas seulement les choisies.
        val openAfter = shown.indexOfFirst { pages[it].apps }
        if (openAfter < 0) addTab(getString(R.string.deck_open_tab), showingOpen, ::showOpen)
        shown.forEachIndexed { i, index ->
            addTab(pages[index].title, !showingOpen && index == pageIndex) { showPage(index) }
            if (i == openAfter) addTab(getString(R.string.deck_open_tab), showingOpen, ::showOpen)
        }
    }

    /** Onglet « Ouvertes » : les touches et les barres de son laissent la place au schéma des écrans. */
    private fun showOpen() {
        showingOpen = true
        grid.visibility = View.GONE
        status.setText(R.string.deck_open_hint)
        status.visibility = View.VISIBLE
        renderTabs()
        audio.visibility = View.GONE
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
        adapter.tiles = page?.tiles.orEmpty() +
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
                initial.visibility = View.VISIBLE
                icon.setImageBitmap(null)
                return view
            }

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
    }
}
