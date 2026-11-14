package app.mate.remote

import android.content.Context
import app.mate.Computers
import app.mate.net.BackendClient
import app.mate.net.CyberpunkPresence
import app.mate.net.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Guette Cyberpunk 2077 sur chaque ordinateur Windows appairé, actif ou non (la tablette n'écoute que son ordinateur
 * actif, elle ne saurait pas qu'une partie démarre sur ATLAS quand elle est sur le Mac). Une requête légère
 * (`GET /cyberpunk/presence`, délai [CONNECT_TIMEOUT_MS]) toutes les [PERIOD_MS] (sauf l'ordinateur dont la page est ouverte), seulement appli au premier plan
 * (MainActivity : onResume / onPause). `onPresences` est appelé sur le fil principal ; null : l'ordinateur ne répond pas.
 */
class CyberpunkWatch(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Ordinateur dont la page est ouverte : pas sondé ici, le sondage de la page suffit (MainActivity relaie son statut). */
    private val pageComputerId: () -> String?,
    private val onPresences: (Map<String, CyberpunkPresence?>) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                onPresences(poll())
                delay(PERIOD_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun poll(): Map<String, CyberpunkPresence?> = coroutineScope {
        // Windows seulement : le mod CET ne tourne pas sur Mac. Un ordinateur à réappairer n'a pas de jeton utilisable.
        val skip = pageComputerId()
        val windows = Computers.all(context).filter { it.os == "windows" && !it.needsRepair && it.id != skip }
        windows.map { c ->
            async { c.id to runCatchingCancellable { BackendClient(c, CONNECT_TIMEOUT_MS).cyberpunk.presence() }.getOrNull() }
        }.awaitAll().toMap()
    }

    companion object {
        const val PERIOD_MS = 4_000L
        const val CONNECT_TIMEOUT_MS = 2_000
    }
}
