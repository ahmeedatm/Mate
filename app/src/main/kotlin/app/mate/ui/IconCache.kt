package app.mate.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import app.mate.net.BackendClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Icônes des tuiles, gardées en mémoire (96 à 128 px chacune, 64 Ko au plus) : quelques dizaines pour le Deck, et jusqu'à
 * une trentaine pour une partie de Valorant (10 agents, leurs rangs, mes dernières parties), soit 6 Mo au plus.
 */
object IconCache {
    private val bitmaps = LruCache<String, Bitmap>(96)

    /** Images de carte Valorant (960 × 540, ~1 Mo chacune en 16 bits) : la carte jouée et la précédente seulement. */
    private val maps = LruCache<String, Bitmap>(2)
    /** Absentes (404) jusqu'à l'instant noté : pour toujours pour le Deck, 5 min pour Valorant (compagnon pas encore à jour). */
    private val missing = HashMap<String, Long>()

    /** Chargements en cours : dix joueurs du même rang ne demandent le logo qu'une fois. */
    private val loading = HashMap<String, CompletableDeferred<Bitmap?>>()

    fun peek(id: String): Bitmap? = bitmaps.get(id)

    suspend fun load(client: BackendClient, id: String): Bitmap? = load(id) { client.deck.tileIcon(id) }

    /** Icône d'une appli d'après son nom (vue « Ouvertes »). */
    suspend fun loadApp(client: BackendClient, name: String): Bitmap? = load(appKey(name)) { client.windows.appIcon(name) }

    fun peekApp(name: String): Bitmap? = peek(appKey(name))

    /** Photo d'un participant d'un appel Discord (64 px, servie par l'ordinateur). */
    suspend fun loadDiscord(client: BackendClient, id: String): Bitmap? = load(discordKey(id)) { client.discord.avatar(id) }

    fun peekDiscord(id: String): Bitmap? = peek(discordKey(id))

    /** Icône carrée d'un agent Valorant et logo d'un rang (128 px, servis par l'ordinateur depuis valorant-api.com). */
    suspend fun loadAgent(client: BackendClient, id: String): Bitmap? = load(agentKey(id), VALORANT_MISS_MS) { client.valorant.agentIcon(id) }

    fun peekAgent(id: String): Bitmap? = peek(agentKey(id))

    suspend fun loadRank(client: BackendClient, tier: Int): Bitmap? = load(rankKey(tier), VALORANT_MISS_MS) { client.valorant.rankIcon(tier) }

    fun peekRank(tier: Int): Bitmap? = peek(rankKey(tier))

    /** Image de fond d'une carte Valorant : décodée sans transparence (moitié moins de mémoire), à part des icônes. */
    suspend fun loadMap(client: BackendClient, id: String): Bitmap? = load(mapKey(id), VALORANT_MISS_MS, maps, Bitmap.Config.RGB_565) { client.valorant.mapImage(id) }

    fun peekMap(id: String): Bitmap? = maps.get(mapKey(id))

    private fun mapKey(id: String) = "valorant-map:$id"

    private fun agentKey(id: String) = "valorant-agent:$id"

    private fun rankKey(tier: Int) = "valorant-rank:$tier"

    private fun discordKey(id: String) = "discord:$id"

    private fun appKey(name: String) = "app:$name"

    /** Appelé depuis le fil principal (les tables ne sont pas partagées entre fils) ; `fetch` passe par `Http.io`. */
    private suspend fun load(
        id: String,
        missFor: Long = Long.MAX_VALUE,
        cache: LruCache<String, Bitmap> = bitmaps,
        config: Bitmap.Config? = null,
        fetch: suspend () -> ByteArray?,
    ): Bitmap? {
        cache.get(id)?.let { return it }
        if ((missing[id] ?: 0L) > SystemClock.elapsedRealtime()) return null
        loading[id]?.let { return it.await() }
        val result = CompletableDeferred<Bitmap?>()
        loading[id] = result
        try {
            // Erreur réseau : on réessaiera plus tard. 404 : pas d'icône, inutile de redemander (pendant `missFor`).
            val bytes = try { fetch() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
            if (bytes == null) {
                missing[id] = if (missFor == Long.MAX_VALUE) Long.MAX_VALUE else SystemClock.elapsedRealtime() + missFor
                return null
            }
            val options = BitmapFactory.Options().apply { config?.let { inPreferredConfig = it } }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (bitmap != null) cache.put(id, bitmap)
            result.complete(bitmap)
            return bitmap
        } finally {
            // Échec, 404 ou annulation : ceux qui attendaient reçoivent null (sans effet si déjà complété).
            result.complete(null)
            loading.remove(id)
        }
    }

    /** Une touche perso reprogrammée change d'icône sans changer d'identifiant. */
    fun forget(id: String) {
        bitmaps.remove(id)
        missing.remove(id)
    }

    /** À appeler quand le deck change côté Mac (les identifiants de tuiles sont positionnels). */
    fun clear() {
        bitmaps.evictAll()
        missing.clear()
    }

    private const val VALORANT_MISS_MS = 5 * 60_000L
}
