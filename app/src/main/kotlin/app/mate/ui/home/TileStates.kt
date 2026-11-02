package app.mate.ui.home

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Touches allumées d'après l'état réel de l'ordinateur (micro coupé, Ne pas déranger, appli lancée) : l'ordre
 * `tilestate` du canal donne la liste complète des ids, chaque ordre remplace la précédente. Les cases la lisent à
 * l'affichage (`TileGridView.bind`) et se redessinent quand elle change ([listen]).
 */
object TileStates {
    @Volatile
    private var lit: Set<String> = emptySet()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun isLit(id: String) = id in lit

    /** Vrai si la liste a changé ; les écouteurs ne sont prévenus que dans ce cas. */
    fun update(ids: Collection<String>): Boolean {
        val next = ids.toSet()
        if (next == lit) return false
        lit = next
        listeners.forEach { it() }
        return true
    }

    /** Renvoie la fonction qui arrête l'écoute. */
    fun listen(onChange: () -> Unit): () -> Unit {
        listeners.add(onChange)
        return { listeners.remove(onChange) }
    }
}
