package app.mate.ui.home

import app.mate.net.AgentState

/**
 * Habillage Instrument du widget Claude (#243) : les états se lisent sur de petites DEL rectangulaires (comme le dock)
 * au lieu des pastilles de couleur. Le texte d'état reste à côté : l'état ne repose jamais sur la seule couleur.
 */
object AgentsLook {
    /**
     * La DEL est allumée quand l'état demande l'œil : la session attend (ou a atteint sa limite), ou travaille.
     * Éteinte : rien à faire (fini, au repos), une session qui surveille une tâche de fond, ou un compteur à zéro
     * ([n] = ce que le compteur compte, 1 pour une session).
     */
    fun lit(state: AgentState, monitoring: Boolean, n: Int = 1): Boolean = n > 0 && when (state) {
        AgentState.WAITING, AgentState.LIMIT -> true
        AgentState.WORKING -> !monitoring
        AgentState.DONE, AgentState.IDLE -> false
    }

    /** DEL d'une icône d'état : 14 × 4 dp comme `dock_led`. */
    const val LED_WIDTH_DP = 14
    const val LED_HEIGHT_DP = 4
}
