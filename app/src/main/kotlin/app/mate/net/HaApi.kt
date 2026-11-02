package app.mate.net

import org.json.JSONObject

/**
 * Entité Home Assistant de la liste blanche de l'ordinateur. `kind` « run » : scène ou script, qui se lance sans état ;
 * `available` faux : Home Assistant ne la joint pas.
 */
data class HaEntity(val id: String, val name: String, val isScene: Boolean, val on: Boolean, val available: Boolean)

fun parseHaEntities(json: String): List<HaEntity> {
    val list = JSONObject(json).optJSONArray("entities") ?: return emptyList()
    return (0 until list.length()).map {
        val e = list.getJSONObject(it)
        val id = e.getString("id")
        HaEntity(id, e.optString("name", id), e.optString("kind") == "run", e.optBoolean("on"), e.optBoolean("available", true))
    }
}

/** Home Assistant de la maison, par l'ordinateur (backend/src/ha.ts) : le jeton n'est jamais sur la tablette. */
class HaApi(private val http: Http) {

    suspend fun entities(): List<HaEntity> = http.io { parseHaEntities(http.get("/ha/entities", timeoutMs = 15_000)) }

    /** Bascule une lumière ou une prise, lance une scène. */
    suspend fun toggle(id: String) = http.io {
        http.postJson("/ha/entities/${http.encode(id)}/toggle", JSONObject())
        Unit
    }
}
