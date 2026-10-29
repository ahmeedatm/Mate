package com.unowhy.unomate.net

import org.json.JSONArray
import org.json.JSONObject

/** Touche posée par un persona sur l'ordinateur (`k-<emplacement>` ou `k<rang>-<emplacement>` pour une page nommée). */
data class DemoKey(val id: String, val label: String, val glyph: String?, val color: String?, val symbol: String? = null)

/** Ce que le compagnon a posé : les touches de Perso et celles de chaque page de touches nommée, par titre. */
class DemoSetup(val keys: List<DemoKey>, val pages: Map<String, List<DemoKey>>)

private fun parseKeys(list: JSONArray) = (0 until list.length()).map { i ->
    val k = list.getJSONObject(i)
    DemoKey(k.getString("id"), k.getString("label"), k.optStringOrNull("glyph"), k.optStringOrNull("color"), k.optStringOrNull("symbol"))
}

fun parseDemoSetup(json: String): DemoSetup {
    val root = JSONObject(json)
    val pages = root.optJSONArray("pages")
    return DemoSetup(
        parseKeys(root.getJSONArray("keys")),
        (0 until (pages?.length() ?: 0)).associate { i -> pages!!.getJSONObject(i).let { it.getString("title") to parseKeys(it.getJSONArray("keys")) } },
    )
}

/** Démo produit (src/demo.ts) : données fictives d'un persona sur l'ordinateur, rendues en quittant la démo. */
class DemoApi(private val http: Http) {

    /** Pose le persona sur l'ordinateur ; rend ses touches. */
    suspend fun setPersona(id: String): DemoSetup = http.io {
        parseDemoSetup(http.postJson("/demo/persona", JSONObject().put("persona", id)))
    }

    /** Rend les données d'avant la démo. Rend faux (sans erreur) s'il n'y en avait pas. */
    suspend fun quit() = http.io { http.delete("/demo"); Unit }
}
