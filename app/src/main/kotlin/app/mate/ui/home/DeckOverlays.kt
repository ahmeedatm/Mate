package app.mate.ui.home

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import app.mate.net.DeckTile
import app.mate.net.WindowsInfo
import app.mate.ui.AgentsBoard
import app.mate.ui.AppAdder
import app.mate.ui.KeyEditor
import app.mate.ui.LinkAdder
import app.mate.ui.WindowPicker
import kotlinx.coroutines.CoroutineScope

/**
 * Les panneaux plein écran du Deck (touche, applis, sites, fenêtres, tableau des agents), par-dessus l'écran Profils. Chacun est créé à
 * la première demande : [KeyEditor] gonfle un gros layout, la tablette est lente. Leur rappel est enregistré
 * avant chaque ouverture : c'est celui du widget qui ouvre.
 */
class DeckOverlays(private val root: FrameLayout, private val scope: CoroutineScope) {
    private var keyEditor: KeyEditor? = null
    private var appAdder: AppAdder? = null
    private var linkAdder: LinkAdder? = null
    private var windowPicker: WindowPicker? = null
    private var agentsBoard: AgentsBoard? = null

    /** `page` : rang de la page de touches nommée de la touche, null pour Perso. */
    fun editKey(slot: Int, page: Int? = null, onChanged: () -> Unit) {
        val editor = keyEditor ?: KeyEditor(root.context).also { keyEditor = it; add(it) }
        editor.attach(scope, onChanged)
        editor.show(slot, page)
    }

    fun addApps(present: Map<String, String>, onChanged: () -> Unit) {
        val adder = appAdder ?: AppAdder(root.context).also { appAdder = it; add(it) }
        adder.attach(scope, onChanged)
        adder.show(present)
    }

    fun addLinks(onChanged: () -> Unit) {
        val adder = linkAdder ?: LinkAdder(root.context).also { linkAdder = it; add(it) }
        adder.attach(scope, onChanged)
        adder.show()
    }

    fun windows(tile: DeckTile, known: WindowsInfo? = null, removable: Boolean = false, onRemoved: () -> Unit = {}) {
        val picker = windowPicker ?: WindowPicker(root.context).also { windowPicker = it; add(it) }
        picker.attach(scope, onRemoved)
        picker.show(tile, known, removable)
    }

    /** Le tableau des sessions Claude : relu tant qu'il est ouvert. */
    fun agentsBoard(owner: LifecycleOwner) {
        val board = agentsBoard ?: AgentsBoard(root.context).also { agentsBoard = it; add(it) }
        board.show(owner)
    }

    /** Seul le tableau des agents : il est relu tant qu'il est ouvert. */
    fun hideAgentsBoard() {
        agentsBoard?.hide()
    }

    fun hideAll() {
        keyEditor?.hide()
        appAdder?.hide()
        linkAdder?.hide()
        windowPicker?.hide()
        agentsBoard?.hide()
    }

    private fun add(view: View) =
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}
