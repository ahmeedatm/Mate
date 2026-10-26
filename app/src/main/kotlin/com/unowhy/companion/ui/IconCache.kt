package com.unowhy.companion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.unowhy.companion.net.BackendClient

/** Icônes des tuiles, gardées en mémoire (96 px chacune, quelques dizaines au plus). */
object IconCache {
    private val bitmaps = LruCache<String, Bitmap>(64)
    private val missing = HashSet<String>()

    fun peek(id: String): Bitmap? = bitmaps.get(id)

    suspend fun load(client: BackendClient, id: String): Bitmap? = load(id) { client.deck.tileIcon(id) }

    /** Icône d'une appli d'après son nom (vue « Ouvertes »). */
    suspend fun loadApp(client: BackendClient, name: String): Bitmap? = load(appKey(name)) { client.windows.appIcon(name) }

    fun peekApp(name: String): Bitmap? = peek(appKey(name))

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
