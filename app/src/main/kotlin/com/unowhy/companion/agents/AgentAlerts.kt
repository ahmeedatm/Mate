package com.unowhy.companion.agents

import com.unowhy.companion.audio.AgentSounds
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.ui.AgentLight

/**
 * Alertes des agents Claude (ordre `agent` de l'ordinateur, voir backend/src/agents.ts) : une lumière sur le bord de
 * l'écran et un son court, jamais de voix ni de carte plein écran. L'ordinateur ne prévient pas quand un terminal ou
 * Orca est devant. Pendant le Focus, seul « attend » sonne ; la lumière reste.
 */
class AgentAlerts(private val light: AgentLight) {

    fun onOrder(kind: String, waiting: Int) {
        when (kind) {
            "waiting" -> {
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
            "clear" -> if (light.kind == AgentLight.Kind.WAITING) light.hide()
        }
    }

    /** Concentration en cours : pas de son pour les bonnes nouvelles. */
    private fun focusing(): Boolean {
        val s = FocusTimer.state
        return s.running && s.phase == FocusTimer.Phase.FOCUS
    }
}
