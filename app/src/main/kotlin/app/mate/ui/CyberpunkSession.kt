package app.mate.ui

import app.mate.net.CyberpunkPresence

/**
 * Décide quand la page Cyberpunk s'ouvre et se ferme toute seule, à partir de la présence du jeu sur chaque ordinateur
 * Windows appairé (sondée par remote/CyberpunkWatch) ; MainActivity applique. Ouvre quand `inGame` passe à vrai (en
 * passant sur l'ordinateur du jeu s'il n'est pas actif), ferme quand il est faux depuis [CLOSE_AFTER_MS] et rend
 * l'ordinateur d'avant, sauf si Ahmed en a choisi un autre entre-temps. Jamais pendant une saisie ni un appel. Une
 * fermeture à la main (bouton Fermer) ne rouvre pas la page tant que la même `session` du jeu dure : [resumeOn] donne
 * alors l'ordinateur du jeu, pour l'icône de retour de la barre.
 */
class CyberpunkSession(private val now: () -> Long = System::currentTimeMillis) {

    enum class Step { NONE, OPEN, CLOSE }

    /** `activeId` : ordinateur piloté ; `pageOpen` : la page est affichée. */
    data class Context(val activeId: String?, val typing: Boolean, val inCall: Boolean, val pageOpen: Boolean)

    /** `computerId` : ordinateur du jeu (OPEN) ; `switchTo` : à rendre actif avant d'ouvrir ; `returnTo` : à rendre actif en fermant. */
    data class Decision(val step: Step, val computerId: String? = null, val switchTo: String? = null, val returnTo: String? = null)

    private var tracking = false
    private var before: String? = null
    private var openedOn: String? = null
    private var dismissed: String? = null
    private var lastInGameMs = 0L

    /** Partie en cours dont la page a été fermée à la main : ordinateur du jeu, sinon null (pas d'icône de retour). */
    var resumeOn: String? = null
        private set

    /** Croix de la page : l'icône de retour paraît tout de suite, sans attendre la relève suivante. */
    fun closedByHand(computerId: String) {
        if (tracking) resumeOn = computerId
    }

    /** `presences` : null pour un ordinateur qui ne répond pas (compte comme « pas en jeu »). */
    fun decide(presences: Map<String, CyberpunkPresence?>, ctx: Context): Decision {
        val playing = presences.filterValues { it != null && it.available && it.inGame }
        val pick = playing.entries.firstOrNull { it.key == ctx.activeId } ?: playing.entries.firstOrNull()
        val busy = ctx.typing || ctx.inCall
        if (pick == null) return noGame(ctx, busy)
        lastInGameMs = now()
        val key = pick.value?.session ?: pick.key
        if (tracking && !ctx.pageOpen && dismissed == null) dismissed = key
        resumeOn = pick.key.takeIf { !ctx.pageOpen && dismissed == key }
        if (ctx.pageOpen || busy || dismissed == key) return Decision(Step.NONE)
        val switchTo = pick.key.takeIf { it != ctx.activeId }
        if (switchTo != null && before == null) before = ctx.activeId
        tracking = true
        openedOn = pick.key
        dismissed = null
        resumeOn = null
        return Decision(Step.OPEN, computerId = pick.key, switchTo = switchTo)
    }

    private fun noGame(ctx: Context, busy: Boolean): Decision {
        if (!tracking || busy || now() - lastInGameMs < CLOSE_AFTER_MS) return Decision(Step.NONE)
        val back = before.takeIf { openedOn != null && ctx.activeId == openedOn }
        reset()
        return Decision(Step.CLOSE, returnTo = back)
    }

    fun reset() {
        tracking = false
        before = null
        openedOn = null
        dismissed = null
        resumeOn = null
    }

    companion object {
        const val CLOSE_AFTER_MS = 10_000L
    }
}
