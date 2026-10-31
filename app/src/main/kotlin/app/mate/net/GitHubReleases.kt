package app.mate.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Source de mises à jour dans une release GitHub : `manifest.json`, APK, installeurs et archives du backend sont les
 * assets du tag. Dépôt privé : l'API GitHub seule les sert (`GET /releases/assets/<id>`), avec le jeton de lecture
 * injecté au build. Le jeton ne part qu'à api.github.com : GitHub répond 302 vers un stockage signé, suivi à la main
 * sans lui (HttpURLConnection le garderait selon la version d'Android).
 */
internal class GitHubStore(private val repo: String, private val tag: String, private val token: String) : UpdateStore {

    private var assets: Map<String, Long>? = null
    private var releaseId = 0L

    override fun manifest(): String {
        load()
        return open(MANIFEST, 60_000).let { conn -> try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() } }
    }

    override fun open(file: String, timeoutMs: Int): HttpURLConnection {
        if (assets == null) load()
        val id = assets!![file] ?: assetIdInPages(file) ?: throw HttpException(404, "")
        return get(assetUrl(repo, id), "application/octet-stream", timeoutMs)
    }

    /** Release du tag : ses assets par nom. Relue à chaque manifest (un asset remplacé change d'id). */
    private fun load() {
        val release = parseRelease(read(get(releaseUrl(repo, tag), "application/vnd.github+json", 60_000)))
        releaseId = release.id
        assets = release.assets
    }

    /** `assets` d'une release est tronqué à 30 : au-delà, la liste paginée (essais nombreux). */
    private fun assetIdInPages(file: String): Long? {
        for (page in 1..MAX_PAGES) {
            val batch = parseAssets(JSONArray(read(get(assetsUrl(repo, releaseId, page), "application/vnd.github+json", 60_000))))
            batch[file]?.let { return it }
            if (batch.size < PAGE_SIZE) return null
        }
        return null
    }

    private fun read(conn: HttpURLConnection): String =
        try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }

    private fun get(url: String, accept: String, timeoutMs: Int): HttpURLConnection {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = timeoutMs
            if (sendsToken(target)) {
                conn.setRequestProperty("Accept", accept)
                conn.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
                if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            when {
                code in 200..299 -> return conn
                code in REDIRECTS -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    target = redirectTarget(target, location)
                }
                else -> {
                    conn.disconnect()
                    throw HttpException(code, "")
                }
            }
        }
        throw IOException("Trop de redirections")
    }

    companion object {
        const val MANIFEST = "manifest.json"
        private const val HOST = "api.github.com"
        private const val API_VERSION = "2022-11-28"
        private const val MAX_REDIRECTS = 3
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        fun releaseUrl(repo: String, tag: String) = "https://$HOST/repos/$repo/releases/tags/$tag"

        fun assetUrl(repo: String, id: Long) = "https://$HOST/repos/$repo/releases/assets/$id"

        fun assetsUrl(repo: String, releaseId: Long, page: Int) = "https://$HOST/repos/$repo/releases/$releaseId/assets?per_page=$PAGE_SIZE&page=$page"

        /** Le jeton ne part qu'à l'API GitHub, en HTTPS. */
        fun sendsToken(url: String): Boolean = URI(url).let { it.scheme == "https" && it.host == HOST }

        /** Cible d'une redirection, relative ou absolue ; HTTPS seulement. */
        fun redirectTarget(from: String, location: String?): String {
            if (location.isNullOrEmpty()) throw IOException("Redirection sans destination")
            val target = URI(from).resolve(location)
            if (target.scheme != "https") throw IOException("Redirection hors HTTPS refusée")
            return target.toString()
        }
    }
}

/** Release GitHub : son id et ses assets (nom → id). */
internal data class GitHubReleaseInfo(val id: Long, val assets: Map<String, Long>)

/** `GET /repos/<repo>/releases/tags/<tag>` : `{id, assets:[{id, name}]}`. */
internal fun parseRelease(json: String): GitHubReleaseInfo =
    JSONObject(json).let { GitHubReleaseInfo(it.getLong("id"), parseAssets(it.getJSONArray("assets"))) }

internal fun parseAssets(assets: JSONArray): Map<String, Long> =
    (0 until assets.length()).map { assets.getJSONObject(it) }.associate { it.getString("name") to it.getLong("id") }
