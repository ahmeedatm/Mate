package app.mate.ui.cyberpunk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TileCacheTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test fun `l'ancienne entree sort quand la taille depasse`() {
        val lru = BoundedLru<String, ByteArray>(100) { it.size }
        lru.put("a", ByteArray(40))
        lru.put("b", ByteArray(40))
        lru.get("a")
        lru.put("c", ByteArray(40))
        assertNotNull(lru.get("a"))
        assertNull(lru.get("b"))
        assertNotNull(lru.get("c"))
    }

    @Test fun `pas de double demande pour une meme tuile`() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<ByteArray?>()
        val changed = AtomicInteger()
        val cache = TileCache<ByteArray>(scope, 1000, { it.size }, { changed.incrementAndGet() }) { calls.incrementAndGet(); gate.await() }
        val k = TileKey(3, 1, 2)
        assertNull(cache.get(k))
        assertNull(cache.get(k))
        assertEquals(1, calls.get())
        gate.complete(ByteArray(10))
        assertNotNull(cache.get(k))
        assertEquals(1, changed.get())
        assertEquals(1, calls.get())
    }

    @Test fun `tuile absente 404, rien et pas de nouvelle demande`() {
        val calls = AtomicInteger()
        val cache = TileCache<ByteArray>(scope, 1000, { it.size }, {}) { calls.incrementAndGet(); null }
        val k = TileKey(0, 0, 0)
        assertNull(cache.get(k))
        assertNull(cache.get(k))
        assertEquals(1, calls.get())
    }

    @Test fun `echec reseau, nouvelle demande au prochain passage`() {
        val calls = AtomicInteger()
        val cache = TileCache<ByteArray>(scope, 1000, { it.size }, {}) { calls.incrementAndGet(); error("réseau") }
        val k = TileKey(1, 0, 0)
        assertNull(cache.get(k))
        assertNull(cache.get(k))
        assertEquals(2, calls.get())
    }
}
