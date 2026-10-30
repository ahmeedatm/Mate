package com.unowhy.unomate.net

import com.unowhy.unomate.frame.FramePhoto
import org.json.JSONObject
import java.io.File

data class DocEntry(val name: String, val path: String, val kind: String, val size: Long, val mtimeMs: Long)
data class DocListing(val dir: String, val entries: List<DocEntry>)

/** Page ouverte dans le navigateur de l'ordinateur (le dernier utilisé). */
data class WebPage(val url: String, val title: String)

/** Onglet d'un navigateur de l'ordinateur ; `active` : affiché dans sa fenêtre. */
data class BrowserTab(val id: String, val title: String, val url: String, val browser: String, val active: Boolean)
data class BrowserTabs(val extension: Boolean, val tabs: List<BrowserTab>)

/** Depuis l'ordi : fichiers du dossier partagé, photos envoyées, page et onglets du navigateur. */
class DocsApi(private val http: Http) {

    suspend fun list(dir: String): DocListing = http.io {
        val json = JSONObject(http.get("/docs?dir=" + http.encode(dir)))
        val entries = json.getJSONArray("entries")
        DocListing(json.getString("dir"), (0 until entries.length()).map { i ->
            val e = entries.getJSONObject(i)
            DocEntry(e.getString("name"), e.getString("path"), e.getString("kind"), e.getLong("size"), e.getLong("mtimeMs"))
        })
    }

    suspend fun downloadDoc(path: String, dest: File) = http.io {
        val conn = http.request("GET", "/docs/file?path=" + http.encode(path))
        try { conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } } } finally { conn.disconnect() }
    }

    /** Photos du dossier « Cadre photo » (créé par l'ordinateur s'il manque) ; chacune se télécharge par [downloadDoc]. */
    suspend fun framePhotos(): List<FramePhoto> = http.io {
        val photos = JSONObject(http.get("/frame")).getJSONArray("photos")
        (0 until photos.length()).map { i ->
            photos.getJSONObject(i).let { FramePhoto(it.getString("path"), it.getLong("size"), it.getLong("mtimeMs")) }
        }
    }

    suspend fun openOnComputer(path: String) = http.io { http.postJson("/docs/open", JSONObject().put("path", path)); Unit }

    /** Envoie une photo dans le dossier partagé du Mac ; renvoie son chemin relatif. */
    suspend fun uploadPhoto(photo: File): String = http.io {
        JSONObject(http.postFile("/docs/upload", "photo", photo, "image/jpeg")).getString("path")
    }

    /** Page ouverte dans le navigateur de l'ordinateur ; erreur 404 (message lisible) s'il n'y en a pas. */
    suspend fun browserPage(): WebPage = http.io {
        JSONObject(http.get("/browser/page")).let { WebPage(it.getString("url"), it.getString("title")) }
    }

    /** `extension` false : ordinateur Windows sans l'extension du navigateur, la liste reste vide. */
    suspend fun browserTabs(): BrowserTabs = http.io {
        val json = JSONObject(http.get("/browser/tabs", timeoutMs = 10_000))
        val a = json.getJSONArray("tabs")
        BrowserTabs(json.optBoolean("extension", true), (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                BrowserTab(it.getString("id"), it.optString("title"), it.getString("url"), it.optString("browser"), it.optBoolean("active"))
            }
        })
    }

    suspend fun openUrlOnComputer(url: String) = http.io { http.postJson("/browser/open", JSONObject().put("url", url)); Unit }
}
