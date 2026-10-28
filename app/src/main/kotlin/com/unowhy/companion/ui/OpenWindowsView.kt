package com.unowhy.companion.ui

import android.content.Context
import android.util.AttributeSet
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
 */
class OpenWindowsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private class Drag(val app: OpenApp, val window: ComputerWindow)

    private val map = ScreenMap(context).apply { stretch = true }
    /** Bande du bas : instructions, ou corbeille pendant un glisser (y garder une fenêtre la ferme). */
    private val trash = TrashZone(context) { payload -> (payload as? Drag)?.let(::close) }
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
            if (dragged == null && acting == 0) render(result)
        } catch (e: Exception) {
            showStatus(context.errorText(e))
        }
    }

    /**
     * Affiche tout de suite l'état attendu (`target` estompée), appelle l'ordinateur, puis relit la liste une fois
     * l'animation de l'ordinateur finie. En cas d'échec, l'état d'avant revient.
     */
    private fun act(app: OpenApp, target: ComputerWindow, expected: OpenWindows, call: suspend WindowsApi.() -> OpenWindows?) {
        val before = shown
        val id = ++lastAction
        pending = app.key to expected.windowOf(app.key, target)
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
        val moved = expect(drag.app, w, front = true) { it.copy(display = display.id, elsewhere = false, minimized = false, fullscreen = false) }
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
        map.setScreens(data.displays) { d -> windows.filter { it.second.display == d.id }.map { (a, w) -> card(a, w) } }
        val away = windows.filter { (_, w) -> data.displays.none { it.id == w.display } }
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
        card.dragToScreens(
            Drag(app, w), canDrag = { true }, onDragStarted = { dragged = it },
            onDoubleTap = {
                card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                // Sortir du plein écran rend aussi sa taille d'avant.
                val zoomed = expect(app, w, front = true) { it.copy(minimized = false, fullscreen = false, maximized = !it.maximized && !it.fullscreen) }
                act(app, w, zoomed) { openWindowAction(app, w, "zoom") }
            },
            // Mac : plein écran sur son propre bureau (bouton vert), à part de « remplir l'écran ».
            onTripleTap = {
                card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                act(app, w, expect(app, w, front = true) { it.copy(minimized = false, fullscreen = !it.fullscreen) }) {
                    openWindowAction(app, w, "fullscreen")
                }
            }.takeIf { Computers.active(context)?.os == "mac" },
        ) {
            card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (w.front && !w.minimized) {
                act(app, w, expect(app, w, front = false) { it.copy(minimized = true) }) { openWindowAction(app, w, "minimize") }
            } else {
                act(app, w, expect(app, w, front = true) { it.copy(minimized = false) }) { openWindowAction(app, w, "focus") }
            }
        }
        return card
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
    }
}
