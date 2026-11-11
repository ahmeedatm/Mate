package app.mate.ui

import app.mate.Mode

/** Ce que la rangée de touches du bas montre et fait, sans vue (thèmes à `c_dock_height` > 0). */
object KeyDockModel {
    /** Une touche : son numéro écrit (« 01 ») et l'écran qu'elle ouvre. */
    data class Key(val number: String, val mode: Mode)

    /** Un segment de la DEL de la touche 01 : un profil, allumé s'il est affiché, `call` pour Réunion pendant un appel. */
    data class Segment(val page: Int, val on: Boolean, val call: Boolean)

    sealed interface HomeTap {
        data class Open(val page: Int) : HomeTap

        data object Profiles : HomeTap
    }

    /** Accueil, les outils gardés dans Réglages, Caméra, Réglages : numérotées dans cet ordre, sans trou. */
    fun keys(tools: List<Mode>): List<Key> =
        (listOf(Mode.HOME) + tools + listOf(Mode.CAMERA, Mode.SETTINGS)).mapIndexed { i, mode -> Key("%02d".format(i + 1), mode) }

    /** Index de la touche du mode affiché, -1 si aucune (outil masqué). */
    fun marked(keys: List<Key>, mode: Mode): Int = keys.indexOfFirst { it.mode == mode }

    fun segments(order: List<Int>, page: Int, callPage: Int, inCall: Boolean): List<Segment> =
        order.map { Segment(it, on = it == page, call = inCall && it == callPage) }

    /**
     * Toucher 01 : hors de l'Accueil, il rouvre le profil affiché ; déjà dessus, il passe au profil suivant (et reboucle) ;
     * seul profil, il ouvre la liste des profils (ajouter un écran).
     */
    fun homeTap(mode: Mode, order: List<Int>, page: Int): HomeTap {
        if (mode != Mode.HOME) return HomeTap.Open(page)
        if (order.size < 2) return HomeTap.Profiles
        return HomeTap.Open(order[(order.indexOf(page) + 1) % order.size])
    }
}
