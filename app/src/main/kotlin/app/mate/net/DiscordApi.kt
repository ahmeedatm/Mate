package app.mate.net

import org.json.JSONObject

/**
 * Photos des participants d'un appel Discord et qui parle, lus par l'ordinateur dans le RPC de son appli Discord
 * (backend/src/discord-rpc.ts). Le jeton Discord reste sur l'ordinateur : la tablette ne voit que des identifiants.
 */
class DiscordApi(private val http: Http) {

    /** Ouvre « Autoriser » dans Discord sur l'ordinateur et attend le clic (60 s au plus côté ordinateur). */
    suspend fun authorize(): Unit = http.io { http.postJson("/discord/authorize", JSONObject(), timeoutMs = AUTHORIZE_TIMEOUT_MS); Unit }

    /** Identifiants des participants qui parlent en ce moment ; relu toutes les 500 ms pendant un appel. */
    suspend fun speaking(): Set<String> = http.io {
        val a = JSONObject(http.get("/discord/speaking", timeoutMs = 3_000)).optJSONArray("speaking")
        (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()
    }

    /** Photo de 64 px d'un participant ; null s'il n'en a pas. */
    suspend fun avatar(id: String): ByteArray? = http.io { http.bytesOrNull("/discord/avatar?id=" + http.encode(id), timeoutMs = 15_000) }

    private companion object {
        const val AUTHORIZE_TIMEOUT_MS = 75_000
    }
}
