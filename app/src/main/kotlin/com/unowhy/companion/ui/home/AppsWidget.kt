package com.unowhy.companion.ui.home

import android.content.Context
import android.widget.LinearLayout
import com.unowhy.companion.R
import com.unowhy.companion.net.DeckTile
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.themeBool

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
        // Cadran, vertical (Accueil) : titre aligné sur ceux des cartes voisines (Agenda, Tâches, Touches). Le « + » de 48 dp
        // agrandit la ligne de titre : 11 dp en haut au lieu de 24 ; en bas, la place reste aux noms des applis.
        if (placed.size == WidgetSize.V && themeBool(context, R.attr.c_cadran)) view.setPadding(view.dp(24), view.dp(11), view.dp(24), view.dp(12))
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
