package app.mate.ui

import app.mate.Mode

/**
 * Ce que la rangée de touches du bas montre, sans vue (thèmes à `c_dock_height` > 0) : une grande touche numérotée par
 * écran de l'Accueil (Réunion seulement pendant un appel), « + » tant qu'il reste de la place, puis des boutons ronds
 * pour Focus, Ordi, Caméra et Réglages.
 */
object KeyDockModel {
    /** Plus d'écrans que ça : « + » disparaît (la liste des écrans sert encore à en ranger). */
    const val MAX_SCREENS = 9

    /** Au-delà de ce nombre d'écrans, les touches deviennent étroites et perdent leur icône. */
    const val COMPACT_FROM = 5

    /** Une grande touche : son numéro écrit (« 01 »), le profil qu'elle ouvre, `call` pour Réunion pendant un appel. */
    data class Screen(val number: String, val page: Int, val call: Boolean)

    /** Les écrans dans l'ordre de la barre (`order` : [app.mate.ui.home.HomeLayout.profilesInOrder]), numérotés sans trou. */
    fun screens(order: List<Int>, callPage: Int, inCall: Boolean): List<Screen> =
        order.mapIndexed { i, page -> Screen("%02d".format(i + 1), page, inCall && page == callPage) }

    /** Outils en boutons ronds : ceux gardés dans Réglages (Focus, Ordi), puis Caméra et Réglages. */
    fun tools(kept: List<Mode>): List<Mode> = kept + listOf(Mode.CAMERA, Mode.SETTINGS)

    fun showAdd(screens: Int): Boolean = screens < MAX_SCREENS

    fun compact(screens: Int): Boolean = screens > COMPACT_FROM

    /** Index de la touche d'écran affichée (-1 hors de l'Accueil), de l'outil du mode (-1 s'il n'en a pas : outil masqué). */
    fun markedScreen(screens: List<Screen>, mode: Mode, page: Int): Int =
        if (mode == Mode.HOME) screens.indexOfFirst { it.page == page } else -1

    fun markedTool(tools: List<Mode>, mode: Mode): Int = tools.indexOf(mode)

    /** Numéro écrit du profil (sur l'aplat du balayage), vide s'il n'a pas de touche. */
    fun numberOf(screens: List<Screen>, page: Int): String = screens.firstOrNull { it.page == page }?.number.orEmpty()

    /** Suite que parcourt le balayage vertical : tous les écrans, puis les outils gardés (Caméra et Réglages n'y sont pas). */
    fun stepEntries(screens: List<Screen>, kept: List<Mode>): List<RailEntry> =
        screens.map { RailEntry.Profile(it.page, it.call) } + kept.map { RailEntry.Tool(it) }
}
