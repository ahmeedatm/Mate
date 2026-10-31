package app.mate.net

import org.json.JSONObject

data class RemoteCommand(val id: String, val command: JSONObject)

/** Canal ordinateur → tablette (voir remote/RemoteChannel.kt). */
class TabletApi(private val http: Http) {

    /** Attend les ordres de l'ordinateur (le backend garde la requête ouverte jusqu'à 25 s). */
    suspend fun poll(state: JSONObject): List<RemoteCommand> = http.io {
        val commands = JSONObject(http.postJson("/tablet/poll", JSONObject().put("state", state))).getJSONArray("commands")
        (0 until commands.length()).map { i ->
            val c = commands.getJSONObject(i)
            RemoteCommand(c.getString("id"), c.getJSONObject("command"))
        }
    }

    suspend fun sendResult(id: String, result: JSONObject, state: JSONObject) = http.io {
        http.postJson("/tablet/result", result.put("id", id).put("state", state)); Unit
    }
}
