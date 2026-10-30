package com.unowhy.unomate.ui

import com.unowhy.unomate.net.DeckPage

/**
 * Appel en cours sur l'ordinateur (Deck V2), sans vue : il commence par l'ordre `meeting` de l'ordinateur (Teams,
 * Discord : micro utilisé) ou par « Rejoindre » sur la tablette (toute réunion en ligne, avec l'heure de fin de
 * l'événement quand l'ordinateur ne sait pas dire qu'elle est finie). Il dit quand basculer sur le profil Réunion et où
 * revenir ensuite ; MainActivity applique.
 */
class CallSession(private val now: () -> Long = System::currentTimeMillis) {

    /** Appli de l'appel (nom du deck : « Microsoft Teams », « Discord »… ou celle de la réunion rejointe), null hors appel. */
    var app: String? = null
        private set

    private var endAtMs: Long? = null

    val active: Boolean get() = app != null

    /**
     * Début d'appel ; vrai s'il vient de commencer (pas déjà en cours). `endAtMs` : fin prévue (« Rejoindre »). L'ordre de
     * l'ordinateur pour la même appli retire cette heure : c'est lui qui dira la fin.
     */
    fun start(app: String, endAtMs: Long? = null): Boolean {
        val fresh = this.app == null
        if (!fresh && this.app == app && endAtMs == null) this.endAtMs = null
        if (fresh) {
            this.app = app
            this.endAtMs = endAtMs
        }
        return fresh
    }

    fun end() {
        app = null
        endAtMs = null
    }

    /** À appeler régulièrement (chaque minute) : vrai si l'appel vient de finir à son heure de fin. */
    fun tick(): Boolean {
        val end = endAtMs ?: return false
        if (now() < end) return false
        end()
        return true
    }

    /**
     * Retour en ligne, l'ordinateur ne voit plus d'appel : un appel qu'il avait signalé (sans heure de fin) a fini pendant
     * la coupure, son ordre `end` s'est perdu.
     */
    fun endsOnResync(live: List<String>): Boolean = active && endAtMs == null && live.isEmpty()

    /** Passer sur Réunion ? Jamais pendant une saisie ni dans un écran plein (Caméra, mode Modifier). */
    fun shouldSwitch(typing: Boolean, fullScreen: Boolean): Boolean = active && !typing && !fullScreen

    /** Fin d'appel : page où revenir (celle d'avant) si l'écran montre encore Réunion, null s'il est parti ailleurs. */
    fun returnTo(currentPage: Int?, callPage: Int, previousPage: Int): Int? = previousPage.takeIf { currentPage == callPage }

    companion object {
        /** Réunion rejointe (`kind` de POST /calendar/join) → nom de l'appli, comme le deck la nomme. */
        fun appOfKind(kind: String?): String = when (kind) {
            "teams" -> "Microsoft Teams"
            "meet" -> "Google Meet"
            "zoom" -> "Zoom"
            "webex" -> "Webex"
            else -> "Réunion"
        }

        /** Page du deck de l'appel : la page Réunion (`meeting`) pour Teams, la page de l'appli sinon ; null : aucune. */
        fun pageFor(app: String?, pages: List<DeckPage>): DeckPage? {
            if (app == null) return null
            val mine = pages.filter { it.app.equals(app, ignoreCase = true) }
            return mine.firstOrNull { it.meeting } ?: mine.firstOrNull()
        }

        /** Appli sans page (Meet, Zoom, Webex) : ce que l'ordinateur sait faire partout, la touche « Couper le micro ». */
        fun fallbackPage(pages: List<DeckPage>): DeckPage? {
            // Commande système seulement : « Micro » de Teams ou « Couper mon micro » de Discord appuient dans leur appli.
            val tiles = pages.flatMap { it.tiles }.filter { it.symbol == "mic_off" && it.type == "system" }.distinctBy { it.id }.take(1)
            return if (tiles.isEmpty()) null else DeckPage("Réunion", tiles, false, false, false, null, false, true, false)
        }
    }
}
