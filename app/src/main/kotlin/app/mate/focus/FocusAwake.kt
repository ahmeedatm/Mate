package app.mate.focus

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mate.net.BackendClient
import kotlinx.coroutines.launch

/**
 * Écran de l'ordinateur gardé allumé pendant la concentration (pas pendant la pause). L'ordinateur rend la veille
 * seul si la tablette ne revient pas (backend/src/awake.ts).
 */
class FocusAwake(private val activity: AppCompatActivity) {

    // shortcut: un ordre perdu (WiFi) n'est pas renvoyé et un changement d'ordinateur en plein Focus n'est pas suivi (comme FocusDnd), le minuteur de secours de l'ordinateur rend la veille ; à revoir si ça gêne.
    private var on: Boolean? = null

    fun follow(state: FocusTimer.State) {
        val wanted = wanted(state)
        if (wanted == on || (on == null && !wanted)) return
        on = wanted
        activity.lifecycleScope.launch { runCatching { BackendClient(activity).system.focusAwake(wanted, minutesLeft(state)) } }
    }

    companion object {
        fun wanted(state: FocusTimer.State) = state.running && state.phase == FocusTimer.Phase.FOCUS

        /** Temps restant arrondi à la minute au-dessus, pour le minuteur de secours de l'ordinateur. */
        fun minutesLeft(state: FocusTimer.State) = ((state.remainingMs + 59_999) / 60_000).toInt()
    }
}
