package com.unowhy.companion.net

import org.json.JSONObject
import java.io.File

/** Mise à jour proposée par le « serveur Unowhy » (simulé par l'ordinateur) : `sha256` vérifiée après téléchargement. */
data class AppUpdate(
    val pkg: String, val label: String, val versionCode: Long, val versionName: String,
    val file: String, val size: Long, val sha256: String,
)

/** Mises à jour de la tablette (src/updates.ts). */
class UpdatesApi(private val http: Http) {

    suspend fun list(): List<AppUpdate> = http.io {
        val a = JSONObject(http.get("/updates")).getJSONArray("updates")
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                AppUpdate(it.getString("package"), it.getString("label"), it.getLong("versionCode"), it.getString("versionName"),
                    it.getString("file"), it.getLong("size"), it.getString("sha256"))
            }
        }
    }

    /** Télécharge une mise à jour dans `dest` ; `progress` (0 à 100, appelé hors du fil principal). Renvoie l'empreinte SHA-256 reçue. */
    suspend fun download(update: AppUpdate, dest: File, progress: (Int) -> Unit): String = http.io {
        val conn = http.request("GET", "/updates/file/" + http.encode(update.file), timeoutMs = 120_000)
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
}
