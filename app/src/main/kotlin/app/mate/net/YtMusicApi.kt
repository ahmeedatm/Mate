package app.mate.net

import org.json.JSONArray
import org.json.JSONObject

/** Titre joué par YouTube Music sur l'ordinateur ; positions en secondes (0 si le lecteur ne les donne pas encore). */
data class YtTrack(
    val title: String,
    val artist: String,
    val album: String,
    val cover: String?,
    val position: Double,
    val duration: Double,
    val like: String,
)

/** Titre de la file d'attente ; `current` : celui qui joue. */
data class YtQueueItem(val title: String, val subtitle: String, val cover: String?, val current: Boolean)

/** Où en est la connexion à l'appli de bureau YTMDesktop (`off`, `connecting` + `code`, `connected`, `error`). */
data class YtDesktop(val status: String, val code: String?, val error: String?)

/**
 * Ce que joue YouTube Music sur l'ordinateur (backend/src/ytmusic.ts) : `source` = `desktop` (YTMDesktop) ou
 * `browser` (onglet du navigateur) ; `open` faux quand ni l'un ni l'autre n'est ouvert.
 */
data class YtMusicState(
    val open: Boolean,
    val source: String?,
    val playing: Boolean,
    val track: YtTrack?,
    val repeat: String?,
    val queue: List<YtQueueItem>,
    val desktop: YtDesktop,
)

/** Réponse de `GET /ytmusic`. */
fun parseYtMusic(json: String): YtMusicState {
    val j = JSONObject(json)
    val track = j.optJSONObject("track")?.let { t ->
        YtTrack(
            title = t.optStringOrNull("title").orEmpty(),
            artist = t.optStringOrNull("artist").orEmpty(),
            album = t.optStringOrNull("album").orEmpty(),
            cover = t.optStringOrNull("cover"),
            position = t.optDouble("position", 0.0),
            duration = t.optDouble("duration", 0.0),
            like = t.optStringOrNull("like") ?: "none",
        )
    }?.takeIf { it.title.isNotEmpty() }
    val queue = j.optJSONArray("queue") ?: JSONArray()
    val desktop = j.optJSONObject("desktop")
    return YtMusicState(
        open = j.optBoolean("open"),
        source = j.optStringOrNull("source"),
        playing = j.optBoolean("playing"),
        track = track,
        repeat = j.optStringOrNull("repeat"),
        queue = (0 until queue.length()).mapNotNull { i ->
            queue.optJSONObject(i)?.let { q ->
                YtQueueItem(q.optStringOrNull("title").orEmpty(), q.optStringOrNull("subtitle").orEmpty(), q.optStringOrNull("cover"), q.optBoolean("current"))
            }
        },
        desktop = YtDesktop(desktop?.optStringOrNull("status") ?: "off", desktop?.optStringOrNull("code"), desktop?.optStringOrNull("error")),
    )
}

/**
 * YouTube Music de l'ordinateur piloté depuis la tablette : l'appli tourne et joue sur l'ordinateur, la tablette n'envoie
 * qu'une action de la liste fermée du compagnon (`op` : play-pause, next, previous, seek, like, dislike, shuffle,
 * repeat, play-queue…). 404 sur `state` : compagnon plus ancien, sans YouTube Music.
 */
class YtMusicApi(private val http: Http) {

    suspend fun state(): YtMusicState = http.io { parseYtMusic(http.get("/ytmusic", timeoutMs = 8_000)) }

    suspend fun action(op: String, index: Int? = null, seconds: Double? = null) = http.io {
        val body = JSONObject().put("op", op)
        index?.let { body.put("index", it) }
        seconds?.let { body.put("seconds", it) }
        http.postJson("/ytmusic/action", body)
        Unit
    }

    /** Demande l'accès à YTMDesktop ; l'accord se donne dans YTMDesktop, `state()` dit la suite. */
    suspend fun connectDesktop(): YtDesktop = http.io {
        val d = JSONObject(http.postJson("/ytmusic/desktop/connect", JSONObject())).optJSONObject("desktop")
        YtDesktop(d?.optStringOrNull("status") ?: "error", d?.optStringOrNull("code"), d?.optStringOrNull("error"))
    }

    /** Pochette relayée par l'ordinateur (seulement une adresse de l'état en cours) ; null si elle ne vient pas. */
    suspend fun cover(url: String): ByteArray? = http.io {
        runCatching { http.bytesOrNull("/ytmusic/cover?url=${http.encode(url)}", timeoutMs = 10_000) }.getOrNull()
    }
}
