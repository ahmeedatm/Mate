package com.unowhy.companion.agents

import android.content.Context
import android.os.SystemClock
import com.unowhy.companion.net.AgentsState
import com.unowhy.companion.net.BackendClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Les sessions Claude Code de l'ordinateur, relues toutes les 3 s tant qu'au moins une vue les montre (onglet Agents,
 * widget) : une seule lecture pour toutes. Rien ne tourne sans observateur. Les alertes arrivent à part, par le canal
 * `/tablet` (remote/RemoteChannel).
 */
object AgentsFeed {

    /** Dernière lecture : l'état, ou l'erreur (déjà passée par `errorText` côté vue). `readAtMs` : horloge de la tablette. */
    class Snapshot(val state: AgentsState?, val error: Throwable?, val readAtMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = LinkedHashSet<(Snapshot) -> Unit>()
    private var job: Job? = null

    var last: Snapshot? = null
        private set

    fun observe(context: Context, listener: (Snapshot) -> Unit) {
        listeners += listener
        last?.let(listener)
        if (job != null) return
        val app = context.applicationContext
        job = scope.launch {
            while (isActive) {
                fetch(app)
                delay(POLL_MS)
            }
        }
    }

    fun unobserve(listener: (Snapshot) -> Unit) {
        listeners -= listener
        if (listeners.isEmpty()) {
            job?.cancel()
            job = null
        }
    }

    /** Relecture tout de suite (après une action) ; sans effet si personne n'observe. */
    fun refresh(context: Context) {
        if (listeners.isEmpty()) return
        val app = context.applicationContext
        scope.launch { fetch(app) }
    }

    private suspend fun fetch(context: Context) {
        val snapshot = try {
            Snapshot(BackendClient(context).agents.state(), null, SystemClock.elapsedRealtime())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Snapshot(null, e, SystemClock.elapsedRealtime())
        }
        last = snapshot
        listeners.toList().forEach { it(snapshot) }
    }

    private const val POLL_MS = 3_000L
}
