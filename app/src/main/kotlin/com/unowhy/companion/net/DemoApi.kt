package com.unowhy.companion.net

import org.json.JSONObject

/** Touche posée par un persona sur l'ordinateur (`k-<emplacement>`). */
data class DemoKey(val id: String, val label: String, val glyph: String?, val color: String?)

fun parseDemoKeys(json: String): List<DemoKey> {
    val list = JSONObject(json).getJSONArray("keys")
    return (0 until list.length()).map { i ->
        val k = list.getJSONObject(i)
        DemoKey(k.getString("id"), k.getString("label"), k.optStringOrNull("glyph"), k.optStringOrNull("color"))
    }
}

/** Démo produit (src/demo.ts) : données fictives d'un persona sur l'ordinateur, rendues en quittant la démo. */
class DemoApi(private val http: Http) {

    /** Pose le persona sur l'ordinateur ; rend ses touches. */
    suspend fun setPersona(id: String): List<DemoKey> = http.io {
        parseDemoKeys(http.postJson("/demo/persona", JSONObject().put("persona", id)))
    }

    /** Rend les données d'avant la démo. */
    suspend fun quit() = http.io { http.delete("/demo"); Unit }
}
