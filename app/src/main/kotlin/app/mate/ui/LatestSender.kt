package app.mate.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Envoie une valeur qui change vite (volume pendant un glisser) sans empiler les requêtes : une seule à la fois, puis
 * la plus récente arrivée entre-temps. À utiliser depuis le fil principal.
 */
class LatestSender<T : Any>(private val scope: CoroutineScope, private val send: suspend (T) -> Unit) {
    private var pending: T? = null
    private var busy = false

    fun offer(value: T) {
        pending = value
        if (busy) return
        busy = true
        scope.launch {
            try {
                while (true) {
                    val next = pending ?: break
                    pending = null
                    runCatching { send(next) }
                }
            } finally {
                busy = false
            }
        }
    }
}
