package com.unowhy.companion.net

import com.unowhy.companion.BuildConfig
import org.json.JSONObject
import java.io.File

/** D'où vient une mise à jour : l'ordinateur relié (« serveur Unowhy » simulé) ou le serveur central (GitLab Pages). */
enum class UpdateSource { COMPUTER, SERVER }

/** Mise à jour annoncée par une source : `sha256` vérifiée après téléchargement. */
data class AppUpdate(
    val pkg: String, val label: String, val versionCode: Long, val versionName: String,
    val file: String, val size: Long, val sha256: String,
    val source: UpdateSource = UpdateSource.COMPUTER,
)

/**
 * Mises à jour de la tablette. Même format des deux côtés : ordinateur (`GET /updates`, src/updates.ts, avec le jeton)
 * et serveur central (`GET <base>/manifest.json`, APK à côté, sans jeton : la tablette ne garde aucun secret).
 */
class UpdatesApi(private val http: Http, private val source: UpdateSource = UpdateSource.COMPUTER) {

    suspend fun list(): List<AppUpdate> = http.io {
        parseUpdates(http.get(if (source == UpdateSource.SERVER) "/manifest.json" else "/updates"), source)
    }

    /** Télécharge une mise à jour dans `dest` ; `progress` (0 à 100, appelé hors du fil principal). Renvoie l'empreinte SHA-256 reçue. */
    suspend fun download(update: AppUpdate, dest: File, progress: (Int) -> Unit): String = http.io {
        val path = (if (source == UpdateSource.SERVER) "/" else "/updates/file/") + http.encode(update.file)
        val conn = http.request("GET", path, timeoutMs = 120_000)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        try {
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (update.size > 0) (done * 100 / update.size).toInt() else 0
                        if (percent != shown) { shown = percent; progress(percent) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Serveur central, null s'il est coupé au build (`-PupdateUrl=`). En-tête facultatif `-PupdateHeader="Nom: valeur"`. */
        fun server(baseUrl: String = BuildConfig.UPDATE_URL, header: String = BuildConfig.UPDATE_HEADER): UpdatesApi? =
            baseUrl.trimEnd('/').ifEmpty { null }?.let { UpdatesApi(Http(it, "", 10_000, parseHeader(header)), UpdateSource.SERVER) }

        /** « Nom: valeur » → une entrée ; vide ou sans « : » → aucun en-tête. */
        internal fun parseHeader(header: String): Map<String, String> {
            val i = header.indexOf(':')
            return if (i <= 0) emptyMap() else mapOf(header.substring(0, i).trim() to header.substring(i + 1).trim())
        }
    }
}

/** `{"updates":[{package, label, versionCode, versionName, file, size, sha256}]}`. */
internal fun parseUpdates(json: String, source: UpdateSource): List<AppUpdate> {
    val a = JSONObject(json).getJSONArray("updates")
    return (0 until a.length()).map { i ->
        a.getJSONObject(i).let {
            AppUpdate(it.getString("package"), it.getString("label"), it.getLong("versionCode"), it.getString("versionName"),
                it.getString("file"), it.getLong("size"), it.getString("sha256"), source)
        }
    }
}
