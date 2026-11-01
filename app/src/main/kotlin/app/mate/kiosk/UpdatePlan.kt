package app.mate.kiosk

import app.mate.net.AppUpdate

/** Choix des mises à jour, sans Android (testé dans app/src/test). */
object UpdatePlan {

    /** Vérification automatique : au démarrage, puis toutes les 6 h. */
    const val INTERVAL_MS = 6 * 3_600_000L

    /**
     * Par paquet : la plus haute versionCode. L'ordinateur n'annonce que des mises à jour de paquets déjà sur la tablette
     * (`installed`) : Android ne vérifie l'éditeur que d'une mise à jour, pas d'une première installation. Paquet aussi sur
     * GitHub : celui de GitHub, ou celui de l'ordinateur s'il est identique (même versionCode et même SHA-256, il télécharge
     * plus vite sur le réseau local) ; une autre version de l'ordinateur ne masque pas celle de GitHub. Les essais de PR
     * n'en font pas partie (release à part, voir `trial/`).
     */
    fun merge(computer: List<AppUpdate>, server: List<AppUpdate>, installed: Set<String>): List<AppUpdate> {
        val fromComputer = computer.filter { it.pkg in installed }
        return (fromComputer + server).map { it.pkg }.distinct().map { pkg ->
            val local = fromComputer.filter { it.pkg == pkg }.maxByOrNull { it.versionCode }
            val remote = server.filter { it.pkg == pkg }.maxByOrNull { it.versionCode }
            when {
                remote == null -> local!!
                local != null && local.versionCode == remote.versionCode && local.sha256.equals(remote.sha256, ignoreCase = true) -> local
                else -> remote
            }
        }
    }

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
