package com.unowhy.companion.net

import org.json.JSONObject

data class AudioState(val output: Int?, val muted: Boolean, val input: Int, val micMuted: Boolean)

/**
 * Ce qui joue sur l'ordinateur (YouTube ou Twitch dans le navigateur, Spotify…). `app` : identifiant de l'appli
 * pour la piloter, `name` : son nom lisible ; `thumb` : clé de la vignette, change avec le morceau.
 */
data class MediaSession(
    val app: String, val name: String, val current: Boolean, val title: String, val artist: String, val playing: Boolean,
    val canPlayPause: Boolean, val canNext: Boolean, val canPrevious: Boolean, val canSeek: Boolean,
    val positionMs: Long?, val durationMs: Long?, val thumb: String?,
)

/** Son d'une appli de l'ordinateur (mélangeur de volume de Windows) ; `level` : niveau sonore instantané, 0-100. */
data class AppAudio(val running: Boolean, val volume: Int?, val muted: Boolean, val level: Int)

/**
 * Tranche du mélangeur (onglet Son du deck) : `id` "master" (sortie), "mic" ou une appli ("system" : sons de
 * l'ordinateur) ; `volume` null s'il ne se pilote pas ; `level` : niveau instantané 0-100, null si illisible (Mac).
 */
data class MixerChannel(val id: String, val name: String, val volume: Int?, val muted: Boolean, val level: Int?)

/** `apps` null : pas de volume par appli sur cet ordinateur (Mac). */
data class MixerState(val master: MixerChannel, val mic: MixerChannel, val apps: List<MixerChannel>?)

/** Scène du mélangeur : volumes de la sortie et des applis rappelés en un toucher. */
data class MixerScene(val id: String, val name: String)
/**
 * Carte graphique de l'ordinateur : `usage` 0-100, `dedicated` faux pour une puce intégrée. `tempC` et `fanRpm` sont
 * absents quand l'ordinateur ne sait pas les lire ; `fanRpm` à 0 veut dire ventilateur arrêté.
 */
data class GpuPerf(val name: String, val usage: Int, val dedicated: Boolean, val tempC: Int?, val fanRpm: Int?)

/** Performances de l'ordinateur ; `gpu` absent sur Mac ou sans carte lisible. */
data class PcPerf(val cpu: Int, val ramPercent: Int, val ramUsedGb: Double, val ramTotalGb: Double, val gpu: GpuPerf?)

fun parsePerf(json: String): PcPerf {
    val j = JSONObject(json)
    val ram = j.getJSONObject("ram")
    val gpu = j.optJSONObject("gpu")?.let { g ->
        GpuPerf(
            g.getString("name"), g.getInt("usage"), g.optBoolean("dedicated"),
            if (g.isNull("tempC")) null else g.getInt("tempC"),
            if (g.isNull("fanRpm")) null else g.getInt("fanRpm"),
        )
    }
    return PcPerf(j.getInt("cpu"), ram.getInt("percent"), ram.getDouble("used"), ram.getDouble("total"), gpu)
}

/**
 * Ce que contient le presse-papiers de l'ordinateur. `Text.truncated` : le compagnon a coupé un texte énorme.
 * `Protected` : contenu sensible (mot de passe), jamais lu ; `Other` : fichiers ou format inconnu.
 */
sealed class ClipContent {
    data class Text(val text: String, val truncated: Boolean) : ClipContent()
    data object Image : ClipContent()
    data object Empty : ClipContent()
    data object Protected : ClipContent()
    data object Other : ClipContent()
}

/** Réponse de `GET /system/clipboard` ; un `kind` inconnu (compagnon plus récent) est traité comme `Other`. */
fun parseClip(json: String): ClipContent {
    val j = JSONObject(json)
    return when (j.optString("kind")) {
        // optStringOrNull : sur Android, org.json rend un JSON null comme la chaîne "null".
        "text" -> ClipContent.Text(j.optStringOrNull("text").orEmpty(), j.optBoolean("truncated"))
        "image" -> ClipContent.Image
        "empty" -> ClipContent.Empty
        "protected" -> ClipContent.Protected
        else -> ClipContent.Other
    }
}

/** Texte de l'historique du presse-papiers ; `at` : heure de la copie (ms) ; `secret` : à masquer (code, mot de passe). */
data class ClipHistoryItem(val id: String, val text: String, val at: Long, val secret: Boolean)

/** Réponse de `GET /system/clipboard/history`, la copie la plus récente en premier. */
fun parseClipHistory(json: String): List<ClipHistoryItem> {
    val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { i ->
        val item = items.optJSONObject(i) ?: return@mapNotNull null
        val id = item.optStringOrNull("id") ?: return@mapNotNull null
        val text = item.optStringOrNull("text") ?: return@mapNotNull null
        ClipHistoryItem(id, text, item.optLong("at"), item.optBoolean("secret"))
    }
}

/** Système de l'ordinateur : son, lecture en cours, Ne pas déranger, appels, applis, saisie, réglages. */
class SystemApi(private val http: Http) {

    suspend fun audio(): AudioState = http.io { parseAudio(http.get("/system/audio")) }

    /** Processeur, mémoire et carte graphique (widget Performances) ; relevé instantané. */
    suspend fun perf(): PcPerf = http.io { parsePerf(http.get("/system/perf", timeoutMs = 5_000)) }

    suspend fun setAudio(output: Int? = null, outputDelta: Int? = null, muted: Boolean? = null, micMuted: Boolean? = null): AudioState = http.io {
        val body = JSONObject()
        output?.let { body.put("output", it) }
        outputDelta?.let { body.put("outputDelta", it) }
        muted?.let { body.put("muted", it) }
        micMuted?.let { body.put("micMuted", it) }
        parseAudio(http.postJson("/system/audio", body))
    }

    /** Son propre à une appli ; HTTP 501 si l'ordinateur ne sait pas le piloter (Mac). */
    suspend fun appAudio(name: String): AppAudio = http.io {
        parseAppAudio(http.get("/system/audio/app?name=" + http.encode(name), timeoutMs = 5_000))
    }

    suspend fun setAppAudio(name: String, volume: Int? = null, muted: Boolean? = null): AppAudio = http.io {
        val body = JSONObject().put("name", name)
        volume?.let { body.put("volume", it) }
        muted?.let { body.put("muted", it) }
        parseAppAudio(http.postJson("/system/audio/app", body))
    }

    // Mélangeur (onglet Son) ; HTTP 501 si l'ordinateur ne pilote pas le son.

    suspend fun mixer(): MixerState = http.io { parseMixer(JSONObject(http.get("/system/mixer", timeoutMs = 5_000))) }

    suspend fun setMixer(id: String, volume: Int? = null, muted: Boolean? = null): MixerState = http.io {
        val body = JSONObject().put("id", id)
        volume?.let { body.put("volume", it) }
        muted?.let { body.put("muted", it) }
        parseMixer(JSONObject(http.postJson("/system/mixer", body)))
    }

    suspend fun mixerScenes(): List<MixerScene> = http.io {
        val scenes = JSONObject(http.get("/system/mixer/scenes")).getJSONArray("scenes")
        (0 until scenes.length()).map { i -> scenes.getJSONObject(i).let { MixerScene(it.getString("id"), it.getString("name")) } }
    }

    /** Enregistre les volumes actuels (une scène du même nom est remplacée). */
    suspend fun saveMixerScene(name: String): MixerScene = http.io {
        JSONObject(http.postJson("/system/mixer/scenes", JSONObject().put("name", name))).let { MixerScene(it.getString("id"), it.getString("name")) }
    }

    suspend fun deleteMixerScene(id: String) = http.io { http.delete("/system/mixer/scenes/" + http.encode(id)) }

    /** Rappelle une scène ; `second` : applis de la scène sans son en ce moment (laissées telles quelles). */
    suspend fun applyMixerScene(id: String): Pair<MixerState, List<String>> = http.io {
        val j = JSONObject(http.postJson("/system/mixer/scenes/" + http.encode(id) + "/apply", JSONObject()))
        val missing = j.getJSONArray("missing")
        parseMixer(j.getJSONObject("mixer")) to (0 until missing.length()).map { missing.getString(it) }
    }

    // Lecture en cours (Windows ; HTTP 501 sur Mac).

    suspend fun media(): List<MediaSession> = http.io {
        val sessions = JSONObject(http.get("/system/media", timeoutMs = 10_000)).getJSONArray("sessions")
        (0 until sessions.length()).map { i ->
            val s = sessions.getJSONObject(i)
            MediaSession(
                s.getString("app"), s.getString("name"), s.optBoolean("current"), s.optString("title"), s.optString("artist"),
                s.optString("status") == "playing", s.optBoolean("canPlayPause"), s.optBoolean("canNext"),
                s.optBoolean("canPrevious"), s.optBoolean("canSeek"),
                if (s.isNull("positionMs")) null else s.getLong("positionMs"),
                if (s.isNull("durationMs")) null else s.getLong("durationMs"),
                s.optStringOrNull("thumb"),
            )
        }
    }

    /** `action` : play-pause, next, previous ou seek (avec `positionMs`). */
    suspend fun mediaControl(action: String, app: String?, positionMs: Long? = null) = http.io {
        val body = JSONObject().put("action", action)
        app?.let { body.put("app", it) }
        positionMs?.let { body.put("positionMs", it) }
        http.postJson("/system/media", body)
        Unit
    }

    suspend fun mediaThumb(app: String, key: String): ByteArray? = http.io {
        http.bytesOrNull("/system/media/thumb?app=${http.encode(app)}&key=${http.encode(key)}", timeoutMs = 10_000)
    }

    /** Lance une appli installée sur l'ordinateur (nom tel que dans `DeckApi.catalog().apps`). */
    suspend fun launchApp(name: String) = http.io { http.postJson("/apps/launch", JSONObject().put("name", name)); Unit }

    /** Ne pas déranger de l'ordinateur pendant le Focus (src/dnd.ts) ; `minutes` : temps restant, pour le minuteur de secours. */
    suspend fun focusDnd(on: Boolean, minutes: Int) = http.io {
        http.postJson("/system/dnd", JSONObject().put("on", on).put("minutes", minutes.coerceIn(1, 240))); Unit
    }

    /** Ne pas déranger de l'ordinateur : null si illisible (pastille de Ma journée). */
    suspend fun dnd(): Boolean? = http.io { JSONObject(http.get("/system/dnd")).opt("on") as? Boolean }

    /** Posé à la main (pastille de Ma journée) : l'utilisateur en prend la main, le Focus ne l'éteindra plus. */
    suspend fun setDnd(on: Boolean) = http.io { http.putJson("/system/dnd", JSONObject().put("on", on)); Unit }

    /** Appel entrant (src/calls.ts) : appuie sur Répondre (ou Vidéo) / Ignorer dans l'appli. */
    suspend fun answerCall(app: String, video: Boolean) = http.io { http.postJson("/calls/answer", JSONObject().put("app", app).put("video", video)); Unit }

    suspend fun declineCall(app: String) = http.io { http.postJson("/calls/decline", JSONObject().put("app", app)); Unit }

    /** Raccroche (`app`, sinon la première appli en appel) ; renvoie le nom de l'appli. */
    suspend fun hangUp(app: String? = null): String = http.io {
        JSONObject(http.postJson("/calls/hangup", JSONObject().apply { app?.let { put("app", it) } })).getString("app")
    }

    /** Dictée : texte collé dans l'appli au premier plan de l'ordinateur. */
    suspend fun typeOnComputer(text: String) = http.io { http.postJson("/system/type", JSONObject().put("text", text)); Unit }

    /** Presse-papiers de l'ordinateur, lu une fois ; HTTP 503 s'il n'y a pas de session de bureau. */
    suspend fun clipboard(): ClipContent = http.io { parseClip(http.get("/system/clipboard", timeoutMs = 10_000)) }

    /** Met ce texte dans le presse-papiers de l'ordinateur (1 à 100 000 caractères). */
    suspend fun copyToComputer(text: String) = http.io { http.postJson("/system/clipboard", JSONObject().put("text", text)); Unit }

    /** Textes copiés sur l'ordinateur depuis une heure (20 au plus), gardés en mémoire par le compagnon. */
    suspend fun clipboardHistory(): List<ClipHistoryItem> = http.io { parseClipHistory(http.get("/system/clipboard/history", timeoutMs = 10_000)) }

    /** Remet ce texte de l'historique dans le presse-papiers de l'ordinateur ; HTTP 404 s'il a disparu. */
    suspend fun recopyFromHistory(id: String) = http.io { http.postJson("/system/clipboard/history/" + http.encode(id) + "/copy", JSONObject()); Unit }

    suspend fun removeFromHistory(id: String) = http.io { http.delete("/system/clipboard/history/" + http.encode(id)); Unit }

    suspend fun clearHistory() = http.io { http.delete("/system/clipboard/history"); Unit }

    /** Met cette image (`path` : relatif au dossier partagé, .jpg/.jpeg/.png) dans le presse-papiers de l'ordinateur. */
    suspend fun copyImageToComputer(path: String) = http.io { http.postJson("/system/clipboard/image", JSONObject().put("path", path)); Unit }

    /** Mac : ouvre le réglage Accessibilité, le compagnon déjà dans la liste. */
    suspend fun openAccessibility() = http.io { http.postJson("/system/accessibility", JSONObject()); Unit }

    /** Ouvre la page du compagnon sur l'ordinateur ; `section` : "agenda" pour y relier un agenda, "tablette" pour la carte Réglages de la tablette. */
    suspend fun openLocalPage(section: String? = null) = http.io {
        http.postJson("/local/open", JSONObject().apply { if (section != null) put("section", section) }); Unit
    }

    private fun parseChannel(j: JSONObject) = MixerChannel(
        j.getString("id"), j.getString("name"),
        if (j.isNull("volume")) null else j.getInt("volume"), j.getBoolean("muted"),
        if (j.isNull("level")) null else j.getInt("level"),
    )

    private fun parseMixer(j: JSONObject): MixerState {
        val apps = j.optJSONArray("apps")
        return MixerState(
            parseChannel(j.getJSONObject("master")), parseChannel(j.getJSONObject("mic")),
            apps?.let { a -> (0 until a.length()).map { parseChannel(a.getJSONObject(it)) } },
        )
    }

    private fun parseAudio(raw: String): AudioState {
        val j = JSONObject(raw)
        return AudioState(if (j.isNull("output")) null else j.getInt("output"), j.getBoolean("muted"), j.getInt("input"), j.getBoolean("micMuted"))
    }

    private fun parseAppAudio(raw: String): AppAudio {
        val j = JSONObject(raw)
        return AppAudio(j.getBoolean("running"), if (j.isNull("volume")) null else j.getInt("volume"), j.getBoolean("muted"), j.getInt("level"))
    }
}
