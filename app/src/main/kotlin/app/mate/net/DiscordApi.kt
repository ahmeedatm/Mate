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
    suspend fun speaking(): Set<String> = http.io { parseSpeaking(http.getKeepAlive("/discord/speaking", timeoutMs = 3_000)) }

    /** Sorties où Discord peut jouer les voix (casque, enceinte…) ; pas celles de tout l'ordinateur. */
    suspend fun outputs(): List<AudioOutput> = http.io { parseAudioOutputs(http.get("/discord/outputs", timeoutMs = 5_000)) }

    /** Fait jouer les voix de Discord sur la sortie `id` ; rend la liste à jour. */
    suspend fun setOutput(id: String): List<AudioOutput> = http.io { parseAudioOutputs(http.postJson("/discord/output", JSONObject().put("id", id))) }

    /** Photo de 64 px d'un participant ; null s'il n'en a pas. */
    suspend fun avatar(id: String): ByteArray? = http.io { http.bytesOrNull("/discord/avatar?id=" + http.encode(id), timeoutMs = 15_000) }

    private companion object {
        const val AUTHORIZE_TIMEOUT_MS = 75_000
    }
}

/** Réponse de /discord/speaking : identifiants des participants qui parlent. */
internal fun parseSpeaking(json: String): Set<String> =
    JSONObject(json).optJSONArray("speaking")?.let { a -> (0 until a.length()).map(a::getString).toSet() }.orEmpty()
