package app.mate.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.mate.net.BackendClient

/**
 * Icônes des tuiles, gardées en mémoire (96 à 128 px chacune, 64 Ko au plus) : quelques dizaines pour le Deck, et jusqu'à
 * une trentaine pour une partie de Valorant (10 agents, leurs rangs, mes dernières parties), soit 6 Mo au plus.
 */
object IconCache {
    private val bitmaps = LruCache<String, Bitmap>(96)
    private val missing = HashSet<String>()

    fun peek(id: String): Bitmap? = bitmaps.get(id)

    suspend fun load(client: BackendClient, id: String): Bitmap? = load(id) { client.deck.tileIcon(id) }

    /** Icône d'une appli d'après son nom (vue « Ouvertes »). */
    suspend fun loadApp(client: BackendClient, name: String): Bitmap? = load(appKey(name)) { client.windows.appIcon(name) }

    fun peekApp(name: String): Bitmap? = peek(appKey(name))

    /** Photo d'un participant d'un appel Discord (64 px, servie par l'ordinateur). */
    suspend fun loadDiscord(client: BackendClient, id: String): Bitmap? = load(discordKey(id)) { client.discord.avatar(id) }

    fun peekDiscord(id: String): Bitmap? = peek(discordKey(id))

    /** Icône carrée d'un agent Valorant et logo d'un rang (128 px, servis par l'ordinateur depuis valorant-api.com). */
    suspend fun loadAgent(client: BackendClient, id: String): Bitmap? = load(agentKey(id)) { client.valorant.agentIcon(id) }

    fun peekAgent(id: String): Bitmap? = peek(agentKey(id))

    suspend fun loadRank(client: BackendClient, tier: Int): Bitmap? = load(rankKey(tier)) { client.valorant.rankIcon(tier) }

    fun peekRank(tier: Int): Bitmap? = peek(rankKey(tier))

    private fun agentKey(id: String) = "valorant-agent:$id"

    private fun rankKey(tier: Int) = "valorant-rank:$tier"

    private fun discordKey(id: String) = "discord:$id"

    private fun appKey(name: String) = "app:$name"

    private suspend fun load(id: String, fetch: suspend () -> ByteArray?): Bitmap? {
        bitmaps.get(id)?.let { return it }
        if (id in missing) return null
        // Erreur réseau : on réessaiera plus tard. 404 : l'appli n'a pas d'icône, inutile de redemander.
        val bytes = try { fetch() } catch (e: Exception) { return null }
        if (bytes == null) { missing += id; return null }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bitmaps.put(id, bitmap)
        return bitmap
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
}
