package app.mate.ui.home

import app.mate.net.DeckPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pages du deck lues une fois pour tous les widgets : `GET /deck` reconstruit toutes les pages côté ordinateur.
 * Une lecture garde ses pages [ttlMs] ; deux lectures en même temps partagent la requête. Une erreur ou une
 * annulation ne laisse rien en mémoire.
 */
class DeckPagesCache(
    private val ttlMs: Long = 10_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val fetch: suspend () -> List<DeckPage>,
) {
    private val lock = Mutex()
    private var pages: List<DeckPage>? = null
    private var readAt = 0L

    /** Change à chaque [invalidate] : une requête partie avant ne garde pas ses pages. */
    @Volatile private var gen = 0

    suspend fun get(force: Boolean = false): List<DeckPage> = lock.withLock {
        val cached = pages
        if (!force && cached != null && clock() - readAt < ttlMs) return cached
        val startedAt = gen
        fetch().also {
            if (startedAt == gen) {
                pages = it
                readAt = clock()
            }
        }
    }

    fun invalidate() {
        gen++
        pages = null
    }
}
