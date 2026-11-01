package app.mate.net

import org.json.JSONArray
import org.json.JSONObject

data class ShoppingItem(val id: String, val label: String, val done: Boolean)

/** Liste de courses, gardée sur l'ordinateur (src/shopping.ts). */
class ShoppingApi(private val http: Http) {

    suspend fun list(): List<ShoppingItem> = http.io { parse(JSONObject(http.get("/shopping")).getJSONArray("items")) }

    /** Ajoute des articles ; un article déjà à acheter n'est pas doublé, un article coché repasse à acheter. */
    suspend fun add(labels: List<String>) = http.io { http.postJson("/shopping", JSONObject().put("labels", JSONArray(labels))); Unit }

    suspend fun setDone(id: String, done: Boolean) = http.io { http.postJson("/shopping/${http.encode(id)}/done", JSONObject().put("done", done)); Unit }

    suspend fun delete(id: String) = http.io { http.delete("/shopping/" + http.encode(id)); Unit }

    /** Retire les articles cochés. */
    suspend fun clearDone() = http.io { http.postJson("/shopping/clear-done", JSONObject()); Unit }

    private fun parse(items: JSONArray) = (0 until items.length()).map { i ->
        val item = items.getJSONObject(i)
        ShoppingItem(item.getString("id"), item.getString("label"), item.getBoolean("done"))
    }
}
