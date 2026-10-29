package com.unowhy.companion.ui.home

import android.content.Context
import android.widget.LinearLayout
import com.unowhy.companion.R
import com.unowhy.companion.net.DeckTile

/**
 * Les applis de la page Apps du Deck, sur un profil : toucher lance l'appli (ou propose ses fenêtres si elle en a
 * plusieurs), appui long = ses fenêtres et « Retirer », « + » du titre = ajouter une appli du catalogue de l'ordinateur. Seule
 * la grille garde l'appui long : sur le titre ou la marge, il entre en mode Modifier.
 */
class AppsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(12).apply { orientation = LinearLayout.VERTICAL }

    private val section = TileSection(
        context, host, R.string.widget_apps, R.drawable.ic_tabs, R.string.apps_add_tile,
        tilesOf = { pages -> pages.firstOrNull { it.apps }?.tiles },
        onAdd = ::addApps,
    )

    // Hors d'un `apply` sur la section : `::run` y désignerait `TileSection.run`, pas celui du widget.
    init {
        section.grid.onTap = { cell, _ -> section.tileOf(cell)?.let(::run) }
        section.grid.ownsLongPress = true
        section.grid.onHold = { cell, _ -> section.tileOf(cell)?.let { host.overlays.windows(it, removable = true) { section.reload() } } }
        section.addTo(view)
    }

    override suspend fun load() = section.load()

    private fun addApps() {
        // Nom d'appli → identifiant de tuile (applis déjà dans la page Apps).
        val present = section.tiles.mapNotNull { t -> t.app?.let { it to t.id } }.toMap()
        host.overlays.addApps(present) { section.reload() }
    }

    private fun run(tile: DeckTile) =
        section.run(tile) { result -> result.windows?.let { host.overlays.windows(tile, it, removable = true) { section.reload() } } }
}
