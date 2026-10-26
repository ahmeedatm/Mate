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

/** Système de l'ordinateur : son, lecture en cours, Ne pas déranger, appels, applis, saisie, réglages. */
class SystemApi(private val http: Http) {

    suspend fun audio(): AudioState = http.io { parseAudio(http.get("/system/audio")) }

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

    /** Appel entrant (src/calls.ts) : appuie sur Répondre (ou Vidéo) / Ignorer dans l'appli. */
    suspend fun answerCall(app: String, video: Boolean) = http.io { http.postJson("/calls/answer", JSONObject().put("app", app).put("video", video)); Unit }

    suspend fun declineCall(app: String) = http.io { http.postJson("/calls/decline", JSONObject().put("app", app)); Unit }

    /** Raccroche (`app`, sinon la première appli en appel) ; renvoie le nom de l'appli. */
    suspend fun hangUp(app: String? = null): String = http.io {
        JSONObject(http.postJson("/calls/hangup", JSONObject().apply { app?.let { put("app", it) } })).getString("app")
    }

    /** Dictée : texte collé dans l'appli au premier plan de l'ordinateur. */
    suspend fun typeOnComputer(text: String) = http.io { http.postJson("/system/type", JSONObject().put("text", text)); Unit }

    /** Mac : ouvre le réglage Accessibilité, le compagnon déjà dans la liste. */
    suspend fun openAccessibility() = http.io { http.postJson("/system/accessibility", JSONObject()); Unit }

    /** Ouvre la page du compagnon sur l'ordinateur ; `section` : "agenda" pour y relier un agenda. */
    suspend fun openLocalPage(section: String? = null) = http.io {
        http.postJson("/local/open", JSONObject().apply { if (section != null) put("section", section) }); Unit
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
