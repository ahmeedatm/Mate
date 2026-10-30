package com.unowhy.unomate.ui.home

import android.content.Context
import android.widget.LinearLayout
import com.unowhy.unomate.R
import com.unowhy.unomate.net.DeckTile
import com.unowhy.unomate.ui.dp
import com.unowhy.unomate.ui.themeBool

/**
 * Les applis de la page Apps du Deck, sur un profil : toucher lance l'appli (ou propose ses fenêtres si elle en a
 * plusieurs), appui long = ses fenêtres et « Retirer », bouton du titre = toutes les applis de l'ordinateur (lancer, épingler ici). Seule
 * la grille garde l'appui long : sur le titre ou la marge, le widget s'agrandit.
 */
class AppsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(12).apply { orientation = LinearLayout.VERTICAL }

    private val section = TileSection(
        context, host, R.string.widget_apps, R.string.apps_all,
        tilesOf = { pages -> pages.firstOrNull { it.apps }?.tiles },
        onAdd = ::addApps,
        // Toutes les applis de l'ordinateur : les lancer, ou les épingler sur la carte.
        addIcon = R.drawable.ic_apps,
    )

    // Hors d'un `apply` sur la section : `::run` y désignerait `TileSection.run`, pas celui du widget.
    init {
        section.grid.onTap = { cell, _ -> section.tileOf(cell)?.let(::run) }
        section.grid.ownsLongPress = true
        section.grid.onHold = { cell, _ -> section.tileOf(cell)?.let { host.overlays.windows(it, removable = true) { section.reload() } } }
        // Vertical (Accueil) : titre aligné sur ceux des cartes voisines (Agenda, Tâches, Touches). Le « + » de 48 dp
        // agrandit la ligne de titre : 11 dp en haut au lieu de 24 ; en bas, la place reste aux noms des applis.
        if (placed.size == WidgetSize.V) view.setPadding(view.dp(24), view.dp(11), view.dp(24), view.dp(12))
        section.addTo(view)
        // Bandeau sur la ligne fine (92 dp) : pas la place pour le titre au-dessus des applis.
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val short = bottom - top in 1 until view.dp(SHORT_DP)
            if (short != section.headerHidden) view.post { section.headerHidden = short }
        }
    }

    private companion object {
        /** Hauteur sous laquelle le titre laisse la place aux applis. */
        const val SHORT_DP = 130
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
