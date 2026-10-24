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
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.MacDisplay
import com.unowhy.companion.net.MacWindow
import com.unowhy.companion.net.OpenApp
import com.unowhy.companion.net.OpenWindows
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

    private class Drag(val app: OpenApp, val window: MacWindow)

    private val map = ScreenMap(context)
    private val elsewhere = HorizontalScrollView(context)
    private val elsewhereList = LinearLayout(context)
    private val status = TextView(context)

    private lateinit var scope: CoroutineScope
    private var poll: Job? = null
    private var shown: OpenWindows? = null
    private var dragged: View? = null
    /** Actions en cours : la relecture périodique les attend, sinon elle remettrait l'ancien état un instant. */
    private var acting = 0

    init {
        orientation = VERTICAL
        visibility = GONE
        status.apply {
            textSize = 15f
            setTextColor(color(R.color.text_dim))
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
            val result = BackendClient(context).openWindows()
            if (dragged == null && acting == 0) render(result)
        } catch (e: Exception) {
            showStatus(context.errorText(e))
        }
    }

    /** Appelle l'ordinateur puis affiche la liste à jour ; `rollback` : état à remettre en cas d'échec. */
    private fun act(rollback: OpenWindows?, call: suspend BackendClient.() -> OpenWindows) {
        acting++
        scope.launch {
            try {
                render(BackendClient(context).call())
            } catch (e: Exception) {
                rollback?.let(::render)
                showStatus(context.errorText(e))
            } finally {
                acting--
            }
        }
    }

    /** Fenêtre déposée sur un écran : le schéma change tout de suite, l'ordinateur confirme ensuite. */
    private fun drop(drag: Drag, display: MacDisplay, layout: String) {
        val current = shown ?: return
        val w = drag.window
        if (!w.elsewhere && w.display == display.id && layout == "keep") return
        val moved = w.copy(display = display.id, elsewhere = false, minimized = false, fullscreen = false)
        render(current.copy(apps = current.apps.map { a ->
            if (a != drag.app) a else a.copy(windows = a.windows.map { if (it == w) moved else it })
        }))
        act(rollback = current) { openWindowAction(drag.app, w, "place", display = display.id, layout = layout) }
    }

    private fun render(data: OpenWindows) {
        status.visibility = GONE
        if (data.apps.isEmpty()) showStatus(context.getString(R.string.open_none))
        // Même état qu'avant : on ne reconstruit pas (le défilement de chaque écran reste où il est).
        if (data == shown) return
        shown = data
        // Par nom d'appli, pas de la plus en avant à la plus en arrière : la liste ne bouge pas quand on touche une fenêtre.
        val windows = data.apps.sortedBy { it.name.lowercase() }.flatMap { a -> a.windows.map { a to it } }
        map.setScreens(data.displays) { d -> windows.filter { it.second.display == d.id }.map { (a, w) -> card(a, w) } }
        val away = windows.filter { (_, w) -> data.displays.none { it.id == w.display } }
        elsewhereList.removeAllViews()
        away.forEach { (a, w) ->
            elsewhereList.addView(card(a, w), LayoutParams(dp(240), LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        elsewhere.visibility = if (away.isEmpty()) GONE else VISIBLE
    }

    private fun card(app: OpenApp, w: MacWindow): View {
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
        card.dragToScreens(
            Drag(app, w), canDrag = { true }, onDragStarted = { dragged = it },
            onDoubleTap = {
                card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                act(rollback = null) { openWindowAction(app, w, "zoom") }
            },
        ) {
            card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            act(rollback = null) { openWindowAction(app, w, if (w.front && !w.minimized) "minimize" else "focus") }
        }
        return card
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = VISIBLE
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** La liste du Mac prend près de 2 s à lire (Accessibilité) : pas plus souvent. */
        const val POLL_MS = 5_000L
    }
}
