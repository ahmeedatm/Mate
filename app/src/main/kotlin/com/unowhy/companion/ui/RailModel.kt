package com.unowhy.companion.ui

import com.unowhy.companion.Mode
import com.unowhy.companion.ui.home.HomeLayout

/** Une entrée de la barre : un profil (page de l'Accueil), « Plus » (la liste des profils) ou un outil (Focus, Ordi, Caméra). */
sealed interface RailEntry {
    data class Profile(val page: Int, val isCall: Boolean) : RailEntry

    data object More : RailEntry

    data class Tool(val mode: Mode) : RailEntry
}

/**
 * Ce que la barre fixe affiche, sans vue : les profils dans leur ordre (Réunion seulement pendant un appel, en dernier),
 * au plus [MAX_PROFILES] places (« Plus » remplace les derniers, jamais Réunion), puis les outils. Parler, les
 * notifications, l'ordinateur et Réglages ont leur place fixe dans la vue.
 */
object RailModel {
    const val MAX_PROFILES = 5

    private val TOOLS = listOf(Mode.FOCUS, Mode.COMPUTER, Mode.CAMERA)

    fun entries(layout: HomeLayout, inCall: Boolean): List<RailEntry> {
        val call = layout.callProfile.takeIf { inCall && it >= 0 }
        val others = layout.profilesInOrder(inCall).filter { it != call }
        val room = MAX_PROFILES - (if (call != null) 1 else 0)
        val shown = if (others.size > room) others.take(room - 1).map { RailEntry.Profile(it, false) } + RailEntry.More else others.map { RailEntry.Profile(it, false) }
        return shown + listOfNotNull(call?.let { RailEntry.Profile(it, true) }) + TOOLS.map { RailEntry.Tool(it) }
    }

    /** Index de l'entrée à marquer : le profil affiché (ou « Plus » s'il est caché derrière), l'outil, -1 sinon (Réglages). */
    fun selected(entries: List<RailEntry>, mode: Mode, page: Int): Int = when (mode) {
        Mode.HOME -> entries.indexOfFirst { it is RailEntry.Profile && it.page == page }.takeIf { it >= 0 } ?: entries.indexOf(RailEntry.More)
        else -> entries.indexOf(RailEntry.Tool(mode))
    }
}
