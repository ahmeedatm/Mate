package com.unowhy.unomate.focus

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.net.BackendClient
import kotlinx.coroutines.launch

/**
 * Ne pas déranger sur l'ordinateur pendant la concentration (pas pendant la pause). L'ordinateur ne rend que
 * ce qu'il a activé, et le rend seul si la tablette ne revient pas (backend/src/dnd.ts).
 */
class FocusDnd(private val activity: AppCompatActivity) {

    private var on: Boolean? = null

    fun follow(state: FocusTimer.State) {
        val wanted = state.running && state.phase == FocusTimer.Phase.FOCUS && Prefs.focusDnd(activity)
        if (wanted == on || (on == null && !wanted)) return
        on = wanted
        val minutes = ((state.remainingMs + 59_999) / 60_000).toInt()
        activity.lifecycleScope.launch { runCatching { BackendClient(activity).system.focusDnd(wanted, minutes) } }
    }
}
