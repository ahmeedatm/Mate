package app.mate.kiosk

import app.mate.net.AppUpdate

/** Choix des mises à jour, sans Android (testé dans app/src/test). */
object UpdatePlan {

    /** Vérification automatique : au démarrage, puis toutes les 6 h. */
    const val INTERVAL_MS = 6 * 3_600_000L

    /**
     * Par paquet, dans l'ordre d'arrivée : la plus haute versionCode. À égalité, celle de la première liste (l'ordinateur,
     * sur le réseau local, télécharge plus vite). Les essais de PR n'en font pas partie (release à part, voir `trial/`).
     */
    fun merge(vararg sources: List<AppUpdate>): List<AppUpdate> =
        sources.flatMap { it }.groupBy { it.pkg }.values.map { same -> same.maxBy { it.versionCode } }

    /** Mise à jour installée sans personne : la release de main du Mate (`pkg`) plus récente que l'installée. */
    fun auto(updates: List<AppUpdate>, pkg: String, installedCode: Long): AppUpdate? =
        updates.filter { it.pkg == pkg && it.versionCode > installedCode }.maxByOrNull { it.versionCode }

    enum class State { NEWER, INSTALLED, OLDER }

    /** Par rapport à la version installée (`null` = paquet absent). OLDER : Android refuse d'installer une versionCode plus basse. */
    fun state(update: AppUpdate, installedCode: Long?): State = when {
        installedCode == null || update.versionCode > installedCode -> State.NEWER
        update.versionCode == installedCode -> State.INSTALLED
        else -> State.OLDER
    }

    /** Vérification due : jamais faite, faite il y a `intervalMs` ou plus, ou horloge revenue en arrière. */
    fun isDue(lastMs: Long?, nowMs: Long, intervalMs: Long = INTERVAL_MS): Boolean =
        lastMs == null || nowMs < lastMs || nowMs - lastMs >= intervalMs

    /**
     * Une vérification a abouti dès qu'au moins une source a répondu : on peut attendre l'intervalle plein avant la
     * suivante. Démarrage hors ligne, toutes les sources tentées en erreur : rien n'a abouti, à refaire au tick suivant.
     * Un 401/404 de GitHub n'est pas une erreur (aucune mise à jour) ; aucune source tentée non plus.
     */
    fun reachedAnySource(attempted: Int, errored: Int): Boolean = attempted == 0 || errored < attempted
}
