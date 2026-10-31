package app.mate.remote

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mate.Computer
import app.mate.MainActivity
import app.mate.net.Link
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Canal avec l'ordinateur actif tant que l'appli est à l'écran : ordres de Claude (extension MCP),
 * pastille de connexion (la requête en attente prouve que l'ordinateur répond), bascule vers un autre
 * ordinateur enregistré quand l'actif ne répond plus.
 */
class RemoteLink(
    private val activity: MainActivity,
    private val onStatus: (Link) -> Unit,
    private val onSwitched: (Computer) -> Unit,
) {
    private var job: Job? = null

    /** (Re)lance le canal. L'ancien n'est pas attendu : son poll (jusqu'à 25 s) se termine dans le vide. */
    fun restart() {
        job?.cancel()
        job = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoteChannel(activity).run(onStatus, onSwitched)
            }
        }
    }
}
