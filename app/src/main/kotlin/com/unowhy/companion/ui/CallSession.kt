package com.unowhy.companion.ui

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
    }
}
