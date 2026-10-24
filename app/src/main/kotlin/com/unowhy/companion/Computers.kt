package com.unowhy.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un ordinateur appairé. `id` : identifiant donné par son backend (il reste le même si son adresse IP change) ;
 * `os` : "mac" ou "windows" (raccourcis proposés dans l'éditeur de touches).
 */
data class Computer(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
    val os: String,
    val lastUsedMs: Long = 0,
) {
    /** Enregistré avant l'appairage par code (adresse saisie, ancienne version) : `id` à remplacer par le vrai. */
    val provisional: Boolean get() = id.startsWith(PROVISIONAL)

    /** Adresse lisible : "192.168.1.20:3000". */
    val address: String get() = url.removePrefix("http://").removePrefix("https://")

    companion object {
        const val PROVISIONAL = "url:"
    }
}

/**
 * Ordinateurs connus de la tablette (bureau, maison…) et celui qu'elle pilote. Chaque ordinateur garde ses
 * propres données (agenda, tâches, deck, applis, écrans) : la tablette ne stocke que cette liste.
 */
object Computers {
    private const val FILE = "companion"
    private const val KEY_LIST = "computers"
    private const val KEY_ACTIVE = "active_computer"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(context: Context): List<Computer> {
        migrate(context)
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val j = array.getJSONObject(i)
            Computer(j.getString("id"), j.getString("name"), j.getString("url"), j.optString("token"), j.optString("os"), j.optLong("lastUsedMs"))
        }
    }

    fun active(context: Context): Computer? {
        val list = all(context)
        val id = prefs(context).getString(KEY_ACTIVE, null)
        return list.firstOrNull { it.id == id } ?: list.maxByOrNull { it.lastUsedMs }
    }

    /** Ajoute ou remplace (même `id`) ; remplace aussi un ordinateur provisoire de même adresse. */
    fun save(context: Context, computer: Computer, makeActive: Boolean = false) {
        val list = all(context).filterNot { it.id == computer.id || (it.provisional && it.url == computer.url && !computer.provisional) }
        val saved = if (makeActive) computer.copy(lastUsedMs = System.currentTimeMillis()) else computer
        write(context, list + saved)
        if (makeActive) prefs(context).edit().putString(KEY_ACTIVE, saved.id).apply()
    }

    fun setActive(context: Context, id: String) {
        all(context).firstOrNull { it.id == id }?.let { save(context, it, makeActive = true) }
    }

    fun rename(context: Context, id: String, name: String) {
        all(context).firstOrNull { it.id == id }?.let { save(context, it.copy(name = name.trim().ifEmpty { it.name })) }
    }

    fun remove(context: Context, id: String) {
        write(context, all(context).filterNot { it.id == id })
        if (prefs(context).getString(KEY_ACTIVE, null) == id) prefs(context).edit().remove(KEY_ACTIVE).apply()
    }

    /** Un ordinateur provisoire a répondu avec son vrai identifiant : on l'adopte (l'adresse pourra changer). */
    fun adopt(context: Context, old: Computer, id: String, name: String, os: String): Computer {
        val wasActive = active(context)?.id == old.id
        remove(context, old.id)
        val known = all(context).firstOrNull { it.id == id }
        val adopted = (known ?: old).copy(id = id, name = if (known != null) known.name else name, os = os, url = old.url, token = old.token)
        save(context, adopted, makeActive = wasActive)
        return adopted
    }

    private fun write(context: Context, list: List<Computer>) {
        val array = JSONArray()
        list.sortedByDescending { it.lastUsedMs }.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("token", it.token)
                    .put("os", it.os).put("lastUsedMs", it.lastUsedMs),
            )
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    /** Ancienne version (une seule URL + jeton dans Réglages) : devient le premier ordinateur de la liste. */
    private fun migrate(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_LIST)) return
        val url = p.getString("backend_url", null)
        val list = JSONArray()
        if (url != null) {
            list.put(
                JSONObject().put("id", Computer.PROVISIONAL + url).put("name", "Mac").put("url", url)
                    .put("token", p.getString("token", null).orEmpty()).put("os", "mac").put("lastUsedMs", System.currentTimeMillis()),
            )
        }
        p.edit().putString(KEY_LIST, list.toString()).remove("backend_url").remove("token").apply()
    }

    /** "192.168.1.20", "192.168.1.20:3000" ou une URL complète → "http://192.168.1.20:3000". */
    fun normalizeUrl(input: String): String {
        var url = input.trim().trimEnd('/')
        if (!url.contains("://")) url = "http://$url"
        val host = url.substringAfter("://")
        // Pas de port : celui du backend par défaut (le crochet fermant d'une IPv6 n'en est pas un).
        if (!host.substringAfterLast(']').contains(':')) url += ":3000"
        return url
    }
}
