package com.unowhy.companion.agents

import com.unowhy.companion.audio.AgentSounds
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.ui.AgentLight

/**
 * Alertes des agents Claude (ordre `agent` de l'ordinateur, voir backend/src/agents.ts) : une lumière sur le bord de
 * l'écran et un son court, jamais de voix ni de carte plein écran. L'ordinateur ne prévient pas quand un terminal ou
 * Orca est devant. Pendant le Focus, seul « attend » sonne ; la lumière reste.
 */
class AgentAlerts(
    private val light: AgentLight,
    /** Une demande est à la tablette : l'ouvrir (carte). */
    private val openAsk: (String) -> Unit,
    /** Rien à répondre d'ici : l'onglet Agents. */
    private val openList: () -> Unit,
) {

    /** Dernière demande annoncée, tant que l'ordinateur ne l'a pas retirée ; null : on ne peut que regarder. */
    private var askId: String? = null

    val pendingAsk get() = askId

    /** Le bord allumé est touché. */
    fun onTouched() {
        val id = askId
        if (id != null) openAsk(id) else openList()
    }

    fun onOrder(kind: String, askId: String?, waiting: Int) {
        when (kind) {
            "waiting" -> {
                this.askId = askId
                light.show(AgentLight.Kind.WAITING)
                AgentSounds.waiting()
            }
            "done" -> {
                // L'ambre d'un autre agent qui attend encore reste : un « fini » ne l'éteint pas.
                if (waiting == 0) light.show(AgentLight.Kind.DONE)
                if (!focusing()) AgentSounds.done()
            }
            "limit" -> {
                if (waiting == 0) light.show(AgentLight.Kind.LIMIT)
                if (!focusing()) AgentSounds.done()
            }
            "clear" -> {
                this.askId = null
                if (light.kind == AgentLight.Kind.WAITING) light.hide()
            }
        }
    }

    /**
     * Ordinateur (re)joint, appli relancée : une demande peut attendre depuis avant (l'ordre `waiting` est passé). La
     * lumière se rallume, sans son ; la tablette pourra y répondre.
     */
    fun resync(waitingAsk: String?) {
        if (waitingAsk == null || askId == waitingAsk) return
        askId = waitingAsk
        light.show(AgentLight.Kind.WAITING)
    }

    /** Concentration en cours : pas de son pour les bonnes nouvelles. */
    private fun focusing(): Boolean {
        val s = FocusTimer.state
        return s.running && s.phase == FocusTimer.Phase.FOCUS
    }
}
