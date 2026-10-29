package com.unowhy.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.unowhy.companion.Computers
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.ComputerDisplay
import com.unowhy.companion.net.ComputerPage
import com.unowhy.companion.net.ComputerWindow
import com.unowhy.companion.net.OpenApp
import com.unowhy.companion.net.OpenWindows
import com.unowhy.companion.net.WindowsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Vue « Ouvertes » du deck : toutes les applis ouvertes sur l'ordinateur, chaque fenêtre dans le schéma de son écran
 * (ScreenMap), sans avoir à les ajouter à la page Apps. Toucher une fenêtre la met devant, ou la réduit si elle l'est
 * déjà ; deux touchers l'agrandissent, ou lui rendent sa taille ; la glisser sur un écran l'y déplace (bords : moitié
 * d'écran, haut : agrandie). Relue toutes les quelques secondes tant qu'elle est affichée.
 * Mac : bande des pages du balayage à trois doigts (Bureau, applis en plein écran), dans l'ordre ; toucher une page y
 * passe, glisser une fenêtre sur « Plein écran » en fait une page, glisser une page sur un écran l'y ramène, sur une
 * autre page la met à sa place.
 */
class OpenWindowsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private class Drag(val app: OpenApp, val window: ComputerWindow)

    private val map = ScreenMap(context).apply { stretch = true }
    /** Bande du bas : instructions, ou corbeille pendant un glisser (y garder une fenêtre la ferme). */
    private val trash = TrashZone(context) { payload -> (payload as? Drag)?.let(::close) }
    private val pagesBar = HorizontalScrollView(context)
    private val pagesList = LinearLayout(context)
    private val elsewhere = HorizontalScrollView(context)
    private val elsewhereList = LinearLayout(context)
    private val status = TextView(context)

    private lateinit var scope: CoroutineScope
    private var poll: Job? = null
    private var shown: OpenWindows? = null
    private var dragged: View? = null
    /** Actions en cours : la relecture périodique les attend, sinon elle remettrait l'ancien état un instant. */
    private var acting = 0
    /** Numéro du dernier geste : la réponse d'un geste dépassé par un autre est ignorée (pas de retour en arrière). */
    private var lastAction = 0
    /** Relectures échouées d'affilée. */
    private var failures = 0
    /** Fenêtre du dernier geste, estompée jusqu'à la réponse de l'ordinateur. */
    private var pending: Pair<String, ComputerWindow>? = null

    init {
        orientation = VERTICAL
        visibility = GONE
        status.apply {
            textSize = 15f
            setTextColor(color(R.attr.c_text_dim))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            visibility = GONE
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        pagesList.orientation = HORIZONTAL
        pagesBar.isHorizontalScrollBarEnabled = false
        pagesBar.addView(pagesList)
        pagesBar.visibility = GONE
        addView(pagesBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // Hors des écrans : fenêtres d'un autre bureau du Mac dont l'écran est inconnu.
        elsewhereList.orientation = HORIZONTAL
        elsewhere.isHorizontalScrollBarEnabled = false
        elsewhere.addView(elsewhereList)
        elsewhere.visibility = GONE
        addView(elsewhere, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(trash, LayoutParams(LayoutParams.MATCH_PARENT, dp(84)).apply { topMargin = dp(12) })

        map.onDrop = { payload, display, layout -> (payload as? Drag)?.let { drop(it, display, layout) } }
        map.onDragEnded = {
            dragged?.alpha = 1f
            dragged = null
        }
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Affichée et relue en boucle, ou cachée. */
    fun setActive(active: Boolean) {
        visibility = if (active) VISIBLE else GONE
        poll?.cancel()
        if (!active) return
        if (shown == null) showStatus(context.getString(R.string.open_loading))
        poll = scope.launch {
            while (isActive) {
                if (dragged == null && acting == 0) refresh()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun refresh() {
        try {
            val result = BackendClient(context).windows.openWindows()
            failures = 0
            if (dragged == null && acting == 0) render(result)
        } catch (e: Exception) {
            val message = context.errorText(e)
            // Un échec isolé (Mac pendant une animation de plein écran) : la liste affichée reste, la suivante le rattrape.
            if (++failures >= 2 || shown == null) showStatus(message)
        }
    }

    /**
     * Affiche tout de suite l'état attendu (`target` estompée), appelle l'ordinateur, puis relit la liste une fois
     * l'animation de l'ordinateur finie. En cas d'échec, l'état d'avant revient.
     */
    private fun act(app: OpenApp?, target: ComputerWindow?, expected: OpenWindows, call: suspend WindowsApi.() -> OpenWindows?) {
        val before = shown
        val id = ++lastAction
        pending = if (app != null && target != null) app.key to expected.windowOf(app.key, target) else null
        render(expected, force = true)
        acting++
        scope.launch {
            try {
                val api = BackendClient(context).windows
                val result = api.call() ?: run {
                    delay(SETTLE_MS)
                    if (id == lastAction) api.openWindows() else null
                }
                if (id == lastAction) {
                    pending = null
                    result?.let { if (dragged == null) render(it, force = true) }
                }
            } catch (e: Exception) {
                val message = context.errorText(e)
                if (id == lastAction) {
                    pending = null
                    before?.let { render(it, force = true) }
                    showStatus(message)
                }
            } finally {
                acting--
            }
        }
    }

    /** État attendu après un geste sur `w` : seule cette fenêtre change (et le premier plan). */
    private fun expect(app: OpenApp, w: ComputerWindow, front: Boolean = w.front, change: (ComputerWindow) -> ComputerWindow): OpenWindows {
        val current = shown ?: OpenWindows(listOf(app), emptyList())
        return current.copy(apps = current.apps.map { a ->
            a.copy(windows = a.windows.map {
                when {
                    a.key == app.key && it == w -> change(it).copy(front = front)
                    front -> it.copy(front = false)
                    else -> it
                }
            })
        })
    }

    private fun OpenWindows.windowOf(key: String, w: ComputerWindow) =
        apps.firstOrNull { it.key == key }?.windows?.firstOrNull { it.index == w.index && it.title == w.title } ?: w

    /** Fenêtre déposée sur un écran : le schéma change tout de suite, l'ordinateur confirme ensuite. */
    private fun drop(drag: Drag, display: ComputerDisplay, layout: String) {
        if (shown == null) return
        val w = drag.window
        if (!w.elsewhere && w.display == display.id && layout == "keep") return
        // Page plein écran ramenée sur un écran : elle quitte le plein écran, sa page disparaît.
        val moved = expect(drag.app, w, front = true) { it.copy(display = display.id, elsewhere = false, minimized = false, fullscreen = false, page = null) }
            .withoutPage(w)
        act(drag.app, w, moved) { openWindowAction(drag.app, w, "place", display = display.id, layout = layout) }
    }

    /** Fenêtre lâchée dans la corbeille : fermée comme par sa croix (l'appli peut demander d'enregistrer). */
    private fun close(drag: Drag) {
        val current = shown ?: return
        val closed = current.copy(apps = current.apps.mapNotNull { a ->
            if (a != drag.app) a else a.copy(windows = a.windows - drag.window).takeIf { it.windows.isNotEmpty() }
        })
        act(drag.app, drag.window, closed) { openWindowAction(drag.app, drag.window, "close") }
    }

    /** `force` : reconstruit même si la liste n'a pas changé (fenêtre estompée ou plus). */
    private fun render(data: OpenWindows, force: Boolean = false) {
        status.visibility = GONE
        if (data.apps.isEmpty()) showStatus(context.getString(R.string.open_none))
        // Même état qu'avant : on ne reconstruit pas (le défilement de chaque écran reste où il est).
        if (data == shown && !force) return
        shown = data
        // Par nom d'appli puis par titre, pas de la plus en avant à la plus en arrière : la liste ne bouge pas quand on
        // touche une fenêtre ou qu'on change de bureau.
        val windows = data.apps.sortedBy { it.name.lowercase() }.flatMap { a -> a.windows.sortedBy { it.title.lowercase() }.map { a to it } }
        // Fenêtres en plein écran : dans la bande des pages, pas sur les écrans.
        val onScreens = windows.filter { it.second.page == null }
        map.setScreens(data.displays) { d -> onScreens.filter { it.second.display == d.id }.map { (a, w) -> card(a, w) } }
        renderPages(data, windows)
        val away = onScreens.filter { (_, w) -> data.displays.none { it.id == w.display } }
        elsewhereList.removeAllViews()
        away.forEach { (a, w) ->
            elsewhereList.addView(card(a, w), LayoutParams(dp(240), LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        elsewhere.visibility = if (away.isEmpty()) GONE else VISIBLE
    }

    private fun card(app: OpenApp, w: ComputerWindow): View {
        val card = LayoutInflater.from(context).inflate(R.layout.item_open_window, this, false)
        val icon = card.findViewById<ImageView>(R.id.open_icon)
        fun showIcon(bitmap: android.graphics.Bitmap) {
            icon.imageTintList = null
            icon.setImageBitmap(bitmap)
        }
        IconCache.peekApp(app.name)?.let(::showIcon) ?: scope.launch {
            IconCache.loadApp(BackendClient(context), app.name)?.let(::showIcon)
        }
        card.findViewById<TextView>(R.id.open_app).text = app.name
        val meta = listOfNotNull(
            context.getString(R.string.windows_other_desktop).takeIf { w.elsewhere },
            context.getString(R.string.windows_minimized).takeIf { w.minimized },
            context.getString(R.string.windows_fullscreen).takeIf { w.fullscreen },
        )
        card.findViewById<TextView>(R.id.open_title).text =
            (listOf(w.title.ifBlank { context.getString(R.string.windows_untitled) }) + meta).joinToString(" · ")
        if (pending == app.key to w) card.alpha = PENDING_ALPHA
        val page = w.page?.let { id -> shown?.pages?.firstOrNull { it.id == id } }
        if (page != null) {
            card.setBackgroundResource(R.drawable.page_bg)
            card.isSelected = page.current
        }
        card.dragToScreens(
            Drag(app, w), canDrag = { true }, onDragStarted = { dragged = it },
            onDoubleTap = {
                card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                // Sortir du plein écran rend aussi sa taille d'avant.
                val zoomed = expect(app, w, front = true) { it.copy(minimized = false, fullscreen = false, maximized = !it.maximized && !it.fullscreen, page = null) }
                act(app, w, zoomed.withoutPage(w)) { openWindowAction(app, w, "zoom") }
            },
            // Mac : plein écran sur son propre bureau (bouton vert), à part de « remplir l'écran ».
            onTripleTap = {
                card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (page == null && !w.fullscreen) {
                    toFullscreen(app, w)
                } else {
                    act(app, w, expect(app, w, front = true) { it.copy(minimized = false, fullscreen = false, page = null) }.withoutPage(w)) {
                        openWindowAction(app, w, "fullscreen")
                    }
                }
            }.takeIf { Computers.active(context)?.os == "mac" },
        ) {
            card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            // Page plein écran : toucher y passe toujours (une fenêtre en plein écran ne se réduit pas).
            if (page != null) {
                act(app, w, expect(app, w, front = true) { it }.showing(page.id)) { openWindowAction(app, w, "focus") }
            } else if (w.front && !w.minimized) {
                act(app, w, expect(app, w, front = false) { it.copy(minimized = true) }) { openWindowAction(app, w, "minimize") }
            } else {
                // Fenêtre du Bureau mise devant : le Mac passe sur le Bureau (s'il n'y en a qu'un sur son écran).
                val focused = expect(app, w, front = true) { it.copy(minimized = false) }
                val desktop = focused.pages.filter { !it.fullscreen && it.display == w.display }.singleOrNull()
                act(app, w, desktop?.let { focused.showing(it.id) } ?: focused) { openWindowAction(app, w, "focus") }
            }
        }
        return card
    }

    /** Bande des pages du Mac : Bureau, pages plein écran (leur fenêtre), puis « Plein écran » pour en créer une. */
    private fun renderPages(data: OpenWindows, windows: List<Pair<OpenApp, ComputerWindow>>) {
        pagesList.removeAllViews()
        pagesBar.visibility = if (data.pages.isEmpty()) GONE else VISIBLE
        if (data.pages.isEmpty()) return
        val severalScreens = data.pages.map { it.display }.distinct().size > 1
        // Toutes les tuiles dans la largeur (on ne glisse pas vers une tuile hors de l'écran) : plus étroites quand il
        // y a beaucoup de pages, la bande ne défile qu'au-delà.
        val available = (if (width > 0) width else resources.displayMetrics.widthPixels) - paddingLeft - paddingRight
        val tileWidth = (available / (data.pages.size + 1) - dp(8)).coerceIn(dp(96), dp(220))
        data.pages.forEach { page ->
            val owner = windows.firstOrNull { it.second.page == page.id }
            val tile = if (owner != null) {
                card(owner.first, owner.second)
            } else {
                val desktops = data.pages.filter { !it.fullscreen && it.display == page.display }
                val name = when {
                    page.fullscreen -> page.app ?: context.getString(R.string.open_page_new)
                    desktops.size > 1 -> context.getString(R.string.open_page_desktop_n, desktops.indexOf(page) + 1)
                    else -> context.getString(R.string.open_page_desktop)
                }
                val screen = data.displays.firstOrNull { it.id == page.display }?.label?.takeIf { severalScreens }
                pageTile(if (page.fullscreen) R.drawable.ic_fullscreen else R.drawable.ic_laptop, listOfNotNull(name, screen).joinToString(" · "),
                    context.getString(if (page.current) R.string.open_page_shown else R.string.open_page_go)).apply {
                    isSelected = page.current
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (!page.current) act(null, null, data.showing(page.id)) { showPage(page) }
                    }
                }
            }
            tile.acceptPage(page)
            pagesList.addView(fit(tile, tileWidth), LayoutParams(tileWidth, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        pagesList.addView(fit(newPageTile(), tileWidth), LayoutParams(tileWidth, LayoutParams.WRAP_CONTENT))
    }

    /** Tuile étroite : sans sous-titre, le nom seul tient. */
    private fun fit(tile: View, tileWidth: Int) = tile.apply {
        if (tileWidth < dp(150)) findViewById<View>(R.id.open_title).visibility = GONE
    }

    private fun pageTile(icon: Int, name: String, hint: String): View {
        val tile = LayoutInflater.from(context).inflate(R.layout.item_open_window, this, false)
        tile.setBackgroundResource(R.drawable.page_bg)
        tile.findViewById<ImageView>(R.id.open_icon).setImageResource(icon)
        tile.findViewById<TextView>(R.id.open_app).text = name
        tile.findViewById<TextView>(R.id.open_title).text = hint
        return tile
    }

    /** « Plein écran » : y lâcher une fenêtre du Bureau en fait une page. Toujours VISIBLE, sinon pas d'événement de glisser. */
    private fun newPageTile() = pageTile(R.drawable.ic_add, context.getString(R.string.open_page_new), context.getString(R.string.open_page_new_hint)).apply {
        alpha = IDLE_ALPHA
        setOnDragListener { v, e ->
            val drag = e.localState as? Drag
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> drag != null && drag.window.page == null
                DragEvent.ACTION_DRAG_ENTERED -> true.also { v.isSelected = true; v.alpha = 1f }
                DragEvent.ACTION_DRAG_EXITED -> true.also { v.isSelected = false; v.alpha = IDLE_ALPHA }
                DragEvent.ACTION_DROP -> true.also { drag?.let { toFullscreen(it.app, it.window) } }
                DragEvent.ACTION_DRAG_ENDED -> true.also { v.isSelected = false; v.alpha = IDLE_ALPHA }
                else -> true
            }
        }
    }

    /**
     * Fenêtre mise en plein écran (lâchée sur « Plein écran », triple toucher) : nouvelle page à la fin, provisoire
     * (id -1) jusqu'à la relecture ; sans pages (Windows), seulement marquée plein écran.
     */
    private fun toFullscreen(app: OpenApp, w: ComputerWindow) {
        val hasPages = shown?.pages.orEmpty().isNotEmpty()
        val expected = expect(app, w, front = true) { it.copy(minimized = false, fullscreen = true, page = NEW_PAGE.takeIf { hasPages }) }
        val withPage = if (hasPages) expected.copy(pages = expected.pages + ComputerPage(NEW_PAGE, w.display, true, false, app.name)).showing(NEW_PAGE) else expected
        act(app, w, withPage) { openWindowAction(app, w, "fullscreen") }
    }

    /** Sa page plein écran retirée de la bande (la fenêtre revient sur le Bureau). */
    private fun OpenWindows.withoutPage(w: ComputerWindow) = if (w.page == null) this else copy(pages = pages.filter { it.id != w.page })

    /** Page plein écran lâchée sur une autre page : elle prend sa place (le Mac la glisse dans Mission Control). */
    private fun View.acceptPage(target: ComputerPage) {
        setOnDragListener { v, e ->
            val moved = (e.localState as? Drag)?.window?.page
            when (e.action) {
                // Page provisoire (tout juste créée, pas encore relue) : pas encore déplaçable.
                DragEvent.ACTION_DRAG_STARTED -> moved != null && moved != NEW_PAGE && moved != target.id && target.id != NEW_PAGE
                DragEvent.ACTION_DRAG_ENTERED -> true.also { v.isPressed = true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> true.also { v.isPressed = false }
                DragEvent.ACTION_DROP -> true.also {
                    v.isPressed = false
                    if (moved != null) reorderPage(moved, target)
                }
                else -> true
            }
        }
    }

    private fun reorderPage(id: Int, target: ComputerPage) {
        val current = shown ?: return
        val page = current.pages.firstOrNull { it.id == id } ?: return
        // Par numéro : la tuile visée a pu changer depuis (page affichée), elle ne serait plus « égale ».
        val to = current.pages.indexOfFirst { it.id == target.id }
        if (to < 0) return
        val pages = current.pages.toMutableList().apply {
            remove(page)
            add(to, page)
        }
        act(null, null, current.copy(pages = pages)) { movePage(page, to) }
    }

    /** Même liste, la page `id` affichée sur l'ordinateur (celle de son écran qui l'était ne l'est plus). */
    private fun OpenWindows.showing(id: Int): OpenWindows {
        val display = pages.firstOrNull { it.id == id }?.display
        return copy(pages = pages.map { if (it.display == display) it.copy(current = it.id == id) else it })
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = VISIBLE
    }

    private fun color(res: Int) = themeColor(context, res)

    private companion object {
        /** La liste du Mac prend près de 2 s à lire (Accessibilité) : pas plus souvent. */
        const val POLL_MS = 5_000L
        /** Le plein écran et la sortie du plein écran sont animés par macOS (~1 s) : relire après. */
        const val SETTLE_MS = 1_500L
        const val PENDING_ALPHA = 0.55f
        const val IDLE_ALPHA = 0.6f
        const val NEW_PAGE = -1
    }
}
