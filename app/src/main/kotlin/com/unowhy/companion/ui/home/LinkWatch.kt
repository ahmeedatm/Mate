package com.unowhy.companion.ui.home

import com.unowhy.companion.net.Link
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** L'ordinateur actif ne répond plus depuis [sinceMs]. */
data class Down(val name: String, val sinceMs: Long) {
    /** « 13:26 », l'heure du début de la coupure. */
    fun since(): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(sinceMs))
}

/**
 * Suit les états de la liaison pour dire à l'Accueil Cadran « <nom> ne répond pas depuis HH:MM ». Une coupure ne compte
 * qu'après [GRACE_MS] : l'état injoignable posé au lancement et à chaque changement d'ordinateur dure le temps du
 * premier essai. Un autre ordinateur, ou un retour en ligne, repart de zéro. Un jeton refusé n'est pas une coupure.
 */
class LinkWatch {
    private var computerId: String? = null
    private var offlineFromMs = 0L

    fun onLink(computerId: String?, state: Link.State, nowMs: Long) {
        if (computerId != this.computerId) offlineFromMs = 0L
        this.computerId = computerId
        offlineFromMs = when {
            state != Link.State.OFFLINE -> 0L
            offlineFromMs == 0L -> nowMs
            else -> offlineFromMs
        }
    }

    /** La coupure à dire, ou null si l'ordinateur répond (ou ne manque que depuis un instant). */
    fun down(name: String?, nowMs: Long): Down? =
        if (name != null && offlineFromMs != 0L && nowMs - offlineFromMs >= GRACE_MS) Down(name, offlineFromMs) else null

    companion object {
        const val GRACE_MS = 10_000L
    }
}
