package app.mate.ui.cyberpunk

import app.mate.net.CyberpunkThread

/** Règles pures de l'onglet Messages du tiroir Téléphone. */
object PhoneInbox {
    /** Après une réponse, le fil se relit toutes les 2 s, pendant 12 s au plus (Takemura a répondu en ~10 s au spike). */
    const val RELOAD_MS = 2_000L
    const val RELOAD_WINDOW_MS = 12_000L

    /**
     * Fils à lister : non lus ou à répondre, plus ceux ouverts depuis l'ouverture du tiroir (`opened`, des hash).
     * Ordre du jeu : à répondre d'abord, puis non lus, puis le reste ; l'ordre du jeu est gardé à égalité.
     */
    fun messages(threads: List<CyberpunkThread>, opened: Set<String>): List<CyberpunkThread> =
        threads.filter { it.unread > 0 || it.canReply || it.hash in opened }
            .sortedBy { if (it.canReply) 0 else if (it.unread > 0) 1 else 2 }

    /**
     * Le contact « tape » avant de répondre, en plusieurs messages : on relit pendant toute la fenêtre, sauf si le fil
     * a bougé et propose déjà de nouveaux choix (c'est au joueur de jouer).
     */
    fun shouldReload(sentVersion: String, currentVersion: String, hasChoices: Boolean, elapsedMs: Long) =
        elapsedMs < RELOAD_WINDOW_MS && !(currentVersion != sentVersion && hasChoices)
}
