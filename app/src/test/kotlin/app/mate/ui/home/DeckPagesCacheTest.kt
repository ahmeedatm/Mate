package app.mate.ui.home

import app.mate.net.DeckPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class DeckPagesCacheTest {

    private var now = 0L
    private var calls = 0

    private fun pages(n: Int) = listOf(DeckPage("Page $n", emptyList()))

    private fun cache(ttlMs: Long = 10_000, fetch: suspend () -> List<DeckPage>) =
        DeckPagesCache(ttlMs, { now }, fetch)

    @Test
    fun `deux lectures rapprochees ne font qu'une requete`() = runBlocking {
        val cache = cache { pages(++calls) }
        val first = cache.get()
        now += 9_999
        assertEquals(first, cache.get())
        assertEquals(1, calls)
    }

    @Test
    fun `apres le delai la page est relue`() = runBlocking {
        val cache = cache { pages(++calls) }
        cache.get()
        now += 10_000
        assertEquals(pages(2), cache.get())
        assertEquals(2, calls)
    }

    @Test
    fun `force relit et invalidate aussi`() = runBlocking {
        val cache = cache { pages(++calls) }
        cache.get()
        assertEquals(pages(2), cache.get(force = true))
        cache.invalidate()
        assertEquals(pages(3), cache.get())
        assertEquals(3, calls)
    }

    @Test
    fun `deux lectures en meme temps partagent la requete`() = runBlocking {
        val gate = CompletableDeferred<List<DeckPage>>()
        val cache = cache { calls++; gate.await() }
        val a = async(start = CoroutineStart.UNDISPATCHED) { cache.get() }
        val b = async(start = CoroutineStart.UNDISPATCHED) { cache.get() }
        gate.complete(pages(1))
        assertEquals(listOf(pages(1), pages(1)), listOf(a, b).awaitAll())
        assertEquals(1, calls)
    }

    @Test
    fun `une erreur n'est pas gardee`() = runBlocking {
        val cache = cache { if (++calls == 1) throw IOException("hors ligne") else pages(calls) }
        try {
            cache.get()
            error("l'erreur aurait dû passer")
        } catch (_: IOException) {
        }
        assertEquals(pages(2), cache.get())
        assertEquals(2, calls)
    }

    @Test
    fun `une annulation n'est pas gardee`() = runBlocking {
        val never = CompletableDeferred<List<DeckPage>>()
        val cache = cache { if (++calls == 1) never.await() else pages(calls) }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { cache.get() }
        job.cancelAndJoin()
        assertEquals(pages(2), cache.get())
        assertEquals(2, calls)
    }

    @Test
    fun `invalidate pendant une requete force une nouvelle lecture`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val cache = cache {
            val n = ++calls
            if (n == 1) gate.await()
            pages(n)
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { cache.get() }
        cache.invalidate()
        gate.complete(Unit)
        assertEquals(pages(1), first.await())
        assertEquals(pages(2), cache.get())
        assertEquals(2, calls)
    }
}
