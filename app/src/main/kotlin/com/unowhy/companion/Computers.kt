package com.unowhy.companion

import android.content.Context
import com.unowhy.companion.net.isLocalUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un ordinateur appairé. `id` : identifiant donné par son backend (il reste le même si son adresse IP change) ;
 * `os` : "mac" ou "windows" (raccourcis proposés dans l'éditeur de touches) ; `pin` : empreinte de son certificat,
 * retenue à l'appairage (HTTPS en WiFi, voir net/Pinning.kt).
 */
data class Computer(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
    val os: String,
    val lastUsedMs: Long = 0,
    val pin: String? = null,
) {
    /** Enregistré avant l'appairage par code (adresse saisie, ancienne version) : `id` à remplacer par le vrai. */
    val provisional: Boolean get() = id.startsWith(PROVISIONAL)

    /** Adresse lisible : "192.168.1.20:3001". */
    val address: String get() = url.removePrefix("http://").removePrefix("https://")

    /** Joint en HTTP en clair sur le WiFi (appairé avant le HTTPS) : à réappairer, le jeton n'est plus envoyé. */
    val needsRepair: Boolean get() = url.startsWith("http://") && !isLocalUrl(url)

    /** Adresse où réappairer : en WiFi, HTTPS sur le port suivant ; le port HTTP n'écoute plus que la tablette en USB. */
    val pairUrl: String get() {
        if (!needsRepair) return url
        val host = url.removePrefix("http://").substringBeforeLast(':')
        val port = url.substringAfterLast(':').toIntOrNull() ?: 3000
        return "https://$host:${port + 1}"
    }

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
        return parse(JSONArray(raw))
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
        val adopted = (known ?: old).copy(id = id, name = if (known != null) known.name else name, os = os, url = old.url, token = old.token, pin = old.pin)
        save(context, adopted, makeActive = wasActive)
        return adopted
    }

    /**
     * Liste et actif, pour l'appli d'essai installée à côté (trial/ComputersProvider). Contient les jetons : ne sort
     * de l'appli que vers une appli signée par la même clé.
     */
    fun export(context: Context): String =
        JSONObject().put("computers", toJson(all(context))).put("active", active(context)?.id).toString()

    /**
     * Appli d'essai : reprend les ordinateurs de la stable ([export]) tant qu'elle n'en a aucun, sans réappairer.
     * Ensuite, chacune garde sa liste. @return true si des ordinateurs ont été repris.
     */
    fun importIfEmpty(context: Context, raw: String): Boolean {
        if (all(context).isNotEmpty()) return false
        val json = JSONObject(raw)
        val list = parse(json.getJSONArray("computers"))
        if (list.isEmpty()) return false
        write(context, list)
        json.optString("active").takeIf { id -> list.any { it.id == id } }?.let { prefs(context).edit().putString(KEY_ACTIVE, it).apply() }
        return true
    }

    private fun parse(array: JSONArray): List<Computer> = (0 until array.length()).map { i ->
        val j = array.getJSONObject(i)
        Computer(
            j.getString("id"), j.getString("name"), j.getString("url"), j.optString("token"), j.optString("os"), j.optLong("lastUsedMs"),
            j.optString("pin").ifEmpty { null },
        )
    }

    private fun toJson(list: List<Computer>): JSONArray {
        val array = JSONArray()
        list.sortedByDescending { it.lastUsedMs }.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("token", it.token)
                    .put("os", it.os).put("lastUsedMs", it.lastUsedMs).put("pin", it.pin),
            )
        }
        return array
    }

    private fun write(context: Context, list: List<Computer>) {
        prefs(context).edit().putString(KEY_LIST, toJson(list).toString()).apply()
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

    /**
     * Adresse saisie → URL : "192.168.1.20" ou "192.168.1.20:3001" → "https://192.168.1.20:3001" (le WiFi passe en HTTPS) ;
     * la tablette elle-même (USB, adb reverse) → "http://127.0.0.1:3000" ; une URL complète reste telle quelle.
     */
    fun normalizeUrl(input: String): String {
        var url = input.trim().trimEnd('/')
        if (!url.contains("://")) url = "https://$url"
        if (isLocalUrl(url.replaceFirst("https://", "http://"))) url = url.replaceFirst("https://", "http://")
        val host = url.substringAfter("://")
        // Pas de port : celui du backend par défaut (le crochet fermant d'une IPv6 n'en est pas un).
        if (!host.substringAfterLast(']').contains(':')) url += if (url.startsWith("https://")) ":3001" else ":3000"
        return url
    }
}
