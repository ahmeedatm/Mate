package com.unowhy.companion.ui

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.WindowsApi
import com.unowhy.companion.net.DeckTile
import com.unowhy.companion.net.MacDisplay
import com.unowhy.companion.net.MacWindow
import com.unowhy.companion.net.WindowsInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fenêtres d'une appli du Mac, par-dessus le deck, rangées dans un schéma des écrans (ScreenMap).
 * Toucher une fenêtre la met devant, là où elle est ; la glisser sur un écran l'y déplace (bords :
 * moitié d'écran, haut : agrandie) ; glisser l'icône de l'appli déplace toutes ses fenêtres.
 * Une fenêtre d'un autre bureau (derrière une appli plein écran…) est sur son écran, marquée « autre
 * bureau », ou en dessous si son écran est inconnu : la toucher fait passer sur son bureau.
 */
class WindowPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Ce qui est glissé : une fenêtre, ou toute l'appli (`window` null). */
    private class Drag(val window: MacWindow?)

    private val icon: ImageView
    private val title: TextView
    private val subtitle: TextView
    private val status: TextView
    private val map: ScreenMap
    private val elsewhere: View
    private val elsewhereList: LinearLayout
    private val hint: TextView
    private val activate: Button
    private val remove: Button

    private lateinit var scope: CoroutineScope
    private var onRemoved: () -> Unit = {}
    private var tile: DeckTile? = null
    private var info: WindowsInfo? = null
    private var dragged: View? = null

    init {
        setBackgroundResource(R.drawable.scrim)
        isClickable = true
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.view_window_picker, this, true)
        icon = findViewById(R.id.picker_icon)
        title = findViewById(R.id.picker_title)
        subtitle = findViewById(R.id.picker_subtitle)
        status = findViewById(R.id.picker_status)
        map = findViewById(R.id.picker_map)
        elsewhere = findViewById(R.id.picker_elsewhere)
        elsewhereList = findViewById(R.id.picker_elsewhere_list)
        hint = findViewById(R.id.picker_hint)
        activate = findViewById(R.id.picker_activate)
        remove = findViewById(R.id.picker_remove)
        remove.setOnClickListener { removeTile() }

        // Toucher le fond assombri ferme le panneau ; la carte absorbe ses propres touchers.
        setOnClickListener { hide() }
        findViewById<ImageButton>(R.id.picker_close).setOnClickListener { hide() }
        activate.setOnClickListener { dismissAndRun { windowAction(it, null, place = false) } }
        draggable(icon, Drag(null)) {}
        map.onDrop = { payload, display, layout -> (payload as? Drag)?.let { drop(it.window, display, layout) } }
        map.onDragEnded = {
            dragged?.alpha = 1f
            dragged = null
        }
    }

    /** `onRemoved` : une appli a été retirée de la page Apps. */
    fun attach(scope: CoroutineScope, onRemoved: () -> Unit) {
        this.scope = scope
        this.onRemoved = onRemoved
    }

    val isShowing get() = visibility == VISIBLE

    /**
     * Ouvre le panneau, avec les fenêtres déjà connues (réponse de /deck/run) ou en les demandant au Mac.
     * `removable` : tuile de la page Apps, que l'on peut retirer du deck.
     */
    fun show(tile: DeckTile, known: WindowsInfo? = null, removable: Boolean = false) {
        this.tile = tile
        remove.visibility = if (removable) VISIBLE else GONE
        title.text = tile.label
        icon.setImageBitmap(IconCache.peek(tile.id))
        visibility = VISIBLE
        if (known != null) render(known) else {
            render(null)
            act { ofTile(it) }
        }
    }

    fun hide() {
        visibility = GONE
        tile = null
    }

    private fun removeTile() {
        val id = tile?.id ?: return
        scope.launch {
            try {
                BackendClient(context).deck.removeApp(id)
                IconCache.forget(id)
                hide()
                onRemoved()
            } catch (e: Exception) {
                status.showError(e)
            }
        }
    }

    /**
     * Mettre une fenêtre devant : le panneau se ferme tout de suite, le Mac suit (une à deux secondes
     * s'il change de bureau). Un échec s'affiche dans le bandeau, le panneau n'étant plus là.
     */
    private fun dismissAndRun(call: suspend WindowsApi.(tileId: String) -> WindowsInfo) {
        val id = tile?.id ?: return
        hide()
        scope.launch {
            try {
                BackendClient(context).windows.call(id)
            } catch (e: Exception) {
                activity()?.showBanner(context.errorText(e), 6)
            }
        }
    }

    private fun activity(): MainActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is MainActivity) return c
            c = c.baseContext
        }
        return null
    }

    /** Appelle le Mac puis affiche les fenêtres à jour. `rollback` : état à remettre en cas d'échec. */
    private fun act(rollback: WindowsInfo? = null, call: suspend WindowsApi.(tileId: String) -> WindowsInfo) {
        val id = tile?.id ?: return
        scope.launch {
            try {
                val result = BackendClient(context).windows.call(id)
                if (tile?.id == id) render(result)
            } catch (e: Exception) {
                if (tile?.id != id) return@launch
                // Déplacement refusé : on remet les fenêtres où elles étaient.
                rollback?.let(::render)
                status.showError(e)
            }
        }
    }

    /**
     * Fenêtre (ou appli) déposée sur un écran. Le schéma change tout de suite, le Mac confirme ensuite :
     * le glisser reste fluide même quand l'Accessibilité met une seconde à répondre.
     */
    private fun drop(window: MacWindow?, display: MacDisplay, layout: String) {
        val current = info ?: return
        if (window != null && !window.elsewhere && window.display == display.id && layout == "keep") return
        val moved = current.windows.map { w ->
            if (window == null || w == window) w.copy(display = display.id, elsewhere = false, minimized = false, fullscreen = false) else w
        }
        render(current.copy(windows = moved))
        act(rollback = current) { windowAction(it, window, place = true, display = display.id, layout = layout) }
    }

    private fun render(info: WindowsInfo?) {
        this.info = info
        status.visibility = GONE
        val windows = info?.windows.orEmpty()
        val displays = info?.displays.orEmpty()

        subtitle.text = when {
            info == null -> ""
            !info.running -> context.getString(R.string.windows_not_running)
            windows.isEmpty() -> context.getString(R.string.windows_none)
            windows.size == 1 -> context.getString(R.string.windows_one)
            else -> context.getString(R.string.windows_count, windows.size)
        }
        activate.setText(if (info?.running == false) R.string.windows_open else R.string.windows_activate_app)
        hint.visibility = if (windows.isEmpty()) GONE else VISIBLE

        map.setScreens(displays) { d -> windows.filter { it.display == d.id }.map(::card) }

        // Hors des écrans : autres bureaux, ou fenêtre dont le centre déborde de tous les écrans.
        val away = windows.filter { w -> displays.none { it.id == w.display } }
        elsewhereList.removeAllViews()
        away.forEach { w ->
            elsewhereList.addView(card(w), LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        elsewhere.visibility = if (away.isEmpty()) GONE else VISIBLE
    }

    private fun card(w: MacWindow): View {
        val card = LayoutInflater.from(context).inflate(R.layout.item_window, this, false)
        card.findViewById<TextView>(R.id.window_title).text = w.title.ifBlank { context.getString(R.string.windows_untitled) }
        val meta = listOfNotNull(
            context.getString(R.string.windows_other_desktop).takeIf { w.elsewhere },
            context.getString(R.string.windows_minimized).takeIf { w.minimized },
            context.getString(R.string.windows_fullscreen).takeIf { w.fullscreen },
        )
        card.findViewById<TextView>(R.id.window_meta).apply {
            text = meta.joinToString(" · ")
            visibility = if (meta.isEmpty()) GONE else VISIBLE
        }
        draggable(card, Drag(w)) { dismissAndRun { windowAction(it, w, place = false) } }
        return card
    }

    private fun draggable(view: View, drag: Drag, onTap: () -> Unit) {
        view.dragToScreens(drag, canDrag = { info?.running == true }, onDragStarted = { dragged = it }, onTap = onTap)
    }
}
