package com.unowhy.companion.ui.home

import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DeckPage
import com.unowhy.companion.net.DeckTile
import com.unowhy.companion.net.runCatchingCancellable
import com.unowhy.companion.ui.errorText
import kotlinx.coroutines.launch

/**
 * Les applis de la page Apps du Deck, sur un profil : toucher lance l'appli (ou propose ses fenêtres si elle en a
 * plusieurs), appui long = ses fenêtres et « Retirer », « + » = ajouter une appli du catalogue de l'ordinateur. Le
 * widget garde l'appui long : sinon la grille entrerait en mode Modifier.
 */
class AppsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = OwnerLinearLayout(context).asCard(12).apply { orientation = LinearLayout.VERTICAL }

    private var tiles: List<DeckTile> = emptyList()

    private val grid = TileGridView(context, host.scope).apply {
        onTap = { cell, _ -> if (cell.add) addApps() else tileOf(cell)?.let(::run) }
        onHold = { cell, _ -> if (!cell.add) tileOf(cell)?.let { host.overlays.windows(it, removable = true) { reload() } } }
    }

    init {
        view.addView(
            cardTitle(R.string.widget_apps, R.drawable.ic_tabs),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) },
        )
        view.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        render()
    }

    /** Relit les pages (cache) : une appli ajoutée ou retirée depuis le Deck apparaît dans la minute. */
    override suspend fun load() {
        val pages = runCatchingCancellable { host.deckPages() }.getOrNull() ?: return
        store(pages)
    }

    private fun store(pages: List<DeckPage>) {
        tiles = pages.firstOrNull { it.apps }?.tiles.orEmpty()
        render()
    }

    private fun render() {
        grid.cells = tiles.map { TileGridView.Cell(KeyRef.of(it), type = it.type) } +
            TileGridView.Cell(KeyRef(ADD_ID, context.getString(R.string.apps_add_tile)), add = true)
    }

    private fun tileOf(cell: TileGridView.Cell) = tiles.firstOrNull { it.id == cell.key.id }

    /** Après un ajout ou un retrait : relit le deck pour de bon. */
    private fun reload() {
        host.scope.launch {
            runCatchingCancellable { host.deckPages(force = true) }
                .onSuccess(::store)
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
        }
    }

    private fun addApps() {
        // Nom d'appli → identifiant de tuile, comme `DeckFragment.currentApps`.
        val present = tiles.mapNotNull { t -> t.app?.let { it to t.id } }.toMap()
        host.overlays.addApps(present) { reload() }
    }

    private fun run(tile: DeckTile) {
        host.scope.launch {
            runCatchingCancellable { BackendClient(context).deck.runTile(tile.id) }
                .onSuccess { result -> result.windows?.let { host.overlays.windows(tile, it, removable = true) { reload() } } }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
        }
    }

    private companion object {
        const val ADD_ID = "+"
    }
}
