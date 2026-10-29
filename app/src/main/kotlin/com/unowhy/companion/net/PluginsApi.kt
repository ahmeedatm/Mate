package com.unowhy.companion.net

import org.json.JSONObject

/**
 * Plugin proposé par l'ordinateur : `app` = lié à une appli trouvée sur l'ordinateur (Discord…), sinon intégré
 * (onglets du Deck). `fresh` : jamais proposé sur cet ordinateur, la tablette ouvre l'écran des plugins.
 */
data class Plugin(
    val id: String,
    val name: String,
    val description: String,
    val app: String?,
    val native: Boolean,
    val on: Boolean,
    val fresh: Boolean,
)

fun parsePlugins(json: String): List<Plugin> {
    val list = JSONObject(json).optJSONArray("plugins") ?: return emptyList()
    return (0 until list.length()).map { i ->
        val p = list.getJSONObject(i)
        Plugin(
            id = p.getString("id"),
            name = p.optString("name", p.getString("id")),
            description = p.optString("description"),
            app = p.optStringOrNull("app"),
            native = p.optString("kind") == "native",
            on = p.optBoolean("on", true),
            fresh = p.optBoolean("fresh"),
        )
    }
}

/** Plugins de cet ordinateur (`/plugins`), choisis à la première utilisation puis depuis les Réglages. */
class PluginsApi(private val http: Http) {

    /** Liste vide : ordinateur pas encore à jour (pas de `/plugins`). */
    suspend fun list(): List<Plugin> = http.io {
        try {
            parsePlugins(http.get("/plugins", timeoutMs = 15_000))
        } catch (e: HttpException) {
            if (e.code == 404) emptyList() else throw e
        }
    }

    suspend fun save(choices: Map<String, Boolean>): List<Plugin> = http.io {
        parsePlugins(http.postJson("/plugins", JSONObject().put("choices", JSONObject(choices))))
    }
}
