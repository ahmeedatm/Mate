package com.unowhy.unomate.ui

import com.unowhy.unomate.Mode
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.ui.home.HomeLayout

/**
 * Une entrée de la barre : un profil (page de l'Accueil), « Plus » (la liste des profils), « + » (ajouter un écran) ou
 * un outil (Focus, Ordi, Caméra).
 */
sealed interface RailEntry {
    data class Profile(val page: Int, val isCall: Boolean) : RailEntry

    data object More : RailEntry

    data object Add : RailEntry

    data class Tool(val mode: Mode) : RailEntry
}

/**
 * Ce que la barre fixe affiche, sans vue : les profils dans leur ordre (Réunion seulement pendant un appel, en dernier),
 * au plus [MAX_PROFILES] places (« Plus » remplace les derniers, jamais Réunion), « + » tant qu'il reste une place
 * (ajouter un écran : de base il n'y a que l'Accueil), puis les outils. Parler, les
 * notifications, l'ordinateur et Réglages ont leur place fixe dans la vue.
 */
object RailModel {
    const val MAX_PROFILES = 5

    /**
     * Ce que la barre a dessiné : noms, icônes, rôles, appel, outils et noms affichés. Copié, pas l'objet vivant : la liste des
     * profils modifie la disposition sur place, la barre doit voir la différence.
     */
    data class Snapshot(
        val names: List<String?>,
        val icons: List<String?>,
        val roles: List<Int>,
        val inCall: Boolean,
        val tools: List<Mode>,
        val labels: Boolean,
    )

    fun snapshot(layout: HomeLayout, inCall: Boolean, tools: List<Mode>, labels: Boolean) = Snapshot(
        layout.names.toList(),
        layout.names.indices.map { layout.iconOf(it) },
        listOf(layout.homeProfile, layout.deckProfile, layout.callProfile),
        inCall,
        tools.toList(),
        labels,
    )

    /** `tools` : les outils choisis dans Réglages (Focus, Ordi, Caméra par défaut). */
    fun entries(layout: HomeLayout, inCall: Boolean, tools: List<Mode> = Prefs.RAIL_TOOLS): List<RailEntry> {
        val call = layout.callProfile.takeIf { inCall && it >= 0 }
        val others = layout.profilesInOrder(inCall).filter { it != call }
        val room = MAX_PROFILES - (if (call != null) 1 else 0)
        val shown = if (others.size > room) others.take(room - 1).map { RailEntry.Profile(it, false) } + RailEntry.More else others.map { RailEntry.Profile(it, false) }
        val add = RailEntry.Add.takeIf { others.size < room }
        return shown + listOfNotNull(call?.let { RailEntry.Profile(it, true) }, add) + tools.map { RailEntry.Tool(it) }
    }

    /**
     * Sens du déplacement dans la barre, d'une entrée marquée à une autre : +1 vers le bas (le nouvel écran monte),
     * -1 vers le haut, 0 sur place. Rien de marqué avant (Réglages) : comme vers le bas.
     */
    fun direction(from: Int, to: Int): Int = if (from < 0) 1 else Integer.signum(to - from)

    /**
     * Entrée voisine pour le balayage vertical (+1 : suivante, plus bas dans la barre ; -1 : précédente) : profils puis
     * outils, dans l'ordre de la barre. « Plus » et « + » ouvrent une liste, pas un écran : sautés. Null au bout, ou
     * hors barre (Réglages).
     */
    fun neighbor(entries: List<RailEntry>, mode: Mode, page: Int, delta: Int): RailEntry? {
        var i = selected(entries, mode, page)
        if (i < 0 || delta == 0) return null
        while (true) {
            i += delta
            val entry = entries.getOrNull(i) ?: return null
            if (entry is RailEntry.Profile || entry is RailEntry.Tool) return entry
        }
    }

    /** Index de l'entrée à marquer : le profil affiché (ou « Plus » s'il est caché derrière), l'outil, -1 sinon (Réglages). */
    fun selected(entries: List<RailEntry>, mode: Mode, page: Int): Int = when (mode) {
        Mode.HOME -> entries.indexOfFirst { it is RailEntry.Profile && it.page == page }.takeIf { it >= 0 } ?: entries.indexOf(RailEntry.More)
        else -> entries.indexOf(RailEntry.Tool(mode))
    }
}
