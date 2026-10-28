package com.unowhy.companion.kiosk

import com.unowhy.companion.net.AppUpdate

/** Choix des mises à jour, sans Android (testé dans app/src/test). */
object UpdatePlan {

    /** Vérification automatique : au démarrage, puis toutes les 6 h. */
    const val INTERVAL_MS = 6 * 3_600_000L

    /** Essai depuis une MR (job essai-android) : versionName `X.Y.Z-essai.N`. Les releases de main sont en `X.Y.Z`. */
    fun isTrial(update: AppUpdate): Boolean = TRIAL.containsMatchIn(update.versionName)

    private val TRIAL = Regex("""-essai\.\d+$""")

    /**
     * Par paquet, dans l'ordre d'arrivée : la plus haute versionCode de main, puis celle des essais. Un essai n'est
     * jamais masqué par une release plus récente, ni l'inverse. À égalité, celle de la première liste (l'ordinateur,
     * sur le réseau local, télécharge plus vite).
     */
    fun merge(vararg sources: List<AppUpdate>): List<AppUpdate> =
        sources.flatMap { it }.groupBy { it.pkg }.values.flatMap { same ->
            same.groupBy { isTrial(it) }.toSortedMap().values.map { channel -> channel.maxBy { it.versionCode } }
        }

    /** Mise à jour installée sans personne : la release de main du Companion (`pkg`) plus récente que l'installée, jamais un essai. */
    fun auto(updates: List<AppUpdate>, pkg: String, installedCode: Long): AppUpdate? =
        updates.filter { it.pkg == pkg && !isTrial(it) && it.versionCode > installedCode }.maxByOrNull { it.versionCode }

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
     * Un 401/404 du serveur central n'est pas une erreur (aucune mise à jour) ; aucune source tentée non plus.
     */
    fun reachedAnySource(attempted: Int, errored: Int): Boolean = attempted == 0 || errored < attempted
}
