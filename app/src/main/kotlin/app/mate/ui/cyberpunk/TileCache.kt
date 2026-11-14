package app.mate.ui.cyberpunk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Cache borné par taille : la dernière entrée lue reste, la plus ancienne sort. Pas thread-safe : l'appelant synchronise. */
class BoundedLru<K, V : Any>(private val maxSize: Int, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var size = 0

    fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map.put(key, value)?.let { size -= sizeOf(it) }
        size += sizeOf(value)
        val it = map.entries.iterator()
        while (size > maxSize && it.hasNext()) {
            val oldest = it.next()
            if (oldest.key == key) continue
            size -= sizeOf(oldest.value)
            it.remove()
        }
    }
}

/**
 * Tuiles décodées. `get` rend la tuile ou null et lance son chargement (`load`, hors thread principal) une seule fois :
 * pas de seconde demande tant qu'elle est en route. Tuile absente (`load` rend null, 404) : mémorisée, plus jamais demandée.
 * Échec réseau : rien n'est mémorisé, la tuile sera redemandée au prochain `get`. `onLoaded` dit qu'il faut redessiner.
 */
class TileCache<T : Any>(
    private val scope: CoroutineScope,
    maxSize: Int,
    sizeOf: (T) -> Int,
    private val onLoaded: () -> Unit,
    private val load: suspend (TileKey) -> T?,
) {
    private val lru = BoundedLru<TileKey, T>(maxSize, sizeOf)
    private val pending = HashSet<TileKey>()
    private val missing = HashSet<TileKey>()
    private val lock = Any()
    private val slots = Semaphore(PARALLEL)

    fun peek(key: TileKey): T? = synchronized(lock) { lru.get(key) }

    fun get(key: TileKey): T? {
        synchronized(lock) {
            lru.get(key)?.let { return it }
            if (key in missing || !pending.add(key)) return null
        }
        scope.launch {
            val tile = try {
                slots.withPermit { load(key) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                synchronized(lock) { pending.remove(key) }
                throw e
            } catch (_: Exception) {
                synchronized(lock) { pending.remove(key) }
                return@launch
            }
            synchronized(lock) {
                pending.remove(key)
                if (tile == null) missing.add(key) else lru.put(key, tile)
            }
            if (tile != null) onLoaded()
        }
        return null
    }

    private companion object {
        const val PARALLEL = 4
    }
}
